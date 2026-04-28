package com.twitch.backend.routes

import cats.effect.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import cats.effect.std.Queue
import io.circe.syntax.*
import com.twitch.core.*
import com.twitch.backend.{Database, TwitchApi, Validation, AppSettings}
import com.twitch.backend.auth.SessionManager

class ApiRoutes(
    clientId: String,
    sessionManager: SessionManager,
    twitchApi: TwitchApi,
    db: Database,
    notificationQueues: Ref[IO, Map[String, (String, Queue[IO, StreamNotification])]],
    settings: AppSettings
):

  private object SearchQueryParamMatcher extends QueryParamDecoderMatcher[String]("query")
  private object AfterQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("after")

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "config" =>
      Ok(AppConfig(clientId))
    case req @ GET -> Root / "user" =>
      sessionManager.getSession(req).flatMap {
        case Some(data) => Ok(data.user)
        case None       => NotFound("Not logged in")
      }
    case req @ GET -> Root / "followed" =>
      sessionManager.getSession(req).flatMap {
        case Some(data) =>
          db.getFollowed(data.user.id).flatMap(cats => Ok(FollowedCategoriesResponse(cats)))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "follow" =>
      req.as[FollowRequest].flatMap { followReq =>
        sessionManager.getSession(req).flatMap {
          case Some(data) =>
            db.follow(data.user.id, followReq.category) *> Ok("Followed")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "unfollow" / categoryId =>
      sessionManager.getSession(req).flatMap {
        case Some(data) =>
          db.unfollow(data.user.id, categoryId) *> Ok("Unfollowed")
        case None => Forbidden("Not logged in")
      }
    case req @ GET -> Root / "search" / "categories" :? SearchQueryParamMatcher(query) +& AfterQueryParamMatcher(after) =>
      sessionManager.getSession(req).flatMap {
        case Some(data) =>
          sessionManager.refreshTokenIfNeeded(data).flatMap { refreshed =>
            twitchApi.searchCategories(query, after, refreshed.accessToken, settings.searchPageSize).flatMap(Ok(_))
          }
        case None => Forbidden("Not logged in")
      }
    case req @ GET -> Root / "search" / "channels" :? SearchQueryParamMatcher(query) +& AfterQueryParamMatcher(after) =>
      sessionManager.getSession(req).flatMap {
        case Some(data) =>
          sessionManager.refreshTokenIfNeeded(data).flatMap { refreshed =>
            twitchApi.searchChannels(query, after, refreshed.accessToken, settings.searchPageSize).flatMap(Ok(_))
          }
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "logout" =>
      val sessionId = req.cookies.find(_.name == "session_id").map(_.content)
      for {
        _ <- sessionId.fold(IO.unit)(id => db.deleteSession(id))
        res <- Ok("Logged out").map(_.removeCookie("session_id"))
      } yield res
    case req @ GET -> Root / "tag-filters" =>
      sessionManager.getSession(req).flatMap {
        case Some(data) =>
          db.getTagFilters(data.user.id).flatMap(filters => Ok(TagFiltersResponse(filters)))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "tag-filters" / "add" =>
      req.as[AddTagFilterRequest].flatMap { body =>
        sessionManager.getSession(req).flatMap {
          case Some(data) =>
            (Validation.validateTag(body.tag), Validation.validateFilterType(body.filterType)) match
              case (Right(tag), Right(ft)) => db.addTagFilter(data.user.id, ft, tag) *> Ok("Filter added")
              case (Left(err), _) => BadRequest(err)
              case (_, Left(err)) => BadRequest(err)
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "tag-filters" / "remove" =>
      req.as[AddTagFilterRequest].flatMap { body =>
        sessionManager.getSession(req).flatMap {
          case Some(data) =>
            db.removeTagFilter(data.user.id, body.filterType, body.tag) *> Ok("Filter removed")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ GET -> Root / "ignored-streamers" =>
      sessionManager.getSession(req).flatMap {
        case Some(data) =>
          db.getIgnoredStreamers(data.user.id).flatMap(streamers => Ok(IgnoredStreamersResponse(streamers)))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "ignored-streamers" / "add" =>
      req.as[AddIgnoredStreamerRequest].flatMap { body =>
        sessionManager.getSession(req).flatMap {
          case Some(data) =>
            Validation.validateNonEmpty(body.streamerId, "streamerId") match
              case Right(_) => db.addIgnoredStreamer(data.user.id, body.streamerId, body.streamerLogin, body.streamerName) *> Ok("Streamer ignored")
              case Left(err) => BadRequest(err)
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "ignored-streamers" / "remove" =>
      req.as[RemoveIgnoredStreamerRequest].flatMap { body =>
        sessionManager.getSession(req).flatMap {
          case Some(data) =>
            db.removeIgnoredStreamer(data.user.id, body.streamerId) *> Ok("Streamer unignored")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "push" / "register" =>
      req.as[PushRegisterRequest].flatMap { body =>
        sessionManager.getSession(req).flatMap {
          case Some(data) =>
            Validation.validatePlatform(body.platform) match
              case Right(platform) => db.savePushSubscription(data.user.id, body.token, platform) *> Ok("Registered")
              case Left(err) => BadRequest(err)
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "push" / "unregister" =>
      req.as[PushUnregisterRequest].flatMap { body =>
        sessionManager.getSession(req).flatMap {
          case Some(_) =>
            db.deletePushSubscription(body.token) *> Ok("Unregistered")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ GET -> Root / "top-game-ids" =>
      sessionManager.getSession(req).flatMap {
        case Some(_) => db.getTopGameIds.flatMap(ids => Ok(TopGameIdsResponse(ids)))
        case None    => Forbidden("Not logged in")
      }
    case req @ GET -> Root / "notifications" / "stream" =>
      sessionManager.getSession(req).flatMap {
        case None => Forbidden("Not logged in")
        case Some(data) =>
          val sessionId = req.cookies.find(_.name == "session_id").map(_.content).getOrElse("unknown")
          Queue.unbounded[IO, StreamNotification].flatMap { queue =>
            notificationQueues.update(_ + (sessionId -> (data.user.id, queue))) *> {
              val eventStream: fs2.Stream[IO, ServerSentEvent] =
                fs2.Stream.fromQueueUnterminated(queue)
                  .map { n =>
                    ServerSentEvent(data = Some(n.asJson.noSpaces), eventType = Some("stream-live"))
                  }
                  .onFinalize(notificationQueues.update(_ - sessionId))
              Ok(eventStream)
            }
          }
      }
  }
