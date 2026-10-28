// GET /stories: the stories hub (stories.html); old project-list links go on to /projects (lib/stories-page.js)
import { hub } from '../../lib/stories-page.js';
export const onRequestGet = ctx => hub(ctx);
