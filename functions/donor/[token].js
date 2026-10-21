// GET /donor/<token>: a donor's private page. The page itself (donor.html) is the same for everyone; it reads
// the token from the address and asks /api/donor for that donor's data. Here it is only served with headers
// that keep it private: not indexed, not cached by shared caches, and the address (with its token) never sent
// on to other sites (map tiles, Google Maps) as a referrer.
export async function onRequestGet({ request, env }) {
  const url = new URL(request.url);
  let res = await env.ASSETS.fetch(new Request(new URL('/donor', url.origin), { headers: { Accept: 'text/html' } }));
  if (res.status >= 300 && res.status < 400 && res.headers.get('Location')) res = await env.ASSETS.fetch(new Request(new URL(res.headers.get('Location'), url.origin)));
  const out = new Response(res.body, { status: res.ok ? 200 : res.status, headers: res.headers });
  out.headers.set('Cache-Control', 'private, no-store');
  out.headers.set('X-Robots-Tag', 'noindex, nofollow');
  out.headers.set('Referrer-Policy', 'strict-origin');
  return out;
}
