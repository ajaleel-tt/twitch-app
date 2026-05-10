package com.twitch.backend.routes

import cats.data.{Kleisli, OptionT}
import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.semigroupk.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*
import org.http4s.server.AuthMiddleware

import com.twitch.backend.{AppSettings, TwitchApi, Validation}
import com.twitch.backend.auth.{SessionData, SessionManager}
import com.twitch.backend.db.{
  FollowRepository,
  IgnoredStreamerRepository,
  PushSubscriptionRepository,
  SessionRepository,
  TagFilterRepository,
  TopGamesRepository,
}
import com.twitch.core.{
  AddIgnoredStreamerRequest,
  AddTagFilterRequest,
  AppConfig,
  FollowRequest,
  FollowedCategoriesResponse,
  IgnoredStreamersResponse,
  PushRegisterRequest,
  PushUnregisterRequest,
  RemoveIgnoredStreamerRequest,
  StreamNotification,
  TagFiltersResponse,
  TopGameIdsResponse,
}

class ApiRoutes(
  clientId: String,
  followRepo: FollowRepository,
  ignoredStreamerRepo: IgnoredStreamerRepository,
  notificationQueues: Ref[IO, Map[String, (String, Queue[IO, StreamNotification])]],
  pushRepo: PushSubscriptionRepository,
  sessionManager: SessionManager,
  sessionRepo: SessionRepository,
  settings: AppSettings,
  tagFilterRepo: TagFilterRepository,
  topGamesRepo: TopGamesRepository,
  twitchApi: TwitchApi,
) {

  private object SearchQueryParamMatcher extends QueryParamDecoderMatcher[String]("query")
  private object AfterQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("after")

  private val authMiddleware: AuthMiddleware[IO, SessionData] = {
    val authUser: Kleisli[IO, Request[IO], Either[String, SessionData]] =
      Kleisli(req => sessionManager.getSession(req).map(_.toRight("Not logged in")))
    val onFailure: AuthedRoutes[String, IO] =
      Kleisli(authReq => OptionT.liftF(Forbidden(authReq.context)))
    AuthMiddleware(authUser, onFailure)
  }

  private val publicRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "config" =>
      Ok(AppConfig(clientId))

    // /user is the frontend's "am I logged in?" probe — returns 404, not 403,
    // so the client can distinguish "no session" from "auth required".
    case req @ GET -> Root / "user" =>
      sessionManager.getSession(req).flatMap {
        case Some(data) => Ok(data.user)
        case None => NotFound("Not logged in")
      }

    case req @ POST -> Root / "logout" =>
      val sessionId = req.cookies.find(_.name == "session_id").map(_.content)
      for {
        _ <- sessionId.fold(IO.unit)(id => sessionRepo.deleteSession(id))
        res <- Ok("Logged out").map(_.removeCookie("session_id"))
      } yield res
  }

  private val authedRoutes: AuthedRoutes[SessionData, IO] = AuthedRoutes.of[SessionData, IO] {
    case GET -> Root / "followed" as data =>
      followRepo.getFollowed(data.user.id).flatMap(cats => Ok(FollowedCategoriesResponse(cats)))

    case authReq @ POST -> Root / "follow" as data =>
      authReq.req.as[FollowRequest].flatMap { followReq =>
        followRepo.follow(data.user.id, followReq.category) *> Ok("Followed")
      }

    case POST -> Root / "unfollow" / categoryId as data =>
      followRepo.unfollow(data.user.id, categoryId) *> Ok("Unfollowed")

    case GET -> Root / "search" / "categories" :? SearchQueryParamMatcher(query)
        +& AfterQueryParamMatcher(after) as data =>
      sessionManager.refreshTokenIfNeeded(data).flatMap { refreshed =>
        twitchApi
          .searchCategories(query, after, refreshed.accessToken, settings.searchPageSize)
          .flatMap(Ok(_))
      }

    case GET -> Root / "search" / "channels" :? SearchQueryParamMatcher(query)
        +& AfterQueryParamMatcher(after) as data =>
      sessionManager.refreshTokenIfNeeded(data).flatMap { refreshed =>
        twitchApi
          .searchChannels(query, after, refreshed.accessToken, settings.searchPageSize)
          .flatMap(Ok(_))
      }

    case GET -> Root / "tag-filters" as data =>
      tagFilterRepo
        .getTagFilters(data.user.id)
        .flatMap(filters => Ok(TagFiltersResponse(filters)))

    case authReq @ POST -> Root / "tag-filters" / "add" as data =>
      authReq.req.as[AddTagFilterRequest].flatMap { body =>
        (
          Validation.validateTag(body.tag),
          Validation.validateFilterType(body.filterType),
        ) match {
          case (Right(tag), Right(ft)) =>
            tagFilterRepo.addTagFilter(data.user.id, ft, tag) *> Ok("Filter added")
          case (Left(err), _) => BadRequest(err)
          case (_, Left(err)) => BadRequest(err)
        }
      }

    case authReq @ POST -> Root / "tag-filters" / "remove" as data =>
      authReq.req.as[AddTagFilterRequest].flatMap { body =>
        tagFilterRepo.removeTagFilter(data.user.id, body.filterType, body.tag) *> Ok(
          "Filter removed",
        )
      }

    case GET -> Root / "ignored-streamers" as data =>
      ignoredStreamerRepo
        .getIgnoredStreamers(data.user.id)
        .flatMap(streamers => Ok(IgnoredStreamersResponse(streamers)))

    case authReq @ POST -> Root / "ignored-streamers" / "add" as data =>
      authReq.req.as[AddIgnoredStreamerRequest].flatMap { body =>
        Validation.validateNonEmpty(body.streamerId, "streamerId") match {
          case Right(_) =>
            ignoredStreamerRepo.addIgnoredStreamer(
              data.user.id,
              body.streamerId,
              body.streamerLogin,
              body.streamerName,
            ) *> Ok("Streamer ignored")
          case Left(err) => BadRequest(err)
        }
      }

    case authReq @ POST -> Root / "ignored-streamers" / "remove" as data =>
      authReq.req.as[RemoveIgnoredStreamerRequest].flatMap { body =>
        ignoredStreamerRepo
          .removeIgnoredStreamer(data.user.id, body.streamerId) *> Ok("Streamer unignored")
      }

    case authReq @ POST -> Root / "push" / "register" as data =>
      authReq.req.as[PushRegisterRequest].flatMap { body =>
        Validation.validatePlatform(body.platform) match {
          case Right(platform) =>
            pushRepo.savePushSubscription(data.user.id, body.token, platform) *> Ok("Registered")
          case Left(err) => BadRequest(err)
        }
      }

    case authReq @ POST -> Root / "push" / "unregister" as _ =>
      authReq.req.as[PushUnregisterRequest].flatMap { body =>
        pushRepo.deletePushSubscription(body.token) *> Ok("Unregistered")
      }

    case GET -> Root / "top-game-ids" as _ =>
      topGamesRepo.getTopGameIds.flatMap(ids => Ok(TopGameIdsResponse(ids)))

    case authReq @ GET -> Root / "notifications" / "stream" as data =>
      val sessionId =
        authReq.req.cookies.find(_.name == "session_id").map(_.content).getOrElse("unknown")
      Queue.unbounded[IO, StreamNotification].flatMap { queue =>
        notificationQueues.update(_ + (sessionId -> (data.user.id, queue))) *> {
          val eventStream: fs2.Stream[IO, ServerSentEvent] =
            fs2
              .Stream
              .fromQueueUnterminated(queue)
              .map { n =>
                ServerSentEvent(data = Some(n.asJson.noSpaces), eventType = Some("stream-live"))
              }
              .onFinalize(notificationQueues.update(_ - sessionId))
          Ok(eventStream)
        }
      }
  }

  def routes: HttpRoutes[IO] = publicRoutes <+> authMiddleware(authedRoutes)

}
