import { SELF } from 'cloudflare:test';
import { it, expect } from 'vitest';
it('lists', async () => {
  const r = await SELF.fetch('https://chat/list', { headers: { 'X-Chat-User': 'Grace', 'X-Chat-Home': 'ke' } });
  const j = await r.json();
  expect(j.ok).toBe(true);
  expect(j.chats.map(c => c.id).sort()).toEqual(['t:all', 't:ke']);
});
