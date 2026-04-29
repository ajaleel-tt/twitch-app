package com.twitch.backend

import cats.effect.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.client.Client
import org.http4s.server.Router
import org.http4s.server.middleware.CORS
import org.http4s.server.staticcontent.*
import cats.effect.std.Queue
import com.twitch.core.StreamNotification
import doobie.Transactor

case class App(
  corsApp: HttpApp[IO],
  poller: StreamPoller,
  topGamesPoller: TopGamesPoller,
)

object AppWiring:

  def build(
    config: ServerConfig,
    settings: AppSettings,
    xa: Transactor[IO],
    client: Client[IO],
  ): IO[App] =
    val followRepo          = new db.FollowRepository(xa, config.dialect)
    val tagFilterRepo       = new db.TagFilterRepository(xa, config.dialect)
    val ignoredStreamerRepo = new db.IgnoredStreamerRepository(xa, config.dialect)
    val userRepo            = new db.UserRepository(xa)
    val sessionRepo         = new db.SessionRepository(xa)
    val pushRepo            = new db.PushSubscriptionRepository(xa, config.dialect)
    val topGamesRepo        = new db.TopGamesRepository(xa)

    val emailService = sys
      .env
      .get("SENDGRID_API_KEY")
      .map(key => new EmailService(client, key, settings.emailFrom, settings.emailFromName))

    val pushServiceIO: IO[Option[PushNotificationService]] =
      val keyIO = sys.env.get("FCM_SERVICE_ACCOUNT_JSON") match
        case Some(json) => ServiceAccountKey.fromJson(json).map(Some(_))
        case None       =>
          sys.env.get("FCM_SERVICE_ACCOUNT_KEY") match
            case Some(keyPath) => ServiceAccountKey.fromFile(keyPath).map(Some(_))
            case None          => IO.none
      keyIO
        .flatMap {
          case Some(key) =>
            for
              tokenCache <- IO.ref(Option.empty[(String, java.time.Instant)])
              tokenMutex <- cats.effect.std.Mutex[IO]
              _          <- IO.println("Push notifications enabled")
            yield Some(
              new PushNotificationService(
                client,
                key.projectId,
                key,
                settings.pushParallelSends,
                pushRepo,
                tokenCache,
                tokenMutex,
              ),
            )
          case None =>
            IO.println(
              "Push notifications disabled (set FCM_SERVICE_ACCOUNT_JSON or FCM_SERVICE_ACCOUNT_KEY)",
            ).as(None)
        }
        .handleErrorWith { err =>
          IO.println(s"Warning: Failed to load FCM service account key: ${err.getMessage}").as(None)
        }

    for
      _                  <- db.Schema.initDb(xa, config.dialect)
      pendingOAuthStates <- IO.ref(Set.empty[String])
      notificationQueues <- IO.ref(Map.empty[String, (String, Queue[IO, StreamNotification])])
      pushService        <- pushServiceIO
      twitchApi      = new TwitchApiClient(config.clientId, config.clientSecret, client)
      sessionManager = new auth.SessionManager(sessionRepo, twitchApi)
      authRoutes     = new routes.AuthRoutes(
        config.clientId,
        config.redirectUri,
        twitchApi,
        pendingOAuthStates,
        userRepo,
        sessionRepo,
        emailService,
      )
      apiRoutes = new routes.ApiRoutes(
        config.clientId,
        sessionManager,
        twitchApi,
        followRepo,
        tagFilterRepo,
        ignoredStreamerRepo,
        sessionRepo,
        pushRepo,
        topGamesRepo,
        notificationQueues,
        settings,
      )
      frontendService = fileService[IO](FileService.Config(config.staticDir))
      httpApp         = Router(
        "/api" -> apiRoutes.routes,
        "/"    -> authRoutes.routes,
        "/"    -> HttpRoutes.of[IO] {
          case req @ GET -> Root =>
            StaticFile
              .fromPath(fs2.io.file.Path(s"${config.staticDir}/index.html"), Some(req))
              .getOrElseF(NotFound())
        },
        "/" -> frontendService,
      ).orNotFound
      corsApp = CORS.policy.withAllowOriginAll(httpApp)
      poller <- StreamPoller.make(
        config.clientId,
        config.clientSecret,
        client,
        followRepo,
        tagFilterRepo,
        ignoredStreamerRepo,
        pushRepo,
        notificationQueues,
        settings,
        pushService,
      )
      topGamesPoller <- TopGamesPoller.make(
        config.clientId,
        config.clientSecret,
        client,
        topGamesRepo,
        settings,
      )
    yield App(corsApp, poller, topGamesPoller)
