// Share previews on preview addresses (rafiki-redesign.pages.dev and the like):
// the pages name www.rafikioutreach.org in og:image / og:url, which is still the old site
// until the domain moves, so on any other host those two tags point at this host instead.
// Canonical links are left alone, so search engines keep treating rafikioutreach.org as home.
const PROD = 'https://www.rafikioutreach.org';

// pin.rafikioutreach.org is the field app's own address: its front door opens the app.
// pin. and test. are working copies on the real domain, so search engines are told to skip them.
const FIELD = 'pin.rafikioutreach.org';
const HIDDEN = /^(pin|test)\.rafikioutreach\.org$/;

// Google Analytics (GA4) for the public website.
// Never on the field app, the API, or private links (donor pages, project share links, the repair tracker).
const GA_ID = /^G-[A-Z0-9]{4,20}$/;
// RAFIKI's GA4 Measurement ID (public by design: it appears in every page's source). GA_MEASUREMENT_ID in Cloudflare overrides it.
const GA_DEFAULT = 'G-QQQ2X6420B';
const GA_SKIP = /^\/(pin|app|api|donor|p|project|tracker|vendor)(\/|$|\.|-)/;
function gaTag(env, url) {
  const id = String((env && env.GA_MEASUREMENT_ID) || GA_DEFAULT).trim().toUpperCase();
  if (!GA_ID.test(id) || url.hostname === FIELD || HIDDEN.test(url.hostname) || GA_SKIP.test(url.pathname)) return '';
  return '<script async src="https://www.googletagmanager.com/gtag/js?id=' + id + '"></script>' +
    '<script>window.dataLayer=window.dataLayer||[];function gtag(){dataLayer.push(arguments)}gtag("js",new Date());gtag("config","' + id + '");' +
    // a tap on any Give / Sponsor / Fundraise link counts as donate_click (the gift itself is donation_complete on /thank-you)
    'document.addEventListener("click",function(e){var a=e.target&&e.target.closest&&e.target.closest("a[href]");if(!a)return;var h=a.getAttribute("href")||"";' +
    'if(/^(https?:\\/\\/(www\\.)?rafikioutreach\\.org)?\\/(donate|sponsor|fundraise)([?#\\/]|$)/.test(h))gtag("event","donate_click",{link_url:h});},true);</script>';
}

export async function onRequest({ request, next, env }) {
  const url = new URL(request.url);
  // a donor's private link on the field app's address opens the app in donor mode (the Android app claims
  // https://pin.rafikioutreach.org/pin; the token rides in the #fragment, which is never sent to a server)
  const dl = url.hostname === FIELD && /^\/donor\/([A-Za-z0-9_-]{43})\/?$/.exec(url.pathname);
  if (dl) return new Response(null, { status: 302, headers: { Location: url.origin + '/pin#donor=' + dl[1], 'Cache-Control': 'private, no-store', 'Referrer-Policy': 'no-referrer' } });
  // on the field app's address, any website page goes to the app; the app's own files, the API and assets still load
  if (url.hostname === FIELD && !/^\/(pin|api|app|vendor|img|fonts|\.well-known)(\/|$|\.|-)/.test(url.pathname) && !/\.[a-z0-9]{2,12}$/i.test(url.pathname)) {
    return Response.redirect(url.origin + '/pin' + url.search, 302);
  }
  let res = await next();
  // a WebSocket (team chat) must go back untouched; copying the response would drop the socket
  if (res.status === 101 || res.webSocket) return res;
  if (HIDDEN.test(url.hostname)) { res = new Response(res.body, res); res.headers.set('X-Robots-Tag', 'noindex, nofollow'); }
  if (!(res.headers.get('Content-Type') || '').includes('text/html')) return res;
  const rw = new HTMLRewriter();
  let changed = false;
  if (!/(^|\.)rafikioutreach\.org$/.test(url.hostname)) {
    const here = url.origin;
    const swap = {
      element(el) {
        const c = el.getAttribute('content');
        if (c && c.startsWith(PROD)) el.setAttribute('content', here + c.slice(PROD.length));
      }
    };
    rw.on('meta[property="og:image"]', swap).on('meta[property="og:url"]', swap).on('meta[name="twitter:image"]', swap);
    changed = true;
  }
  const ga = gaTag(env, url);
  if (ga) { rw.on('head', { element(el) { el.append(ga, { html: true }); } }); changed = true; }
  return changed ? rw.transform(res) : res;
}
