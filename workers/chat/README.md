# rafiki-chat: the RAFIKI Pin team chat worker

The team chat in RAFIKI Pin (web and Android) runs in this separate Cloudflare Worker, named **`rafiki-chat`**.
The site (Pages project **`rafiki-redesign`**) signs people in as it always has, then passes chat requests to this
worker through a **Service binding named `CHAT`**.

```
phone / browser ──► Pages Functions /api/chat/*  ──(Service binding CHAT)──►  rafiki-chat worker
                    functions/api/chat/[[path]].js                             ├─ Directory (1 Durable Object): who is in which chat,
                    lib/chat.js                                                │   unread counts, receipts, presence, all WebSockets
                    · PIN / monday.com session, X-Pin-Gen                      ├─ Conversation (1 Durable Object per chat): messages
                    · blocked and signed-out people refused                    └─ R2 bucket rafiki-chat-media (binding CHAT_MEDIA)
                    · donors and the shared team PIN refused
```

- Both Durable Object classes are **SQLite-backed**, which the Workers **Free** plan allows.
- The worker has **no public address**: `workers_dev = false`, `preview_urls = false` and no routes. Only the Pages
  project can reach it, so no shared secret is needed.
- Until the `CHAT` binding exists, every `/api/chat/*` answer is `{ ok: false, reason: 'chat-off' }`. The apps then say
  "Chat isn't switched on yet. The office can turn it on." Nothing else in the app changes.

## Setting it up (the owner, once)

Nothing here needs an API token. The R2 bucket `rafiki-chat-media` already exists. `wrangler.toml` binds it as
`CHAT_MEDIA`, so the first deploy creates that binding by itself.

### Step 1: Deploy the worker with Workers Builds (Cloudflare's Git integration)

1. Open the Cloudflare dashboard and choose **Workers & Pages** in the left menu.
2. Click **Create** (top right).
3. On the **Workers** tab, choose **Import a repository**.
4. Pick the GitHub account **Donke01** and the repository **rafiki-redesign**, then click **Next** (or **Begin setup**).
   - If GitHub isn't connected yet, click **Connect GitHub**.
   - Allow Cloudflare to see the repository, then come back.
5. Fill in the settings exactly:

   | Setting | Value |
   |---|---|
   | Project name | `rafiki-chat` (it must match `name` in `wrangler.toml`) |
   | Production branch | `main` |
   | Build command | `npm ci` |
   | Deploy command | `npx wrangler deploy` |
   | Non-production branch deploy command | `npx wrangler versions upload` (the default; leave it) |
   | **Root directory** (under **Advanced settings**; the field may be labelled **Path**) | `workers/chat` |

6. Click **Create and deploy** (or **Deploy**). Wait until the build says **Success** (about a minute).
   - The worker **rafiki-chat** now appears under **Workers & Pages**.
   - From now on, every push to `main` that changes `workers/chat` deploys it again.
   - Optional: in the worker's **Settings → Build → Build watch paths**, set **Include paths** to `workers/chat/*`, so other pushes don't rebuild it.

### Step 2: Connect the site to the worker (the `CHAT` binding)

1. In **Workers & Pages**, open the Pages project **rafiki-redesign**.
2. Go to **Settings → Bindings** and click **+ Add**.
3. Choose **Service binding**:
   - **Variable name:** `CHAT`
   - **Service:** `rafiki-chat`
   - Click **Save**.
4. The binding is for **Production**. For chat on preview addresses too, switch the environment at the top of the page to **Preview** and add the same binding.
5. Bindings take effect on the next deploy. Open **Deployments**, find the newest production deployment, then click **⋯ → Retry deployment** (or push any commit).

That's all. Open RAFIKI Pin and the Chat tab works.
- The Team chats ("All RAFIKI" and one per country) appear by themselves, filled from Office › Team.
- To check it from a browser while signed in, open `/api/chat/list`. It answers `"ok":true` instead of `"chat-off"`.

### Optional: a shared secret

Not needed while the worker has no public address. For a second lock:
1. Make a long random value.
2. Add it as a **secret** named `CHAT_SECRET` in both places:
   - the worker: **rafiki-chat → Settings → Variables and Secrets → + Add → Type: Secret**
   - the Pages project: **rafiki-redesign → Settings → Variables and Secrets**, for Production (and Preview)
3. Redeploy the Pages project.

The worker then refuses any request without that value. It also signs media links and WebSocket tickets with it. Without it, the worker makes its own signing key once and keeps it inside the Directory object.

### Alternative: deploy with the GitHub Action instead

`.github/workflows/chat-worker.yml` always runs the worker tests on changes to `workers/chat`. It deploys only when these
two repository secrets exist (GitHub → the repo → **Settings → Secrets and variables → Actions → New repository secret**):
- `CLOUDFLARE_API_TOKEN`: Cloudflare → **My Profile → API Tokens → Create Token → "Edit Cloudflare Workers"** template.
- `CLOUDFLARE_ACCOUNT_ID`: shown on the **Workers & Pages** overview page.

Use either Workers Builds or the Action, not both (both would deploy the same code; harmless, but twice).

## Endpoints (`/api/chat/*`)

The client contract is in the chat brief, with the clarifications in `chat-contract-changes.md`. In short:

- **Lists:** `GET list[?since]`, `POST open`
- **Groups:** `POST group`, `group-update`, `members`, `link`, `join`, `leave`
- **Messages:**
  - `GET msgs?c=&before=|after=&limit=`, `POST send`, `edit` (15 min), `delete`, `react`, `star`
  - `GET starred`, `POST read`, `delivered`, `GET info`, `POST live`, `live-stop`, `prefs`
- **Media:** `POST media`, `GET media/:id` (with Range, or signed `?e=&s=` links)
- **Other:** `GET search?q=`, `GET preview?url=` (answered by Pages; project links become a project card), `POST ticket`, `GET ws?t=`

Message ids are integers per chat. Times are epoch milliseconds.

Limits:
- Size caps after the phone compresses: photo 2 MB, video 16 MB and 60 s, voice note 5 min (6 MB), document 10 MB.
- Text 4,000 characters. Live location at most 8 h.
- 60 messages a minute and 120 uploads an hour per person.

System lines in project threads come from the Pages Functions (`lib/chat.js` → `chatSys`). `sys.type` is one of:
- `repair`: repair reported on a visit
- `notworking`: a visit found it not working
- `assigned`: a repair or a visit was assigned (the person is added to the thread)
- `step`: a repair step changed
- `fixed`
- `sms`: a community SMS or WhatsApp report (no phone number is shown)

Custom groups also get `created`, `added`, `removed`, `joined` and `left`.

Office › Team changes (block, remove, sign out of all phones, admin, home country) call `chatSync`. Their open chat
connections close straight away (WebSocket close code 4401), and the Team chats follow the team list.

## Tests

```
cd workers/chat
npm ci
npm test           # vitest + @cloudflare/vitest-pool-workers: real Durable Objects, SQLite, R2 and WebSockets
```

The Pages side is tested with the rest of the site (`node --test tests/*.test.mjs`, file `tests/chat.test.mjs`).

## Later: push notifications with Firebase (FCM)

Not built yet. Android checks `list?since` in the background, and has the WebSocket while open. Push would need:
1. A Firebase project, with the Android app (`org.rafikioutreach.pin`) registered and its `google-services.json` in the app.
2. A service-account key, stored as a worker **secret** (`FCM_SERVICE_ACCOUNT`).
3. A `POST /api/chat/push-token` endpoint, storing each phone's token in the Directory.
4. The Directory sending a data message from `post()` to members who are offline and haven't muted the chat (FCM HTTP v1 API).
