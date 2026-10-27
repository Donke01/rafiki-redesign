// Translate · Tafsiri for chat messages: English ↔ Kiswahili (chat2).
//
// Settings (Cloudflare → Workers & Pages → the site → Settings → Variables and secrets). No key in the code.
//   OPENAI_API_KEY        (secret, the same one speech to text and story drafts use) switches it on.
//   TRANSLATE_MODEL       optional, default "gpt-4o-mini".
//   TRANSLATE_PROVIDER    optional: "off" switches translating off without removing the key.
// Without a key the app opens Google Translate with the text instead (nothing is sent from our side).
//
// Exports: translateOn(env) → boolean; isSw(text); target(text, to) → 'en' | 'sw'; googleUrl(text, to);
//          translate(env, text, to) → { text, to } (throws { code: 'off' | 'down' | 'rate' })

export const MAX_TEXT = 2000;
const URL_AI = 'https://api.openai.com/v1/chat/completions';

export function translateOn(env) { return !!(env && env.OPENAI_API_KEY) && String(env.TRANSLATE_PROVIDER || '').trim().toLowerCase() !== 'off'; }
// Kiswahili or not: common short words, or nearly every word ending in a vowel (the app has the same test, cwSwGuess)
const SWW = /^(na|ya|wa|za|la|cha|vya|kwa|ni|si|hii|huu|hiyo|hapa|pale|kama|pia|sasa|kila|leo|jana|kesho|bado|sana|asante|habari|ndiyo|hapana|hakuna|kuna|iko|yuko|nini|lini|wapi|maji|kisima|pampu|kazi|watu|watoto|safi|siku|mzuri|nzuri|tafadhali|karibu|pole|sawa|mimi|wewe|yeye|sisi|nyinyi|wao|lakini|au|tu|tena|mwenyekiti|kamati)$/;
export function isSw(text) {
  const w = String(text || '').toLowerCase().replace(/[^a-z'\s]/g, ' ').split(/\s+/).filter(x => x.length > 1);
  if (!w.length) return false;
  const hits = w.filter(x => SWW.test(x)).length, vow = w.filter(x => /[aeiou]$/.test(x)).length;
  return hits / w.length >= 0.15 || (w.length >= 3 && vow / w.length >= 0.85);
}
// the other language: Kiswahili text goes to English, anything else to Kiswahili (unless the app asks for one)
export function target(text, to) { return to === 'en' || to === 'sw' ? to : isSw(text) ? 'en' : 'sw'; }
export function googleUrl(text, to) { return 'https://translate.google.com/?sl=auto&tl=' + (to === 'en' ? 'en' : 'sw') + '&text=' + encodeURIComponent(String(text || '').slice(0, MAX_TEXT)) + '&op=translate'; }

export async function translate(env, text, to) {
  if (!translateOn(env)) { const e = new Error('off'); e.code = 'off'; throw e; }
  const lang = to === 'en' ? 'English' : 'Kiswahili';
  const sys = 'You translate short chat messages for RAFIKI Outreach, a charity team in Kenya, Tanzania, Uganda and Malawi, between English and Kiswahili. ' +
    'Translate the user\'s message into ' + lang + '. Keep names of people, places and projects, numbers, amounts and emojis exactly as they are. ' +
    'Keep it short and natural, as a teammate would write it. Reply with the translation only, no quotes and no notes.';
  let r;
  try {
    r = await fetch(URL_AI, { method: 'POST', headers: { Authorization: 'Bearer ' + env.OPENAI_API_KEY, 'Content-Type': 'application/json' },
      body: JSON.stringify({ model: String(env.TRANSLATE_MODEL || 'gpt-4o-mini').trim(), temperature: 0, max_tokens: 1200, messages: [{ role: 'system', content: sys }, { role: 'user', content: String(text) }] }) });
  } catch (e) { const x = new Error('down'); x.code = 'down'; throw x; }
  if (!r.ok) { const e = new Error('openai ' + r.status); e.code = r.status === 429 ? 'rate' : 'down'; throw e; }
  const j = await r.json().catch(() => null), out = j && j.choices && j.choices[0] && j.choices[0].message && String(j.choices[0].message.content || '').trim();
  if (!out) { const e = new Error('empty'); e.code = 'down'; throw e; }
  return { text: out.replace(/^["“](.*)["”]$/s, '$1').slice(0, MAX_TEXT * 2), to };
}
