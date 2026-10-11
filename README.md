# Rafiki Outreach — homepage redesign (design preview)

Static preview of the redesigned homepage (design 06), the impact stories / projects page and the progress page.

- `/` homepage
- `/stories` impact stories (all projects)
- `/progress` our progress in numbers

Deploy:
- Vercel: import the repo (framework preset: Other, no build command).
- Railway / any Node host: `npm start` runs `server.js`, a tiny static server. It sends a Content-Security-Policy that allows `'unsafe-eval'`, which the page runtime (`support.js`) needs.

Numbers marked [#] and the 312 sample projects are placeholders to be replaced with Rafiki's real data.
