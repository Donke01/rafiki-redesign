// POST /api/subscribe: the "Letters from the field" newsletter box.
// The browser only ever talks to this site. This function checks the visitor with
// Cloudflare Turnstile (secret TURNSTILE_SECRET; skipped until it is set), then adds the
// address to the mailing list through the Mailchimp Marketing API (secret MAILCHIMP_API_KEY,
// e.g. "abc123…-us7"; audience id below). Until the API key is set, signups are kept in the
// FEED KV namespace ("sub:<email>") and added to Mailchimp on the first signup after it is set.
// Signups are added as subscribed straight away: Mailchimp's own confirmation email carries
// Mailchimp branding, so a Rafiki-branded confirmation can be added once rafikioutreach.org
// is verified in Resend.

const LIST_ID = '7e30ec875e';
const EMAIL = /^[^\s@<>()",;:]+@[^\s@<>()",;:]+\.[a-z]{2,}$/i;
const PER_HOUR = 6;

function json(body, status) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }
  });
}

async function md5(s) {
  const d = await crypto.subtle.digest('MD5', new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, '0')).join('');
}

async function turnstileOk(env, token, ip) {
  if (!env.TURNSTILE_SECRET) return true;
  if (!token) return false;
  const form = new FormData();
  form.append('secret', env.TURNSTILE_SECRET); form.append('response', token); if (ip) form.append('remoteip', ip);
  try {
    const r = await fetch('https://challenges.cloudflare.com/turnstile/v0/siteverify', { method: 'POST', body: form });
    const j = await r.json();
    return !!j.success;
  } catch (e) { return false; }
}

// Adds or updates one address. Returns 'added', 'already' or throws.
export async function addToList(env, email) {
  const key = env.MAILCHIMP_API_KEY, dc = (key.split('-')[1] || 'us7').replace(/[^a-z0-9]/g, '');
  const id = await md5(email.toLowerCase());
  const auth = 'Basic ' + btoa('rafiki:' + key);
  const base = 'https://' + dc + '.api.mailchimp.com/3.0/lists/' + LIST_ID + '/members/' + id;
  const cur = await fetch(base + '?fields=status', { headers: { Authorization: auth } });
  if (cur.ok) {
    const c = await cur.json();
    if (c.status === 'subscribed') return 'already';
  }
  const r = await fetch(base, {
    method: 'PUT',
    headers: { Authorization: auth, 'Content-Type': 'application/json' },
    body: JSON.stringify({ email_address: email, status: 'subscribed', status_if_new: 'subscribed' })
  });
  if (!r.ok) {
    const t = await r.text();
    const err = new Error('mailchimp ' + r.status + ' ' + t.slice(0, 300));
    err.status = r.status; err.body = t;
    throw err;
  }
  return 'added';
}

async function flushQueue(env) {
  if (!env.FEED || !env.MAILCHIMP_API_KEY) return;
  const l = await env.FEED.list({ prefix: 'sub:', limit: 20 });
  for (const k of l.keys) {
    try { await addToList(env, k.name.slice(4)); await env.FEED.delete(k.name); }
    catch (e) { console.error('subscribe queue:', e.message); if (e.status === 400) await env.FEED.delete(k.name); }
  }
}

export async function onRequestPost({ request, env, waitUntil }) {
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return json({ ok: false, reason: 'origin' }, 403);
  if (Number(request.headers.get('Content-Length') || 0) > 8000) return json({ ok: false, reason: 'too-large' }, 413);
  let b;
  try { b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
  if (!b || typeof b !== 'object') return json({ ok: false, reason: 'bad-request' }, 400);

  // spam traps: a hidden field people never fill, and forms sent within 2 seconds of the page opening
  if (String(b.website || '').trim() || (Number(b.elapsed) > 0 && Number(b.elapsed) < 2000)) return json({ ok: true, status: 'added' }, 200);

  const email = String(b.email || '').trim().slice(0, 200);
  if (!EMAIL.test(email)) return json({ ok: false, reason: 'email' }, 400);

  const ip = request.headers.get('CF-Connecting-IP') || '';
  if (!(await turnstileOk(env, String(b.token || ''), ip))) return json({ ok: false, reason: 'check' }, 400);

  if (env.FEED) {
    const rk = 'rl:sub:' + (ip || 'unknown'), n = Number(await env.FEED.get(rk)) || 0;
    if (n >= PER_HOUR) return json({ ok: false, reason: 'rate' }, 429);
    await env.FEED.put(rk, String(n + 1), { expirationTtl: 3600 });
  }

  if (!env.MAILCHIMP_API_KEY) {
    if (!env.FEED) return json({ ok: false, reason: 'not-configured' }, 503);
    await env.FEED.put('sub:' + email.toLowerCase(), JSON.stringify({ at: new Date().toISOString() }));
    return json({ ok: true, status: 'added' }, 200);
  }
  try {
    const status = await addToList(env, email);
    if (waitUntil) waitUntil(flushQueue(env));
    return json({ ok: true, status }, 200);
  } catch (e) {
    console.error('subscribe:', e.message);
    // Mailchimp refuses fake-looking or previously removed addresses with a 400
    if (e.status === 400) return json({ ok: false, reason: /forgotten|compliance|unsubscribe/i.test(e.body || '') ? 'removed' : 'email' }, 400);
    if (env.FEED) { await env.FEED.put('sub:' + email.toLowerCase(), JSON.stringify({ at: new Date().toISOString() })); return json({ ok: true, status: 'added' }, 200); }
    return json({ ok: false, reason: 'down' }, 502);
  }
}

export function onRequest() {
  return json({ ok: false, reason: 'method' }, 405);
}
