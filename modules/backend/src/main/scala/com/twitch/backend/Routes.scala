package com.twitch.backend

import cats.effect.*
import org.http4s.*
import org.http4s.Method.*
import org.http4s.dsl.io.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.client.Client
import org.http4s.implicits.*
import org.http4s.headers.{Authorization, Location}
import org.typelevel.ci.*
import java.util.UUID
import cats.effect.std.Queue
import io.circe.syntax.*
import com.twitch.core.*

case class SessionData(
    user: TwitchUser,
    accessToken: String
)

class Routes(
    clientId: String,
    clientSecret: String,
    redirectUri: String,
    client: Client[IO],
    userSession: Ref[IO, Map[String, SessionData]],
    pendingOAuthStates: Ref[IO, Set[String]],
    db: Database,
    notificationQueues: Ref[IO, Map[String, (String, Queue[IO, StreamNotification])]]
):

  private def getSession(req: Request[IO]): IO[Option[SessionData]] = {
    val sessionId = req.cookies.find(_.name == "session_id").map(_.content)
    sessionId.fold(IO.pure(None: Option[SessionData]))(id => userSession.get.map(_.get(id)))
  }

  private object CodeQueryParamMatcher extends QueryParamDecoderMatcher[String]("code")
  private object StateQueryParamMatcher extends QueryParamDecoderMatcher[String]("state")
  private object SearchQueryParamMatcher extends QueryParamDecoderMatcher[String]("query")
  private object AfterQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("after")

  def authRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "auth" / "login" =>
      val state = UUID.randomUUID().toString
      val authorizeUri = s"https://id.twitch.tv/oauth2/authorize?client_id=$clientId&redirect_uri=$redirectUri&response_type=code&scope=user:read:email&state=$state"
      pendingOAuthStates.update(_ + state) *>
        Found(Location(Uri.unsafeFromString(authorizeUri)))

    case GET -> Root / "auth" / "callback" :? CodeQueryParamMatcher(code) +& StateQueryParamMatcher(state) =>
      val flow = for {
        pending <- pendingOAuthStates.get
        _ <- IO.raiseUnless(pending.contains(state))(new RuntimeException("Invalid OAuth state parameter"))
        _ <- pendingOAuthStates.update(_ - state)
        _ <- IO.println("Received auth callback")
        req = Request[IO](method = Method.POST, uri = uri"https://id.twitch.tv/oauth2/token").withEntity(
          UrlForm(
            "client_id" -> clientId,
            "client_secret" -> clientSecret,
            "code" -> code,
            "grant_type" -> "authorization_code",
            "redirect_uri" -> redirectUri
          )
        )
        
        tokenResponse <- client.run(req).use { resp =>
          if (resp.status.isSuccess) {
            resp.as[TwitchTokenResponse]
          } else {
            resp.bodyText.compile.string.flatMap { errorBody =>
              IO.raiseError(new RuntimeException(s"unexpected HTTP status: ${resp.status} for request POST https://id.twitch.tv/oauth2/token. Response body: $errorBody"))
            }
          }
        }
        
        _ <- IO.println("Token exchange successful")
        userReq = Request[IO](method = Method.GET, uri = uri"https://api.twitch.tv/helix/users").putHeaders(
          Authorization(Credentials.Token(AuthScheme.Bearer, tokenResponse.access_token)),
          Header.Raw(ci"Client-Id", clientId)
        )
        userResponse <- client.expect[TwitchUsersResponse](userReq)
        user = userResponse.data.head
        _ <- IO.println(s"Found user: ${user.display_name}")
        sessionId = UUID.randomUUID().toString
        _ <- userSession.update(_ + (sessionId -> SessionData(user, tokenResponse.access_token)))
        res <- Found(Location(uri"/")).map(_.addCookie(ResponseCookie("session_id", sessionId, path = Some("/"), httpOnly = true)))
      } yield res

      flow.handleErrorWith { err =>
        IO.println(s"Auth flow failed: ${err.getMessage}") *>
          InternalServerError(s"Auth flow failed. Check server logs. Error: ${err.getMessage}")
      }
  }

  def apiRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "config" =>
      Ok(AppConfig(clientId))
    case req @ GET -> Root / "user" =>
      getSession(req).flatMap {
        case Some(data) => Ok(data.user)
        case None       => NotFound("Not logged in")
      }
    case req @ GET -> Root / "followed" =>
      getSession(req).flatMap {
        case Some(data) =>
          db.getFollowed(data.user.id).flatMap(cats => Ok(FollowedCategoriesResponse(cats)))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "follow" =>
      req.as[FollowRequest].flatMap { followReq =>
        getSession(req).flatMap {
          case Some(data) =>
            db.follow(data.user.id, followReq.category) *> Ok("Followed")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "unfollow" / categoryId =>
      getSession(req).flatMap {
        case Some(data) =>
          db.unfollow(data.user.id, categoryId) *> Ok("Unfollowed")
        case None => Forbidden("Not logged in")
      }
    case req @ GET -> Root / "search" / "categories" :? SearchQueryParamMatcher(query) +& AfterQueryParamMatcher(after) =>
      getSession(req).flatMap {
        case Some(data) =>
          val uri = uri"https://api.twitch.tv/helix/search/categories"
            .withQueryParam("query", query)
            .withOptionQueryParam("after", after)
          val searchReq = Request[IO](method = Method.GET, uri = uri).putHeaders(
            Authorization(Credentials.Token(AuthScheme.Bearer, data.accessToken)),
            Header.Raw(ci"Client-Id", clientId)
          )
          client.expect[TwitchSearchCategoriesResponse](searchReq).flatMap(Ok(_))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "logout" =>
      val sessionId = req.cookies.find(_.name == "session_id").map(_.content)
      for {
        _ <- sessionId.fold(IO.unit)(id => userSession.update(_ - id))
        res <- Ok("Logged out").map(_.removeCookie("session_id"))
      } yield res
    case req @ GET -> Root / "tag-filters" =>
      getSession(req).flatMap {
        case Some(data) =>
          db.getTagFilters(data.user.id).flatMap(filters => Ok(TagFiltersResponse(filters)))
        case None => Forbidden("Not logged in")
      }
    case req @ POST -> Root / "tag-filters" / "add" =>
      req.as[AddTagFilterRequest].flatMap { body =>
        getSession(req).flatMap {
          case Some(data) =>
            val tag = body.tag.trim
            if tag.isEmpty || tag.length > 25 then BadRequest("Tag must be 1-25 characters")
            else if body.filterType != "include" && body.filterType != "exclude" then BadRequest("filterType must be 'include' or 'exclude'")
            else db.addTagFilter(data.user.id, body.filterType, tag) *> Ok("Filter added")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ POST -> Root / "tag-filters" / "remove" =>
      req.as[AddTagFilterRequest].flatMap { body =>
        getSession(req).flatMap {
          case Some(data) =>
            db.removeTagFilter(data.user.id, body.filterType, body.tag) *> Ok("Filter removed")
          case None => Forbidden("Not logged in")
        }
      }
    case req @ GET -> Root / "notifications" / "stream" =>
      getSession(req).flatMap {
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
