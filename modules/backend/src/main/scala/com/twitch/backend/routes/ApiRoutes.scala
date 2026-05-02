package com.twitch.backend.routes

import cats.effect.*
import cats.effect.std.Queue
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*

import com.twitch.backend.{AppSettings, TwitchApi, Validation}
import com.twitch.backend.auth.SessionManager
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

  private def tooMany(resource: String): IO[Response[IO]] =
    TooManyRequests(s"Too many $resource. Remove an existing item before adding another.")

  private def canOpenNotificationStream(userId: String, sessionId: String): IO[Boolean] =
    notificationQueues.get.map { queues =>
      val replacingExistingSession = queues.contains(sessionId)
      val userConnectionCount = queues.values.count(_._1 == userId)
      replacingExistingSession || (
        queues.size < settings.sseMaxConnections &&
          userConnectionCount < settings.sseMaxConnectionsPerUser
      )
    }

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "config" =>
      Ok(AppConfig(clientId))
    case req @ GET -> Root / "user" =>
      sessionManager.getSession(req).flatMap {
        case Some(data) => Ok(data.user)
        case None => NotFound("Not logged in")
      }
    case req @ GET -> Root / "followed" =>
      sessionManager.getSession(req).flatMap {
        case Some(data) =>
          followRepo.getFollowed(data.user.id).flatMap(cats => Ok(FollowedCategoriesResponse(cats)))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "follow" =>
      req.as[FollowRequest].flatMap { followReq =>
        sessionManager.getSession(req).flatMap {
          case Some(data) =>
            followRepo.isFollowing(data.user.id, followReq.category.id).flatMap {
              case true => followRepo.follow(data.user.id, followReq.category) *> Ok("Followed")
              case false =>
                followRepo.countFollowed(data.user.id).flatMap { count =>
                  if count >= settings.maxFollowedCategories then tooMany("followed categories")
                  else followRepo.follow(data.user.id, followReq.category) *> Ok("Followed")
                }
            }
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "unfollow" / categoryId =>
      sessionManager.getSession(req).flatMap {
        case Some(data) =>
          followRepo.unfollow(data.user.id, categoryId) *> Ok("Unfollowed")
        case None => Forbidden("Not logged in")
      }
    case req @ GET -> Root / "search" / "categories" :? SearchQueryParamMatcher(
          query,
        ) +& AfterQueryParamMatcher(after) =>
      sessionManager.getSession(req).flatMap {
        case Some(data) =>
          sessionManager.refreshTokenIfNeeded(data).flatMap { refreshed =>
            twitchApi
              .searchCategories(query, after, refreshed.accessToken, settings.searchPageSize)
              .flatMap(Ok(_))
          }
        case None => Forbidden("Not logged in")
      }
    case req @ GET -> Root / "search" / "channels" :? SearchQueryParamMatcher(
          query,
        ) +& AfterQueryParamMatcher(after) =>
      sessionManager.getSession(req).flatMap {
        case Some(data) =>
          sessionManager.refreshTokenIfNeeded(data).flatMap { refreshed =>
            twitchApi
              .searchChannels(query, after, refreshed.accessToken, settings.searchPageSize)
              .flatMap(Ok(_))
          }
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "logout" =>
      val sessionId = req.cookies.find(_.name == "session_id").map(_.content)
      for {
        _ <- sessionId.fold(IO.unit)(id => sessionRepo.deleteSession(id))
        res <- Ok("Logged out").map(_.removeCookie("session_id"))
      } yield res
    case req @ GET -> Root / "tag-filters" =>
      sessionManager.getSession(req).flatMap {
        case Some(data) =>
          tagFilterRepo
            .getTagFilters(data.user.id)
            .flatMap(filters => Ok(TagFiltersResponse(filters)))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "tag-filters" / "add" =>
      req.as[AddTagFilterRequest].flatMap { body =>
        sessionManager.getSession(req).flatMap {
          case Some(data) =>
            (
              Validation.validateTag(body.tag),
              Validation.validateFilterType(body.filterType),
            ) match {
              case (Right(tag), Right(ft)) =>
                tagFilterRepo.tagFilterExists(data.user.id, ft, tag).flatMap {
                  case true => tagFilterRepo.addTagFilter(data.user.id, ft, tag) *> Ok("Filter added")
                  case false =>
                    tagFilterRepo.countTagFilters(data.user.id).flatMap { count =>
                      if count >= settings.maxTagFilters then tooMany("tag filters")
                      else tagFilterRepo.addTagFilter(data.user.id, ft, tag) *> Ok("Filter added")
                    }
                }
              case (Left(err), _) => BadRequest(err)
              case (_, Left(err)) => BadRequest(err)
            }
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "tag-filters" / "remove" =>
      req.as[AddTagFilterRequest].flatMap { body =>
        sessionManager.getSession(req).flatMap {
          case Some(data) =>
            tagFilterRepo.removeTagFilter(data.user.id, body.filterType, body.tag) *> Ok(
              "Filter removed",
            )
          case None => Forbidden("Not logged in")
        }
      }
    case req @ GET -> Root / "ignored-streamers" =>
      sessionManager.getSession(req).flatMap {
        case Some(data) =>
          ignoredStreamerRepo
            .getIgnoredStreamers(data.user.id)
            .flatMap(streamers => Ok(IgnoredStreamersResponse(streamers)))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "ignored-streamers" / "add" =>
      req.as[AddIgnoredStreamerRequest].flatMap { body =>
        sessionManager.getSession(req).flatMap {
          case Some(data) =>
            Validation.validateNonEmpty(body.streamerId, "streamerId") match {
              case Right(streamerId) =>
                ignoredStreamerRepo.ignoredStreamerExists(data.user.id, streamerId).flatMap {
                  case true =>
                    ignoredStreamerRepo.addIgnoredStreamer(
                      data.user.id,
                      streamerId,
                      body.streamerLogin,
                      body.streamerName,
                    ) *> Ok("Streamer ignored")
                  case false =>
                    ignoredStreamerRepo.countIgnoredStreamers(data.user.id).flatMap { count =>
                      if count >= settings.maxIgnoredStreamers then tooMany("ignored streamers")
                      else
                        ignoredStreamerRepo.addIgnoredStreamer(
                          data.user.id,
                          streamerId,
                          body.streamerLogin,
                          body.streamerName,
                        ) *> Ok("Streamer ignored")
                    }
                }
              case Left(err) => BadRequest(err)
            }
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "ignored-streamers" / "remove" =>
      req.as[RemoveIgnoredStreamerRequest].flatMap { body =>
        sessionManager.getSession(req).flatMap {
          case Some(data) =>
            ignoredStreamerRepo
              .removeIgnoredStreamer(data.user.id, body.streamerId) *> Ok("Streamer unignored")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "push" / "register" =>
      req.as[PushRegisterRequest].flatMap { body =>
        sessionManager.getSession(req).flatMap {
          case Some(data) =>
            (
              Validation.validateNonEmpty(body.token, "token"),
              Validation.validatePlatform(body.platform),
            ) match {
              case (Right(token), Right(platform)) =>
                pushRepo.pushSubscriptionExists(data.user.id, token).flatMap {
                  case true =>
                    pushRepo.savePushSubscription(data.user.id, token, platform) *> Ok("Registered")
                  case false =>
                    pushRepo.countPushSubscriptions(data.user.id).flatMap { count =>
                      if count >= settings.maxPushSubscriptions then tooMany("push subscriptions")
                      else pushRepo.savePushSubscription(data.user.id, token, platform) *> Ok("Registered")
                    }
                }
              case (Left(err), _) => BadRequest(err)
              case (_, Left(err)) => BadRequest(err)
            }
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "push" / "unregister" =>
      req.as[PushUnregisterRequest].flatMap { body =>
        sessionManager.getSession(req).flatMap {
          case Some(data) =>
            Validation.validateNonEmpty(body.token, "token") match {
              case Right(token) =>
                pushRepo.deletePushSubscription(data.user.id, token) *> Ok("Unregistered")
              case Left(err) => BadRequest(err)
            }
          case None => Forbidden("Not logged in")
        }
      }
    case req @ GET -> Root / "top-game-ids" =>
      sessionManager.getSession(req).flatMap {
        case Some(_) => topGamesRepo.getTopGameIds.flatMap(ids => Ok(TopGameIdsResponse(ids)))
        case None => Forbidden("Not logged in")
      }
    case req @ GET -> Root / "notifications" / "stream" =>
      sessionManager.getSession(req).flatMap {
        case None => Forbidden("Not logged in")
        case Some(data) =>
          val sessionId =
            req.cookies.find(_.name == "session_id").map(_.content).getOrElse("unknown")
          canOpenNotificationStream(data.user.id, sessionId).flatMap {
            case false => tooMany("notification streams")
            case true =>
              Queue.bounded[IO, StreamNotification](settings.sseQueueCapacity).flatMap { queue =>
                notificationQueues.update(_ + (sessionId -> (data.user.id, queue))) *> {
                  val eventStream: fs2.Stream[IO, ServerSentEvent] =
                    fs2
                      .Stream
                      .fromQueueUnterminated(queue)
                      .map { n =>
                        ServerSentEvent(
                          data = Some(n.asJson.noSpaces),
                          eventType = Some("stream-live"),
                        )
                      }
                      .onFinalize(
                        notificationQueues.update { queues =>
                          queues.get(sessionId) match {
                            case Some((_, currentQueue)) if currentQueue eq queue =>
                              queues - sessionId
                            case _ => queues
                          }
                        },
                      )
                  Ok(eventStream)
                }
              }
          }
      }
  }

}
