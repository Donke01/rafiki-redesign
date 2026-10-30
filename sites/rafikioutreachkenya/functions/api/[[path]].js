// The Kenya trial site reads the same live project data (numbers, map, photos) as the main website.
// Its own address can't call the main website's API from the browser, so this passes /api/... through on the server.
const SOURCE = 'https://test.rafikioutreach.org';

export async function onRequest({ request }) {
  const url = new URL(request.url);
  const headers = new Headers(request.headers);
  for (const h of ['host', 'cookie', 'cf-connecting-ip', 'x-forwarded-for', 'x-real-ip']) headers.delete(h);
  const body = request.method === 'GET' || request.method === 'HEAD' ? undefined : request.body;
  const res = await fetch(SOURCE + url.pathname + url.search, { method: request.method, headers, body, redirect: 'manual' });
  const out = new Response(res.body, res);
  out.headers.delete('set-cookie');
  return out;
}
