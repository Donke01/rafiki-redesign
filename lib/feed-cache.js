// Keeps the monday.com feed for FEED_TTL_SECONDS (default 10 minutes) so visitors
// don't each cost a monday.com API call. With a KV namespace bound as FEED the copy
// is shared everywhere and the last good copy is served if monday.com is down;
// without it the copy lives in this worker's memory and Cloudflare's cache.
import { fetchBoard, buildFeed, BOARD_ID } from './monday.js';

const KEY = 'feed:v6'; // bump when the feed format or clean-up changes
const CACHE_URL = 'https://rafiki-feed.cache/v4';
let mem = null; // { at, feed }

async function cacheGet() {
  try { const r = await caches.default.match(CACHE_URL); return r ? await r.json() : null; } catch (e) { return null; }
}
function cachePut(rec, ttl) {
  try {
    return caches.default.put(CACHE_URL, new Response(JSON.stringify(rec), {
      headers: { 'Content-Type': 'application/json', 'Cache-Control': 'max-age=' + Math.ceil(ttl / 1000) }
    }));
  } catch (e) { return Promise.resolve(); }
}

export async function getFeed(env, waitUntil) {
  const ttl = (Number(env.FEED_TTL_SECONDS) || 600) * 1000;
  const now = Date.now();
  if (mem && now - mem.at < ttl) return mem.feed;

  let stored = env.FEED ? await env.FEED.get(KEY, 'json') : await cacheGet();
  if (stored && now - stored.at < ttl) { mem = stored; return stored.feed; }

  try {
    const { columns, items } = await fetchBoard(env.MONDAY_API_TOKEN, env.MONDAY_BOARD_ID || BOARD_ID, env.MONDAY_API_URL);
    const rec = { at: now, feed: buildFeed(columns, items) };
    mem = rec;
    const save = env.FEED ? env.FEED.put(KEY, JSON.stringify(rec)) : cachePut(rec, ttl);
    if (waitUntil) waitUntil(save); else await save;
    return rec.feed;
  } catch (e) {
    const old = stored || mem;
    if (old) return Object.assign({}, old.feed, { stale: true });
    throw e;
  }
}
