// Share previews on preview addresses (rafiki-redesign.pages.dev and the like):
// the pages name www.rafikioutreach.org in og:image / og:url, which is still the old site
// until the domain moves, so on any other host those two tags point at this host instead.
// Canonical links are left alone, so search engines keep treating rafikioutreach.org as home.
const PROD = 'https://www.rafikioutreach.org';

export async function onRequest({ request, next }) {
  const res = await next();
  const url = new URL(request.url);
  if (/(^|\.)rafikioutreach\.org$/.test(url.hostname)) return res;
  if (!(res.headers.get('Content-Type') || '').includes('text/html')) return res;
  const here = url.origin;
  const swap = {
    element(el) {
      const c = el.getAttribute('content');
      if (c && c.startsWith(PROD)) el.setAttribute('content', here + c.slice(PROD.length));
    }
  };
  return new HTMLRewriter()
    .on('meta[property="og:image"]', swap)
    .on('meta[property="og:url"]', swap)
    .on('meta[name="twitter:image"]', swap)
    .transform(res);
}
