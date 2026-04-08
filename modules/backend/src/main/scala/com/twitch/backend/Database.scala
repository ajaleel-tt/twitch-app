package com.twitch.backend

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import com.twitch.core.{TwitchCategory, TwitchUser, TagFilter}
import java.time.Instant

enum SqlDialect:
  case H2, Postgres

class Database(xa: Transactor[IO], dialect: SqlDialect = SqlDialect.H2):

  def initDb: IO[Unit] =
    val createFollowed = sql"""
      CREATE TABLE IF NOT EXISTS followed_categories (
        user_id VARCHAR NOT NULL,
        category_id VARCHAR NOT NULL,
        name VARCHAR NOT NULL,
        box_art_url VARCHAR NOT NULL,
        PRIMARY KEY (user_id, category_id)
      )
    """.update.run
    val createTagFilters = sql"""
      CREATE TABLE IF NOT EXISTS tag_filters (
        user_id VARCHAR NOT NULL,
        filter_type VARCHAR NOT NULL,
        tag VARCHAR(25) NOT NULL,
        PRIMARY KEY (user_id, filter_type, tag)
      )
    """.update.run
    val createSessions = sql"""
      CREATE TABLE IF NOT EXISTS sessions (
        session_id VARCHAR PRIMARY KEY,
        user_id VARCHAR NOT NULL,
        user_login VARCHAR NOT NULL,
        display_name VARCHAR NOT NULL,
        profile_image_url VARCHAR NOT NULL,
        access_token VARCHAR NOT NULL,
        refresh_token VARCHAR,
        token_expires_at BIGINT,
        created_at BIGINT NOT NULL
      )
    """.update.run
    (createFollowed *> createTagFilters *> createSessions).transact(xa).void

  def getFollowed(userId: String): IO[List[TwitchCategory]] =
    sql"SELECT category_id, name, box_art_url FROM followed_categories WHERE user_id = $userId"
      .query[TwitchCategory]
      .to[List]
      .transact(xa)

  def follow(userId: String, category: TwitchCategory): IO[Unit] =
    val stmt = dialect match
      case SqlDialect.Postgres =>
        sql"""
          INSERT INTO followed_categories (user_id, category_id, name, box_art_url)
          VALUES ($userId, ${category.id}, ${category.name}, ${category.box_art_url})
          ON CONFLICT (user_id, category_id) DO UPDATE SET name = EXCLUDED.name, box_art_url = EXCLUDED.box_art_url
        """
      case SqlDialect.H2 =>
        sql"""
          MERGE INTO followed_categories (user_id, category_id, name, box_art_url)
          KEY(user_id, category_id)
          VALUES ($userId, ${category.id}, ${category.name}, ${category.box_art_url})
        """
    stmt.update.run.transact(xa).void

  def unfollow(userId: String, categoryId: String): IO[Unit] =
    sql"DELETE FROM followed_categories WHERE user_id = $userId AND category_id = $categoryId"
      .update.run.transact(xa).void

  def getAllFollowedCategories: IO[List[TwitchCategory]] =
    sql"SELECT DISTINCT category_id, name, box_art_url FROM followed_categories"
      .query[TwitchCategory]
      .to[List]
      .transact(xa)

  def getTagFilters(userId: String): IO[List[TagFilter]] =
    sql"SELECT filter_type, tag FROM tag_filters WHERE user_id = $userId"
      .query[TagFilter]
      .to[List]
      .transact(xa)

  def addTagFilter(userId: String, filterType: String, tag: String): IO[Unit] =
    val normalizedTag = tag.trim.toLowerCase
    val stmt = dialect match
      case SqlDialect.Postgres =>
        sql"""
          INSERT INTO tag_filters (user_id, filter_type, tag)
          VALUES ($userId, $filterType, $normalizedTag)
          ON CONFLICT (user_id, filter_type, tag) DO NOTHING
        """
      case SqlDialect.H2 =>
        sql"""
          MERGE INTO tag_filters (user_id, filter_type, tag)
          KEY(user_id, filter_type, tag)
          VALUES ($userId, $filterType, $normalizedTag)
        """
    stmt.update.run.transact(xa).void

  def removeTagFilter(userId: String, filterType: String, tag: String): IO[Unit] =
    val normalizedTag = tag.trim.toLowerCase
    sql"DELETE FROM tag_filters WHERE user_id = $userId AND filter_type = $filterType AND tag = $normalizedTag"
      .update.run.transact(xa).void

  // ── Session persistence ─────────────────────────────────────────────

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
