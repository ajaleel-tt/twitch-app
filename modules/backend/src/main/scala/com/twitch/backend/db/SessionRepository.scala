package com.twitch.backend.db

import cats.effect.*
import doobie.*
import doobie.implicits.*
import com.twitch.core.TwitchUser
import java.time.Instant

class SessionRepository(xa: Transactor[IO]):

  def createSession(
      sessionId: String,
      user: TwitchUser,
      accessToken: String,
      refreshToken: Option[String],
      tokenExpiresAt: Option[Instant]
  ): IO[Unit] =
    val now = Instant.now().getEpochSecond
    val expiresAt = tokenExpiresAt.map(_.getEpochSecond)
    sql"""
      INSERT INTO sessions (session_id, user_id, user_login, display_name, profile_image_url, access_token, refresh_token, token_expires_at, created_at)
      VALUES ($sessionId, ${user.id}, ${user.login}, ${user.display_name}, ${user.profile_image_url}, $accessToken, $refreshToken, $expiresAt, $now)
    """.update.run.transact(xa).void

  def getSession(sessionId: String): IO[Option[SessionRow]] =
    sql"""
      SELECT session_id, user_id, user_login, display_name, profile_image_url, access_token, refresh_token, token_expires_at, created_at
      FROM sessions WHERE session_id = $sessionId
    """.query[SessionRow].option.transact(xa)

  def updateSessionToken(sessionId: String, accessToken: String, refreshToken: Option[String], tokenExpiresAt: Option[Instant]): IO[Unit] =
    val expiresAt = tokenExpiresAt.map(_.getEpochSecond)
    sql"""
      UPDATE sessions SET access_token = $accessToken, refresh_token = $refreshToken, token_expires_at = $expiresAt
      WHERE session_id = $sessionId
    """.update.run.transact(xa).void

  def deleteSession(sessionId: String): IO[Unit] =
    sql"DELETE FROM sessions WHERE session_id = $sessionId"
      .update.run.transact(xa).void

case class SessionRow(
    sessionId: String,
    userId: String,
    userLogin: String,
    displayName: String,
    profileImageUrl: String,
    accessToken: String,
    refreshToken: Option[String],
    tokenExpiresAt: Option[Long],
    createdAt: Long
):
  def toUser: TwitchUser = TwitchUser(userId, userLogin, displayName, profileImageUrl)
