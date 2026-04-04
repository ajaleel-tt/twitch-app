# Plan: Deploy Twitch Stream Notifier as a Production App

## Context

The Twitch Stream Notifier is a working local app (Scala 3 / Http4s / Scala.js / H2) that notifies users when streams go live in their followed categories. The goal is to make it a real, publicly accessible multi-user app. Today, several things are hardcoded for localhost and the stack uses components (H2, in-memory sessions) that don't survive restarts or scale to multiple users in production.

---

## Step 1: Replace H2 with PostgreSQL

**Why:** H2 is an embedded file-based database. It doesn't support concurrent access from multiple processes, has no remote connectivity, and isn't suitable for production hosting. The app already runs H2 in `MODE=PostgreSQL`, so the SQL is largely compatible.

**Files to modify:**
- `build.sbt` — Replace `h2` and `doobie-h2` dependencies with `doobie-postgres` and `org.postgresql:postgresql` JDBC driver
- `modules/backend/src/main/scala/com/twitch/backend/TwitchServer.scala` (line 30) — Change JDBC URL from `jdbc:h2:./twitch_app_db;MODE=PostgreSQL;...` to read a `DATABASE_URL` env var (e.g. `jdbc:postgresql://host:5432/twitch_app`)
- `modules/backend/src/main/scala/com/twitch/backend/Database.scala` (line 14) — Change `MERGE INTO` to standard PostgreSQL `INSERT ... ON CONFLICT ... DO UPDATE` (the `follow` method, lines 28-33)
- `modules/backend/src/main/scala/com/twitch/backend/Database.scala` (lines 11-19) — Verify `CREATE TABLE IF NOT EXISTS` syntax works as-is with Postgres (it should)

**New env vars:** `DATABASE_URL` (full JDBC connection string, includes credentials)

---

## Step 2: Externalize All Configuration

**Why:** Host, port, redirect URI, and DB path are all hardcoded. A deployed app needs these to come from the environment so the same image works in dev, staging, and production.

**Files to modify:**
- `modules/backend/src/main/scala/com/twitch/backend/TwitchServer.scala`:
  - Line 27: `redirectUri` — read from `BASE_URL` env var, construct as `${BASE_URL}/auth/callback` (default to `http://localhost:8080` for local dev)
  - Line 30: DB URL — already handled in Step 1
  - Lines 43-44: host/port — read `PORT` env var (many hosting platforms set this automatically), default to `8080`
- `modules/frontend/src/main/scala/com/twitch/frontend/components/LoginSection.scala` (lines 12-35):
  - The frontend hardcodes `http://localhost:8080/auth/callback` as the OAuth redirect URI. Instead, it should construct this relative to `window.location.origin`, or fetch the redirect URI from the `/api/config` endpoint (which already exists and returns the client ID).

**Env var summary after this step:**
| Variable | Required | Example | Purpose |
|----------|----------|---------|---------|
| `TWITCH_CLIENT_ID` | Yes | `vk4vfg8...` | Twitch OAuth app ID |
| `TWITCH_CLIENT_SECRET` | Yes | `ztiknten...` | Twitch OAuth app secret |
| `DATABASE_URL` | Yes | `jdbc:postgresql://localhost:5432/twitch_app` | Postgres connection |
| `BASE_URL` | No | `https://myapp.fly.dev` | Public URL (default: `http://localhost:8080`) |
| `PORT` | No | `8080` | Server listen port (default: `8080`) |

---

## Step 3: Persist Sessions (or Make Them Stateless)

**Why:** Sessions are stored in an in-memory `Ref[IO, Map[String, SessionData]]` (TwitchServer.scala line 40). A server restart loses all sessions, logging everyone out. In production, this is unacceptable.

**Two options (recommend Option A for simplicity):**

### Option A: Store sessions in PostgreSQL
- Add a `sessions` table (`session_id VARCHAR PRIMARY KEY, user_id VARCHAR, access_token VARCHAR, created_at TIMESTAMP`)
- Add `createSession`, `getSession`, `deleteSession` methods to `Database.scala`
- Modify `Routes.scala` to use DB-backed sessions instead of the in-memory Ref
- Add a TTL / expiry check (e.g. 30 days)

### Option B: Stateless JWT sessions
- Encode session data (user ID, display name) in a signed JWT stored in the cookie
- No server-side session storage needed
- Downside: can't revoke individual sessions, tokens are larger

---

## Step 4: Dockerize the App

**Why:** A Dockerfile lets you deploy to any container hosting platform (Fly.io, Railway, Render, AWS ECS, etc.) with a single `docker build && docker push`.

**New files to create:**
- `Dockerfile` — Multi-stage build:
  1. **Build stage:** Use an sbt/JDK image with Node.js. Run `sbt frontend/fullLinkJS backend/assembly` (or `stage`) to produce a fat JAR and compiled frontend assets.
  2. **Run stage:** Use a slim JRE image. Copy the JAR and frontend assets. Expose `$PORT`.
- `.dockerignore` — Exclude `.bsp/`, `target/`, `node_modules/`, `.idea/`, `*.mv.db`, `*.trace.db`

**Files to modify:**
- `build.sbt` — Add `sbt-assembly` or `sbt-native-packager` plugin for building a deployable artifact. Ensure `fullLinkJS` (optimized JS) is used for production builds instead of `fastLinkJS`.

**Build command:** `docker build -t twitch-notifier .`
**Run command:** `docker run -p 8080:8080 --env-file .env twitch-notifier`

---

## Step 5: Deploy

**Why:** This is the actual "make it real" step.

**Recommended platform: Fly.io** (simple, supports Docker, has managed Postgres, free tier)

**Steps:**
1. Install `flyctl` CLI
2. `fly launch` — creates app, generates `fly.toml`
3. `fly postgres create` — provision a managed Postgres instance
4. `fly postgres attach` — sets `DATABASE_URL` automatically
5. Set secrets: `fly secrets set TWITCH_CLIENT_ID=... TWITCH_CLIENT_SECRET=... BASE_URL=https://<app-name>.fly.dev`
6. Update Twitch Developer Console: add `https://<app-name>.fly.dev/auth/callback` as an allowed redirect URI
7. `fly deploy` — builds Docker image, deploys

**Alternative platforms:** Railway, Render, DigitalOcean App Platform, or a plain VPS with Docker.

---

## Step 6 (Future): Web Push Notifications

**Why:** Currently, users must keep the browser tab open to receive SSE notifications. Web Push (via the Push API + service worker) delivers notifications even when the tab is closed. This is a significant feature addition and can be done after the initial deployment.

**High-level approach:**
- Add a service worker to the frontend that subscribes to push notifications
- Store push subscriptions (endpoint + keys) in a new DB table
- When the poller detects a new stream, send push notifications via a push service (e.g. web-push library) in addition to SSE
- This requires generating VAPID keys and serving the public key to the frontend

**This step is optional for initial launch** — SSE notifications work fine as long as users keep the tab open.

---

## Verification

After each step, verify:
1. **Step 1:** Run the app locally against a local Postgres instance. Confirm login, follow/unfollow, and data persistence across restarts.
2. **Step 2:** Start the server with `BASE_URL=http://localhost:8080 PORT=8080` env vars. Confirm OAuth flow works. Change port to 9090, confirm it binds correctly.
3. **Step 3:** Log in, restart the server, confirm you're still logged in.
4. **Step 4:** `docker build` succeeds. `docker run` with env vars starts the app and serves the frontend.
5. **Step 5:** Visit `https://<app-name>.fly.dev`, log in with Twitch, follow categories, receive notifications.
