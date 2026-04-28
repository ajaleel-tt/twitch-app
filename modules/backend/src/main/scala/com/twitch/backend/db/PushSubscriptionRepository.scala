package com.twitch.backend.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import com.twitch.backend.SqlDialect
import java.time.Instant

class PushSubscriptionRepository(xa: Transactor[IO], dialect: SqlDialect):

  def savePushSubscription(userId: String, deviceToken: String, platform: String): IO[Unit] =
    val id = java.util.UUID.randomUUID().toString
    val now = Instant.now().getEpochSecond
    val stmt = dialect match
      case SqlDialect.Postgres =>
        sql"""
          INSERT INTO push_subscriptions (id, user_id, device_token, platform, created_at)
          VALUES ($id, $userId, $deviceToken, $platform, $now)
          ON CONFLICT (user_id, device_token) DO UPDATE SET platform = EXCLUDED.platform
        """
      case SqlDialect.H2 =>
        sql"""
          MERGE INTO push_subscriptions (id, user_id, device_token, platform, created_at)
          KEY(user_id, device_token)
          VALUES ($id, $userId, $deviceToken, $platform, $now)
        """
    stmt.update.run.transact(xa).void

  def deletePushSubscription(deviceToken: String): IO[Unit] =
    sql"DELETE FROM push_subscriptions WHERE device_token = $deviceToken"
      .update.run.transact(xa).void

  def getPushSubscriptionsForUsers(userIds: Set[String]): IO[List[PushSubscriptionRow]] =
    if userIds.isEmpty then IO.pure(Nil)
    else
      val inClause = Fragments.in(fr"user_id", userIds.toList.toNel.get)
      (fr"SELECT id, user_id, device_token, platform, created_at FROM push_subscriptions WHERE" ++ inClause)
        .query[PushSubscriptionRow]
        .to[List]
        .transact(xa)

case class PushSubscriptionRow(
    id: String,
    userId: String,
    deviceToken: String,
    platform: String,
    createdAt: Long
)
