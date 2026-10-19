// POST /api/contact: the contact form on /contact.
// Sends the message by email through Resend (secret RESEND_API_KEY) and keeps a copy in
// the FEED KV namespace for 90 days (keys "msg:..."), so nothing is lost if email fails.
// Until rafikioutreach.org is verified in Resend, mail goes from Resend's test sender
// (onboarding@resend.dev), which only delivers to the Resend account's own address,
// morgan@rafikioutreach.org. Once the domain is verified, set CONTACT_FROM (for example
// "RAFIKI website <website@rafikioutreach.org>") and CONTACT_TO (for example
// info@rafikioutreach.org). Without RESEND_API_KEY the form falls back to the visitor's email app.

const TOPICS = ['General question', 'Giving', 'Sponsor a well', 'Fundraising', 'Expeditions', 'Partnerships', 'Media'];
const EMAIL = /^[^\s@<>()",;:]+@[^\s@<>()",;:]+\.[a-z]{2,}$/i;
const PER_HOUR = 5;

function json(body, status) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }
  });
}
const clean = (v, max) => String(v == null ? '' : v).replace(/\r\n?/g, '\n').replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '').trim().slice(0, max);

export async function onRequestPost({ request, env }) {
  // same-site posts only
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return json({ ok: false, reason: 'origin' }, 403);
  if (Number(request.headers.get('Content-Length') || 0) > 16000) return json({ ok: false, reason: 'too-large' }, 413);

  let b;
  try { b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
  if (!b || typeof b !== 'object') return json({ ok: false, reason: 'bad-request' }, 400);

  // spam traps: a hidden field people never fill, and forms sent within 3 seconds of opening
  if (clean(b.website, 200) || (Number(b.elapsed) > 0 && Number(b.elapsed) < 3000)) return json({ ok: true }, 200);

  const msg = {
    topic: TOPICS.includes(b.topic) ? b.topic : 'General question',
    name: clean(b.name, 100),
    email: clean(b.email, 200),
    message: clean(b.message, 1500),
    page: clean(b.page, 200),
    at: new Date().toISOString(),
    country: (request.cf && request.cf.country) || ''
  };
  if (msg.message.length < 4) return json({ ok: false, reason: 'message' }, 400);
  if (!EMAIL.test(msg.email)) return json({ ok: false, reason: 'email' }, 400);

  if (!env.RESEND_API_KEY) return json({ ok: false, reason: 'not-configured' }, 503);

  // a light limit per visitor, so the form can't be used to flood the inbox
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  if (env.FEED) {
    const key = 'rl:contact:' + ip, n = Number(await env.FEED.get(key)) || 0;
    if (n >= PER_HOUR) return json({ ok: false, reason: 'rate' }, 429);
    await env.FEED.put(key, String(n + 1), { expirationTtl: 3600 });
    await env.FEED.put('msg:' + msg.at + ':' + Math.random().toString(36).slice(2, 8), JSON.stringify(msg), { expirationTtl: 90 * 86400 });
  }

  const who = msg.name || msg.email;
  const text = [
    msg.message, '', '—',
    'From: ' + (msg.name ? msg.name + ' <' + msg.email + '>' : msg.email),
    'Topic: ' + msg.topic,
    'Sent from: ' + (msg.page || '/contact') + (msg.country ? ' (visitor in ' + msg.country + ')' : ''),
    'Reply to this email to answer them.'
  ].join('\n');
  try {
    const r = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + env.RESEND_API_KEY, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        from: env.CONTACT_FROM || 'RAFIKI website <onboarding@resend.dev>',
        to: [env.CONTACT_TO || (env.CONTACT_FROM ? 'info@rafikioutreach.org' : 'morgan@rafikioutreach.org')],
        reply_to: msg.email,
        subject: 'Website: ' + msg.topic + ' from ' + who,
        text
      })
    });
    if (!r.ok) { console.error('contact email:', r.status, (await r.text()).slice(0, 300)); return json({ ok: false, reason: 'send' }, 502); }
  } catch (e) {
    console.error('contact email:', e && e.message);
    return json({ ok: false, reason: 'send' }, 502);
  }
  return json({ ok: true }, 200);
}

export function onRequest() {
  return json({ ok: false, reason: 'method' }, 405);
}
