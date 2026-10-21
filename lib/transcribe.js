// Speech to text for RAFIKI Pin voice notes (Kiswahili or English, the language is detected).
//
// Settings (Cloudflare → Workers & Pages → the site → Settings → Variables and secrets). No key in the
// code: until a key is set the feature is simply off, and the Office shows which variable to add.
//
//   OPENAI_API_KEY        (secret) switches it on with OpenAI's speech to text (Whisper), the primary
//                         provider: it reads every format the apps record (WebM/Ogg from browsers,
//                         M4A/AAC from Android), up to 25 MB, and detects Kiswahili or English itself.
//   TRANSCRIBE_PROVIDER   optional: "openai" (the default when OPENAI_API_KEY is set), "google", or "off"
//                         to switch typing out off without removing the key.
//   TRANSCRIBE_MODEL      optional OpenAI model, default "whisper-1" (also "gpt-4o-mini-transcribe",
//                         "gpt-4o-transcribe").
//   GOOGLE_STT_KEY        (secret) the alternative: a Google Cloud Speech-to-Text API key, used when
//                         TRANSCRIBE_PROVIDER=google (or when it is the only key set). Google's quick
//                         recognition only takes up to 1 minute of WebM/Ogg Opus or MP3, so voice notes
//                         from the Android app (M4A) and longer notes are skipped with this provider.
//   TRANSCRIBE_DAILY_MAX  optional cap on voice notes typed out per day, all users together (default 200).
//
// Exports: transcribeSetup(env) → { on, provider, model, missing, note } (no secrets in it)
//          transcribe(env, bytes, mime) → { text, lang } ; lang is "sw", "en" or "" (unknown)

const OPENAI_URL = 'https://api.openai.com/v1/audio/transcriptions';
const GOOGLE_URL = 'https://speech.googleapis.com/v1p1beta1/speech:recognize';
// words the field team uses a lot: helps the speech model spell them
const HINT = 'RAFIKI Outreach site visit, Kenya, Tanzania, Uganda, Malawi. Kisima, pampu, borehole, hand pump, maji, kamati ya maji, water committee, tank, tap, mfereji.';
const EXT = { 'audio/webm': 'webm', 'audio/ogg': 'ogg', 'audio/mp4': 'm4a', 'audio/x-m4a': 'm4a', 'audio/aac': 'm4a', 'audio/mpeg': 'mp3', 'audio/wav': 'wav' };
const GOOGLE_ENC = { 'audio/webm': ['WEBM_OPUS', 48000], 'audio/ogg': ['OGG_OPUS', 48000], 'audio/mpeg': ['MP3', 44100] };

export function transcribeSetup(env) {
  const want = String(env.TRANSCRIBE_PROVIDER || '').trim().toLowerCase();
  if (want === 'off') return { on: false, provider: 'off', missing: [], note: 'Switched off with TRANSCRIBE_PROVIDER=off.' };
  if (want === 'google' || (!want && !env.OPENAI_API_KEY && env.GOOGLE_STT_KEY)) {
    return env.GOOGLE_STT_KEY ? { on: true, provider: 'google', model: 'google-stt', missing: [], note: 'Google Speech-to-Text (browser voice notes up to 1 minute).' }
      : { on: false, provider: 'google', missing: ['GOOGLE_STT_KEY'], note: 'Add GOOGLE_STT_KEY in Cloudflare to switch it on.' };
  }
  if (want && want !== 'openai') return { on: false, provider: want, missing: [], note: 'TRANSCRIBE_PROVIDER must be "openai", "google" or "off".' };
  return env.OPENAI_API_KEY ? { on: true, provider: 'openai', model: model(env), missing: [], note: 'OpenAI speech to text (' + model(env) + ').' }
    : { on: false, provider: 'openai', missing: ['OPENAI_API_KEY'], note: 'Add OPENAI_API_KEY in Cloudflare to switch it on.' };
}
const model = env => String(env.TRANSCRIBE_MODEL || 'whisper-1').trim();

// "swahili"/"sw"/"sw-KE" → "sw"; "english"/"en" → "en"
export function langCode(l) {
  const s = String(l || '').toLowerCase();
  if (/^(sw|swa|swahili|kiswahili)\b/.test(s) || s.indexOf('sw-') === 0) return 'sw';
  if (/^(en|eng|english)\b/.test(s) || s.indexOf('en-') === 0) return 'en';
  return '';
}
const tidy = t => String(t || '').replace(/\s+/g, ' ').trim();

export async function transcribe(env, bytes, mime) {
  const cfg = transcribeSetup(env);
  if (!cfg.on) { const e = new Error('off'); e.code = 'off'; throw e; }
  const type = String(mime || '').split(';')[0].trim().toLowerCase();
  const data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  return cfg.provider === 'google' ? google(env, data, type) : openai(env, data, type);
}

async function openai(env, data, type) {
  const m = model(env), verbose = m === 'whisper-1';
  const form = new FormData();
  form.append('file', new Blob([data], { type: type || 'audio/webm' }), 'voice-note.' + (EXT[type] || 'webm'));
  form.append('model', m);
  form.append('response_format', verbose ? 'verbose_json' : 'json');
  form.append('prompt', HINT);
  const r = await fetch(OPENAI_URL, { method: 'POST', headers: { Authorization: 'Bearer ' + env.OPENAI_API_KEY }, body: form });
  if (!r.ok) { const e = new Error('openai ' + r.status + ': ' + (await r.text()).slice(0, 160)); e.code = r.status === 429 ? 'rate' : 'down'; throw e; }
  const j = await r.json();
  const text = tidy(j.text);
  return { text, lang: langCode(j.language) || guessLang(text), secs: Number(j.duration) || null };
}

async function google(env, data, type) {
  const enc = GOOGLE_ENC[type];
  if (!enc) { const e = new Error('google: format ' + type); e.code = 'format'; throw e; }
  const body = { config: { encoding: enc[0], sampleRateHertz: enc[1], languageCode: 'sw-KE', alternativeLanguageCodes: ['en-KE', 'sw-TZ', 'en-US'], enableAutomaticPunctuation: true, speechContexts: [{ phrases: HINT.split(/[,.]\s*/).filter(Boolean) }] },
    audio: { content: b64(data) } };
  const r = await fetch(GOOGLE_URL + '?key=' + encodeURIComponent(env.GOOGLE_STT_KEY), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  if (!r.ok) { const e = new Error('google ' + r.status + ': ' + (await r.text()).slice(0, 160)); e.code = r.status === 429 ? 'rate' : 'down'; throw e; }
  const j = await r.json(), res = j.results || [];
  const text = tidy(res.map(x => (x.alternatives && x.alternatives[0] && x.alternatives[0].transcript) || '').join(' '));
  return { text, lang: langCode(res[0] && res[0].languageCode) || guessLang(text), secs: null };
}

function b64(u8) {
  let s = '';
  for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(s);
}

// when the provider doesn't say: common Kiswahili words
const SW = /\b(na|ya|wa|kwa|ni|za|cha|kisima|maji|pampu|haifanyi|imeharibika|kazi|sana|lakini|watu|hakuna|iko|kuna|tumepata|leo)\b/gi;
export function guessLang(text) {
  const words = String(text || '').split(/\s+/).filter(Boolean).length;
  if (!words) return '';
  return (String(text).match(SW) || []).length / words > 0.15 ? 'sw' : 'en';
}
