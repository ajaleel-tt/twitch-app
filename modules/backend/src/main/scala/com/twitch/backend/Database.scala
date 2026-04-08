package com.twitch.backend

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import com.twitch.core.{TwitchCategory, TagFilter}

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
    (createFollowed *> createTagFilters).transact(xa).void

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
