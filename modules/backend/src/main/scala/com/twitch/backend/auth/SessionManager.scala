package com.twitch.backend.auth

import cats.effect.*
import org.http4s.*
import java.time.Instant
import com.twitch.core.*
import com.twitch.backend.TwitchApi
import com.twitch.backend.db.SessionRepository

case class SessionData(
    user: TwitchUser,
    accessToken: String,
    refreshToken: Option[String],
    tokenExpiresAt: Option[Long],
    sessionId: String
)

class SessionManager(sessionRepo: SessionRepository, twitchApi: TwitchApi):

  def getSession(req: Request[IO]): IO[Option[SessionData]] =
    req.cookies.find(_.name == "session_id").map(_.content) match
      case None => IO.pure(None)
      case Some(sid) =>
        sessionRepo.getSession(sid).map(_.map(row =>
          SessionData(row.toUser, row.accessToken, row.refreshToken, row.tokenExpiresAt, row.sessionId)
        ))

  def refreshTokenIfNeeded(data: SessionData): IO[SessionData] =
    val needsRefresh = data.tokenExpiresAt.exists { expiresAt =>
      Instant.now().getEpochSecond >= expiresAt - 300
    }
    if !needsRefresh || data.refreshToken.isEmpty then IO.pure(data)
    else
      twitchApi.refreshToken(data.refreshToken.get).flatMap { tokenResp =>
        val expiresAt = Some(Instant.now().plusSeconds(tokenResp.expires_in.toLong))
        sessionRepo.updateSessionToken(data.sessionId, tokenResp.access_token, tokenResp.refresh_token.orElse(data.refreshToken), expiresAt) *>
          IO.pure(data.copy(
            accessToken = tokenResp.access_token,
            refreshToken = tokenResp.refresh_token.orElse(data.refreshToken),
            tokenExpiresAt = expiresAt.map(_.getEpochSecond)
          ))
      }.handleErrorWith(_ => IO.pure(data))
