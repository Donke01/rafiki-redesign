// POST /api/sms: community repair reports by SMS or WhatsApp (see lib/sms.js for what is understood).
//
// Africa's Talking (SMS, Kenya/Tanzania/Uganda/Malawi): in the AT dashboard → SMS → SMS Callback URLs →
//   Incoming Messages, paste   https://<your site>/api/sms?token=<SMS_WEBHOOK_TOKEN>
//   AT posts a form: from, to, text, date, id, linkId. The token is the shared secret.
//   Replies go out through AT's SMS API when AT_USERNAME and AT_API_KEY are set (AT_SENDER_ID optional:
//   your shortcode or approved sender name). AT_KEYWORD (optional): the keyword on a shared shortcode.
// Twilio (WhatsApp or SMS): on the number / WhatsApp sender → "A message comes in" → Webhook, HTTP POST,
//   paste   https://<your site>/api/sms
//   Twilio posts a form: From ("whatsapp:+2547…"), Body, MessageSid…; checked with the
//   X-Twilio-Signature header and TWILIO_AUTH_TOKEN. The reply goes back as TwiML.
// Until the matching secret is set in Cloudflare, that provider is refused (503 "not-set-up").
// Needs the FEED KV binding and MONDAY_API_TOKEN, like the rest of the pin app.
import { handleMessage, verifyTwilio, verifyToken, twiml, sendAfricasTalking } from '../../lib/sms.js';

const MAX_BODY = 16 * 1024;

function text(body, status, type) {
  return new Response(body, { status: status || 200, headers: { 'Content-Type': type || 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' } });
}

export async function onRequestPost({ request, env, waitUntil }) {
  const later = p => (waitUntil ? waitUntil(p) : p);
  const url = new URL(request.url);
  if (Number(request.headers.get('Content-Length') || 0) > MAX_BODY) return text('too large', 413);
  const raw = await request.text();
  if (raw.length > MAX_BODY) return text('too large', 413);
  const form = new URLSearchParams(raw), params = {};
  for (const [k, v] of form) params[k] = v;
  const twSig = request.headers.get('X-Twilio-Signature');
  if (twSig || params.MessageSid || params.AccountSid) {
    // Twilio
    if (!env.TWILIO_AUTH_TOKEN) return text('not-set-up: add TWILIO_AUTH_TOKEN in Cloudflare', 503);
    if (!(await verifyTwilio(env.TWILIO_AUTH_TOKEN, request.url, params, twSig))) return text('forbidden', 403);
    if (!env.FEED || !env.MONDAY_API_TOKEN) return text(twiml(''), 200, 'text/xml; charset=utf-8');
    const from = params.From || '';
    const r = await handleMessage(env, { ch: /^whatsapp:/i.test(from) ? 'whatsapp' : 'sms', from, text: params.Body || '', mid: params.MessageSid || '', to: params.To || '' }, { site: url.origin });
    return text(twiml(r.reply), 200, 'text/xml; charset=utf-8');
  }
  // Africa's Talking
  if (!env.SMS_WEBHOOK_TOKEN || String(env.SMS_WEBHOOK_TOKEN).length < 16) return text('not-set-up: add SMS_WEBHOOK_TOKEN (16+ characters) in Cloudflare', 503);
  if (!verifyToken(env.SMS_WEBHOOK_TOKEN, url.searchParams.get('token'))) return text('forbidden', 403);
  if (!params.from || params.text == null) return text('bad-request', 400);
  if (!env.FEED || !env.MONDAY_API_TOKEN) return text('not-set-up', 503);
  const r = await handleMessage(env, { ch: 'sms', from: params.from, text: params.text, mid: params.id || '', to: params.to || '' }, { site: url.origin });
  if (r.reply) later(sendAfricasTalking(env, r.entry.from, r.reply).catch(e => console.error('sms reply:', e && e.message)));
  // AT only needs a 200; the body is the reply text, for the logs
  return text(r.reply || 'OK', 200);
}

export function onRequest() { return text('method', 405); }
