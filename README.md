## Twitch Stream Notifier

A web app that sends you browser notifications when Twitch streams go live in categories you follow. Built with Scala 3, Scala.js, and Http4s.

### How It Works

1. Log in with your Twitch account.
2. Search for and follow game/category pages you're interested in.
3. Leave the tab open — you'll get a browser notification whenever a new stream goes live in any of your followed categories.

The backend polls the Twitch API every 60 seconds and pushes updates to connected clients via Server-Sent Events (SSE). Followed categories are persisted in an embedded H2 database, so they survive server restarts.

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

2. Build the frontend and start the server:
   ```sh
   TWITCH_CLIENT_ID=your_client_id \
   TWITCH_CLIENT_SECRET=your_client_secret \
   sbt "frontend/fastLinkJS" "backend/run"
   ```

3. Open http://localhost:8080.

### Tech Stack

- **Scala 3** with Cats-Effect for concurrency
- **Http4s** for the backend HTTP server and API
- **Calico** (Scala.js) for the reactive frontend
- **Scalawind** + **Tailwind CSS** for styling
- **Doobie** + **H2** for persistence
- **Circe** for JSON serialization
