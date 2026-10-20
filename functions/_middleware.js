// Share previews on preview addresses (rafiki-redesign.pages.dev and the like):
// the pages name www.rafikioutreach.org in og:image / og:url, which is still the old site
// until the domain moves, so on any other host those two tags point at this host instead.
// Canonical links are left alone, so search engines keep treating rafikioutreach.org as home.
const PROD = 'https://www.rafikioutreach.org';

// pin.rafikioutreach.org is the field app's own address: its front door opens the app.
// pin. and test. are working copies on the real domain, so search engines are told to skip them.
const FIELD = 'pin.rafikioutreach.org';
const HIDDEN = /^(pin|test)\.rafikioutreach\.org$/;

export async function onRequest({ request, next }) {
  const url = new URL(request.url);
  // on the field app's address, any website page goes to the app; the app's own files, the API and assets still load
  if (url.hostname === FIELD && !/^\/(pin|api|app|vendor|img|fonts|\.well-known)(\/|$|\.|-)/.test(url.pathname) && !/\.[a-z0-9]{2,12}$/i.test(url.pathname)) {
    return Response.redirect(url.origin + '/pin' + url.search, 302);
  }
  let res = await next();
  if (HIDDEN.test(url.hostname)) { res = new Response(res.body, res); res.headers.set('X-Robots-Tag', 'noindex, nofollow'); }
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
