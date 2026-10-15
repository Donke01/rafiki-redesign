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

## Before going live

- **Donations:** paste the Zeffy embed code into `donate.html`. Search for `ZEFFY:` and replace the "Give today" box under it.
- **Numbers:** the figures below are consistent across the site but still need confirming with Rafiki's real data.
  - Totals (`progress.html` data props, `index.html` counters): 300 wells completed, 12 under construction, 780,500+ people, 230,000+ households, 10,000 households a month goal, about 2,600 people per well, $5 per person.
  - Per country (`var CTRY` in every page): Kenya 120 + 6, Tanzania 60 + 2, Uganda 60 + 2, Malawi 60 + 2 wells; people 312,200 / 156,100 / 156,100 / 156,100.
  - The 312 projects on `/stories` and `/progress` are sample data (`var PROJECTS`), generated in the page script. Replace with real projects.
- **Team:** Uganda and Malawi directors and the Tanzania, Uganda and Malawi office details are hidden until added to `var OFFICES`.
- **Newsletter (Mailchimp):** in Mailchimp go to Audience → Signup forms → Embedded forms and copy the form `action` URL (`https://….list-manage.com/subscribe/post?u=…&id=…`). Paste it into `var MAILCHIMP_URL = ''` in `index.html` (source: `D06-Bento.dc.html`). Until then the signup opens the visitor's email app. Mailchimp sends its own confirmation email (double opt-in). The /updates page links to this signup (`/#field-notes`).

## Deploy

- Vercel: import the repo (framework preset: Other, no build command). `vercel.json` holds clean URLs and redirects.
- Cloudflare Pages (free, unlimited traffic): connect the repo, build command `npm run build:pages`, output directory `dist`. `_redirects` holds the redirects and `_headers` the Content-Security-Policy; `404.html` is served for unknown pages. Only the site files are copied into `dist`, so `server.js`, `package.json` and this README are not published.
- Railway or any Node host: `npm start` runs `server.js`, a small static server. It sends a Content-Security-Policy that allows `'unsafe-eval'`, which the page runtime (`support.js`) needs.
