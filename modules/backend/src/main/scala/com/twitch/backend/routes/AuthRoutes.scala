package com.twitch.backend.routes

import cats.effect.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.headers.Location
import org.http4s.SameSite
import java.util.UUID
import java.time.Instant
import com.twitch.core.*
import com.twitch.backend.{TwitchApi, EmailNotifier}
import com.twitch.backend.db.{UserRepository, SessionRepository}

class AuthRoutes(
    clientId: String,
    redirectUri: String,
    twitchApi: TwitchApi,
    pendingOAuthStates: Ref[IO, Set[String]],
    userRepo: UserRepository,
    sessionRepo: SessionRepository,
    emailService: Option[EmailNotifier]
):

  private val secureCookies = redirectUri.startsWith("https")

  private def sendWelcomeEmailIfNeeded(user: TwitchUser): IO[Unit] =
    (user.email, emailService) match
      case (Some(email), Some(es)) =>
        es.sendWelcomeEmail(email, user.display_name)
          .flatMap(_ => userRepo.markWelcomeEmailSent(user.id))
          .handleErrorWith(err =>
            IO.println(s"Failed to send welcome email to ${user.id}: ${err.getMessage}")
          )
          .start
          .void
      case _ =>
        IO.println(s"Skipping welcome email for ${user.id} (no email or email service not configured)")

  private object CodeQueryParamMatcher extends QueryParamDecoderMatcher[String]("code")
  private object StateQueryParamMatcher extends QueryParamDecoderMatcher[String]("state")

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
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
        tokenResponse <- twitchApi.exchangeCode(code, redirectUri)
        _ <- IO.println("Token exchange successful")
        user <- twitchApi.getUser(tokenResponse.access_token)
        _ <- IO.println(s"Found user: ${user.display_name}")
        existingUser <- userRepo.findUser(user.id)
        _ <- existingUser match
          case None =>
            userRepo.insertUser(user.id, user.login, user.display_name, user.email) *>
              sendWelcomeEmailIfNeeded(user)
          case Some(existing) =>
            userRepo.updateLastLogin(user.id, user.login, user.display_name, user.email) *>
              (if !existing.welcomeEmailSent then sendWelcomeEmailIfNeeded(user) else IO.unit)
        sessionId = UUID.randomUUID().toString
        tokenExpiresAt = Some(Instant.now().plusSeconds(tokenResponse.expires_in.toLong))
        _ <- sessionRepo.createSession(sessionId, user, tokenResponse.access_token, tokenResponse.refresh_token, tokenExpiresAt)
        res <- Found(Location(uri"/")).map(_.addCookie(ResponseCookie(
          "session_id", sessionId,
          path = Some("/"),
          httpOnly = true,
          secure = secureCookies,
          sameSite = Some(SameSite.Lax)
        )))
      } yield res

      flow.handleErrorWith { err =>
        IO.println(s"Auth flow failed: ${err.getMessage}") *>
          InternalServerError(s"Auth flow failed. Check server logs. Error: ${err.getMessage}")
      }
  }
