## Twitch Stream Notifier

A web app that sends you browser notifications when Twitch streams go live in categories you follow. Built with Scala 3, Scala.js, and Http4s.

### How It Works

1. Log in with your Twitch account.
2. Search for and follow game/category pages you're interested in.
3. Leave the tab open — you'll get a browser notification whenever a new stream goes live in any of your followed categories.

The backend polls the Twitch API every 60 seconds and pushes updates to connected clients via Server-Sent Events (SSE). Followed categories and sessions are persisted in a database, so they survive server restarts.

### Project Structure

- `modules/core` — Shared models (cross-compiled JVM/JS)
- `modules/frontend` — Scala.js frontend using Calico and Tailwind CSS (via Scalawind)
- `modules/backend` — Http4s server (JVM)

### Running Locally

**Prerequisites** (must be on your PATH):
- JDK 11+
- [sbt](https://www.scala-sbt.org/download)
- [Node.js](https://nodejs.org/) 20+ (provides `npm` and `npx`, used by the build for Tailwind CSS and Scalawind generation; `npm install` runs automatically on first build)

1. Register an app on the [Twitch Developer Console](https://dev.twitch.tv/console) with redirect URL `http://localhost:8080/auth/callback`.

2. Start the server using one of the options below, then open http://localhost:8080.

#### Option A: H2 (simplest, no setup)

No database to install — the app uses an embedded H2 file database automatically:

```sh
TWITCH_CLIENT_ID=your_client_id \
TWITCH_CLIENT_SECRET=your_client_secret \
sbt dev
```

Data is stored in `./twitch_app_db.mv.db` and persists across restarts.

#### Option B: Local PostgreSQL

To develop against the same database used in production:

```sh
# Start a Postgres container
docker run -d --name pg-local \
  -e POSTGRES_PASSWORD=test \
  -e POSTGRES_DB=twitch_app \
  -p 5432:5432 postgres:16

# Start the app pointing at it
TWITCH_CLIENT_ID=your_client_id \
TWITCH_CLIENT_SECRET=your_client_secret \
DATABASE_URL=jdbc:postgresql://localhost:5432/twitch_app \
DATABASE_USER=postgres \
DATABASE_PASS=test \
sbt dev
```

Tables are created automatically on first startup. To stop and remove the container later: `docker stop pg-local && docker rm pg-local`.

### Environment Variables

| Variable | Required | Default | Purpose |
|----------|----------|---------|---------|
| `TWITCH_CLIENT_ID` | Yes | — | Twitch OAuth app ID |
| `TWITCH_CLIENT_SECRET` | Yes | — | Twitch OAuth app secret |
| `DATABASE_URL` | No | H2 file DB | JDBC connection string (e.g. `jdbc:postgresql://host:5432/db`) |
| `DATABASE_USER` | No | — | DB username (if not embedded in URL) |
| `DATABASE_PASS` | No | — | DB password (if not embedded in URL) |
| `BASE_URL` | No | `http://localhost:8080` | Public URL (sets redirect URI and cookie security) |
| `PORT` | No | `8080` | Server listen port |
| `STATIC_DIR` | No | `./modules/frontend` | Path to static assets directory |
| `SENDGRID_API_KEY` | No | — | SendGrid API key (enables welcome emails; see [DEPLOY_PLAN.md](DEPLOY_PLAN.md)) |

### Running with Docker

```sh
docker build -t twitch-notifier .
docker run -p 8080:8080 \
  -e TWITCH_CLIENT_ID=your_client_id \
  -e TWITCH_CLIENT_SECRET=your_client_secret \
  -e DATABASE_URL=jdbc:postgresql://host:5432/twitch_app \
  twitch-notifier
```

### Tech Stack

- **Scala 3** with Cats-Effect for concurrency
- **Http4s** for the backend HTTP server and API
- **Calico** (Scala.js) for the reactive frontend
- **Scalawind** + **Tailwind CSS** for styling
- **Doobie** + **PostgreSQL** for production persistence (H2 for local dev)
- **Circe** for JSON serialization
