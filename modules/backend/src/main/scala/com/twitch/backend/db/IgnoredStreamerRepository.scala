package com.twitch.backend.db

import cats.effect.*
import doobie.*
import doobie.implicits.*
import com.twitch.core.IgnoredStreamer
import com.twitch.backend.SqlDialect

class IgnoredStreamerRepository(xa: Transactor[IO], dialect: SqlDialect):

  def getIgnoredStreamers(userId: String): IO[List[IgnoredStreamer]] =
    sql"SELECT streamer_id, streamer_login, streamer_name FROM ignored_streamers WHERE user_id = $userId"
      .query[IgnoredStreamer]
      .to[List]
      .transact(xa)

  def addIgnoredStreamer(userId: String, streamerId: String, streamerLogin: String, streamerName: String): IO[Unit] =
    val stmt = dialect match
      case SqlDialect.Postgres =>
        sql"""
          INSERT INTO ignored_streamers (user_id, streamer_id, streamer_login, streamer_name)
          VALUES ($userId, $streamerId, $streamerLogin, $streamerName)
          ON CONFLICT (user_id, streamer_id) DO UPDATE SET streamer_login = EXCLUDED.streamer_login, streamer_name = EXCLUDED.streamer_name
        """
      case SqlDialect.H2 =>
        sql"""
          MERGE INTO ignored_streamers (user_id, streamer_id, streamer_login, streamer_name)
          KEY(user_id, streamer_id)
          VALUES ($userId, $streamerId, $streamerLogin, $streamerName)
        """
    stmt.update.run.transact(xa).void

  def removeIgnoredStreamer(userId: String, streamerId: String): IO[Unit] =
    sql"DELETE FROM ignored_streamers WHERE user_id = $userId AND streamer_id = $streamerId"
      .update.run.transact(xa).void
