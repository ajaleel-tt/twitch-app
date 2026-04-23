package com.twitch.backend

import com.typesafe.config.ConfigFactory
import scala.concurrent.duration.*

case class AppSettings(
    pollerInterval: FiniteDuration,
    recentlyLiveWindow: FiniteDuration,
    parallelCategories: Int,
    streamsPageSize: Int,
    searchPageSize: Int,
    sseReconnectDelay: FiniteDuration,
    emailFrom: String,
    emailFromName: String,
    pushParallelSends: Int,
    topGamesCount: Int,
    topGamesPollInterval: FiniteDuration
)

object AppSettings:
  def load: AppSettings =
    val config = ConfigFactory.load().getConfig("twitch-app")
    AppSettings(
      pollerInterval = config.getDuration("poller.interval").toMillis.millis,
      recentlyLiveWindow = config.getDuration("poller.recently-live-window").toMillis.millis,
      parallelCategories = config.getInt("poller.parallel-categories"),
      streamsPageSize = config.getInt("poller.streams-page-size"),
      searchPageSize = config.getInt("search.page-size"),
      sseReconnectDelay = config.getDuration("sse.reconnect-delay").toMillis.millis,
      emailFrom = config.getString("email.from"),
      emailFromName = config.getString("email.from-name"),
      pushParallelSends = config.getInt("push.parallel-sends"),
      topGamesCount = config.getInt("top-games.count"),
      topGamesPollInterval = config.getDuration("top-games.poll-interval").toMillis.millis
    )
