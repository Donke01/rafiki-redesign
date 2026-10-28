// GET /stories/<id>-<slug>: one published story, with its share preview tags (lib/stories-page.js)
import { storyPage } from '../../lib/stories-page.js';
export const onRequestGet = ctx => storyPage(ctx);
