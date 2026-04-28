package com.twitch.backend.db

import cats.effect.*
import doobie.*
import doobie.implicits.*
import java.time.Instant

class UserRepository(xa: Transactor[IO]):

  def findUser(userId: String): IO[Option[UserRow]] =
    sql"SELECT user_id, login, display_name, email, welcome_email_sent, created_at, last_login_at FROM users WHERE user_id = $userId"
      .query[UserRow]
      .option
      .transact(xa)

  def insertUser(userId: String, login: String, displayName: String, email: Option[String]): IO[Unit] =
    val now = Instant.now().getEpochSecond
    sql"""
      INSERT INTO users (user_id, login, display_name, email, welcome_email_sent, created_at, last_login_at)
      VALUES ($userId, $login, $displayName, $email, false, $now, $now)
    """.update.run.transact(xa).void

  def updateLastLogin(userId: String, login: String, displayName: String, email: Option[String]): IO[Unit] =
    val now = Instant.now().getEpochSecond
    sql"""
      UPDATE users SET last_login_at = $now, login = $login, display_name = $displayName, email = COALESCE($email, email)
      WHERE user_id = $userId
    """.update.run.transact(xa).void

  def markWelcomeEmailSent(userId: String): IO[Unit] =
    sql"UPDATE users SET welcome_email_sent = true WHERE user_id = $userId"
      .update.run.transact(xa).void

case class UserRow(
    userId: String,
    login: Option[String],
    displayName: Option[String],
    email: Option[String],
    welcomeEmailSent: Boolean,
    createdAt: Long,
    lastLoginAt: Long
)
