package com.twitch.backend

import scala.concurrent.duration.*

import cats.effect.IO
import com.typesafe.config.ConfigFactory

case class AppSettings(
  emailFrom: String,
  emailFromName: String,
  parallelCategories: Int,
  pollerInterval: FiniteDuration,
  pushParallelSends: Int,
  recentlyLiveWindow: FiniteDuration,
  searchPageSize: Int,
  sseReconnectDelay: FiniteDuration,
  streamsPageSize: Int,
  topGamesCount: Int,
  topGamesPollInterval: FiniteDuration,
)

object AppSettings {

  def load: IO[AppSettings] = IO.blocking {
    val config = ConfigFactory.load().getConfig("twitch-app")
    AppSettings(
      emailFrom = config.getString("email.from"),
      emailFromName = config.getString("email.from-name"),
      parallelCategories = config.getInt("poller.parallel-categories"),
      pollerInterval = config.getDuration("poller.interval").toMillis.millis,
      pushParallelSends = config.getInt("push.parallel-sends"),
      recentlyLiveWindow = config.getDuration("poller.recently-live-window").toMillis.millis,
      searchPageSize = config.getInt("search.page-size"),
      sseReconnectDelay = config.getDuration("sse.reconnect-delay").toMillis.millis,
      streamsPageSize = config.getInt("poller.streams-page-size"),
      topGamesCount = config.getInt("top-games.count"),
      topGamesPollInterval = config.getDuration("top-games.poll-interval").toMillis.millis,
    )
  }

}
