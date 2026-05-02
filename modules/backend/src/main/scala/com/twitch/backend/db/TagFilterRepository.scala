package com.twitch.backend.db

import cats.effect.*
import doobie.*
import doobie.implicits.*
import com.twitch.core.TagFilter
import com.twitch.backend.SqlDialect

class TagFilterRepository(xa: Transactor[IO], dialect: SqlDialect) {

  def getTagFilters(userId: String): IO[List[TagFilter]] =
    sql"SELECT filter_type, tag FROM tag_filters WHERE user_id = $userId"
      .query[TagFilter]
      .to[List]
      .transact(xa)

  def countTagFilters(userId: String): IO[Long] =
    sql"SELECT COUNT(*) FROM tag_filters WHERE user_id = $userId"
      .query[Long]
      .unique
      .transact(xa)

  def tagFilterExists(userId: String, filterType: String, tag: String): IO[Boolean] = {
    val normalizedTag = tag.trim.toLowerCase
    sql"SELECT COUNT(*) FROM tag_filters WHERE user_id = $userId AND filter_type = $filterType AND tag = $normalizedTag"
      .query[Long]
      .unique
      .map(_ > 0)
      .transact(xa)
  }

  def addTagFilter(userId: String, filterType: String, tag: String): IO[Unit] = {
    val normalizedTag = tag.trim.toLowerCase
    val stmt = dialect match {
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
    }
    stmt.update.run.transact(xa).void
  }

  def removeTagFilter(userId: String, filterType: String, tag: String): IO[Unit] = {
    val normalizedTag = tag.trim.toLowerCase
    sql"DELETE FROM tag_filters WHERE user_id = $userId AND filter_type = $filterType AND tag = $normalizedTag"
      .update
      .run
      .transact(xa)
      .void
  }

}
