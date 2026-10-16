# Rafiki Outreach website (redesign)

Static site for Rafiki Outreach. Kenya is the default country; visitors can switch to Tanzania, Uganda, Malawi or all countries.

## Pages

The menu follows the owner's eight sections.

| URL | Page | Menu |
| --- | --- | --- |
| `/` | Home | Home |
| `/about` | Mission, values, story and leadership. `/meet` redirects here | About |
| `/#where-we-work` | Country cards on the homepage; each opens its country page | Where we work |
| `/kenya`, `/tanzania`, `/uganda`, `/malawi` | Country pages: team, work, impact, projects, map, stories, needs | Where we work |
| `/projects` | Completed, current and upcoming projects, donors and partners | Our projects |
| `/communities` | Impacted communities (Tanzania, 2023). `/about-1` redirects here | Our projects |
| `/water` | Our approach: why water, the partnership model, the Community Water Agreement | Our approach |
| `/progress` | Our impact in numbers, annual reports | Our impact |
| `/donate` | Get involved: give, sponsor a project, expeditions, volunteer | Get involved |
| `/trips`, `/event-list` | Expeditions and events | Get involved |
| `/stories` | Stories and updates: every project, with filters | Stories & updates |
| `/loan` | Loan program (closed; linked from About and the footer) | |

Anything still to be supplied by Rafiki is shown on the page in a yellow "To add" box or tag.

Those boxes and tags only show on previews (localhost, Vercel or Cloudflare preview links). On rafikioutreach.org they are hidden, together with any section that would be empty without them. Add `?live` to a preview link to see the public view, or `?preview` on the live site to see the boxes. Notes that say figures or projects are samples stay visible everywhere until the real data is in. To hide a whole block on the live site, give it the class `draft`.

## Images

Pages use the WebP files in `img/` (photos at most 1200px, logos 120px tall). The original JPG and PNG files are kept for social previews (`og:image`) and as sources. When adding a photo, also save a `.webp` copy and point the page at that.

## Before going live

- **Donations:** paste the Zeffy embed code into `donate.html`. Search for `ZEFFY:` and replace the "Give today" box under it.
- **Numbers:** the figures below are consistent across the site but still need confirming with Rafiki's real data.
  - Totals (`progress.html` data props, `index.html` counters): 300 wells completed, 12 under construction, 780,500+ people, 230,000+ households, 10,000 households a month goal, about 2,600 people per well, $5 per person.
  - Per country (`var CTRY` in every page): Kenya 120 + 6, Tanzania 60 + 2, Uganda 60 + 2, Malawi 60 + 2 wells; people 312,200 / 156,100 / 156,100 / 156,100.
  - The 312 projects on `/stories` and `/progress` are sample data (`var PROJECTS`), generated in the page script. Replace with real projects.
- **Team:** Uganda and Malawi directors and the Tanzania, Uganda and Malawi office details are hidden until added to `var OFFICES`.
- **Newsletter (Mailchimp):** in Mailchimp go to Audience → Signup forms → Embedded forms and copy the form `action` URL (`https://….list-manage.com/subscribe/post?u=…&id=…`). Paste it into `var MAILCHIMP_URL = ''` in `index.html` (source: `D06-Bento.dc.html`). Until then the signup opens the visitor's email app. Mailchimp sends its own confirmation email (double opt-in). The /updates page links to this signup (`/#field-notes`).

## Deploy

The site is hosted on **Cloudflare Pages** (free, unlimited traffic).

1. Cloudflare dashboard → Workers & Pages → Create → Pages → Connect to Git → choose `donke01/rafiki-redesign`.
2. Production branch `main`, framework preset None, build command `npm run build:pages`, build output directory `dist`.
3. Every push to `main` redeploys. Other branches get their own preview link.
4. Custom domain: the project → Custom domains → add `rafikioutreach.org` (and `www`). The yellow "To add" boxes only hide on that domain.

`_redirects` holds the redirects, `_headers` the Content-Security-Policy (the page runtime `support.js` needs `'unsafe-eval'`), and `404.html` is served for unknown pages. Only the site files are copied into `dist`, so `server.js`, `package.json` and this README are not published.

Local preview: `npm start` runs `server.js`, a small static server with the same headers, on port 3000. `vercel.json` is kept only in case the site moves to Vercel.
