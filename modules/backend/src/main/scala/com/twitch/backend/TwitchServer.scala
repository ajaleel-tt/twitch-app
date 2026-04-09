package com.twitch.backend

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.*
import org.http4s.server.Router
import org.http4s.server.middleware.CORS
import org.http4s.server.staticcontent.*
import doobie.h2.H2Transactor
import doobie.hikari.HikariTransactor
import com.zaxxer.hikari.HikariConfig
import cats.effect.std.Queue
import com.twitch.core.StreamNotification

object TwitchServer extends IOApp.Simple:

  private val clientId = sys.env.getOrElse("TWITCH_CLIENT_ID", {
    System.err.println("ERROR: TWITCH_CLIENT_ID environment variable is not set")
    sys.exit(1)
  })
  private val clientSecret = sys.env.getOrElse("TWITCH_CLIENT_SECRET", {
    System.err.println("ERROR: TWITCH_CLIENT_SECRET environment variable is not set")
    sys.exit(1)
  })
  private val baseUrl = sys.env.getOrElse("BASE_URL", "http://localhost:8080")
  private val redirectUri = s"$baseUrl/auth/callback"

  private val settings = AppSettings.load

  def run: IO[Unit] =
    val dbUrl = sys.env.getOrElse("DATABASE_URL",
      "jdbc:h2:./twitch_app_db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
    val isPostgres = dbUrl.startsWith("jdbc:postgresql")
    val dialect = if isPostgres then SqlDialect.Postgres else SqlDialect.H2

    val transactorResource: Resource[IO, doobie.Transactor[IO]] =
      if isPostgres then
        val hikariConfig = new HikariConfig()
        hikariConfig.setDriverClassName("org.postgresql.Driver")
        hikariConfig.setJdbcUrl(dbUrl)
        // If DATABASE_USER/DATABASE_PASS are set, use them explicitly.
        // Otherwise let HikariCP parse credentials from the JDBC URL.
        sys.env.get("DATABASE_USER").foreach(hikariConfig.setUsername)
        sys.env.get("DATABASE_PASS").foreach(hikariConfig.setPassword)
        HikariTransactor.fromHikariConfig[IO](hikariConfig)
      else
        for {
          ec <- Resource.eval(IO.executionContext)
          xa <- H2Transactor.newH2Transactor[IO](dbUrl, "sa", "", ec)
        } yield xa

    transactorResource.use { xa =>
      val db = new Database(xa, dialect)
      for {
        _ <- db.initDb
        pendingOAuthStates <- IO.ref(Set.empty[String])
        notificationQueues <- IO.ref(Map.empty[String, (String, Queue[IO, StreamNotification])])
        _ <- EmberClientBuilder.default[IO].build.use { client =>
          val host = host"0.0.0.0"
          val port = Port.fromInt(sys.env.getOrElse("PORT", "8080").toInt).getOrElse(port"8080")

          val staticDir = sys.env.getOrElse("STATIC_DIR", "./modules/frontend")

          val routes = new Routes(clientId, clientSecret, redirectUri, client, pendingOAuthStates, db, notificationQueues, settings)
          val frontendService = fileService[IO](FileService.Config(staticDir))

          val httpApp = Router(
            "/api" -> routes.apiRoutes,
            "/" -> routes.authRoutes,
            "/" -> HttpRoutes.of[IO] {
              case req @ GET -> Root =>
                StaticFile.fromPath(fs2.io.file.Path(s"$staticDir/index.html"), Some(req)).getOrElseF(NotFound())
            },
            "/" -> frontendService
          ).orNotFound

          val corsApp = CORS.policy.withAllowOriginAll(httpApp)

          for
            poller <- StreamPoller.make(clientId, clientSecret, client, db, notificationQueues, settings)
            _ <- (
              poller.start.void,
              IO.println(s"Server started at $baseUrl") *>
                EmberServerBuilder
                  .default[IO]
                  .withHost(host)
                  .withPort(port)
                  .withHttpApp(corsApp)
                  .build
                  .useForever
            ).parTupled
          yield ()
        }
      } yield ()
    }
