package com.twitch.backend

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import com.twitch.core.*

object StreamLogic:

  def toNotification(s: TwitchStream): StreamNotification =
    StreamNotification(
      categoryId = s.game_id,
      categoryName = s.game_name,
      streamerId = s.user_id,
      streamerLogin = s.user_login,
      streamerName = s.user_name,
      streamTitle = s.title,
      viewerCount = s.viewer_count,
      thumbnailUrl = s.thumbnail_url.replace("{width}", "320").replace("{height}", "180"),
      tags = s.tags.getOrElse(Nil)
    )

  def recentlyWentLive(s: TwitchStream, now: Instant, window: FiniteDuration): Boolean =
    s.`type` == "live" && {
      val startedAt = Instant.parse(s.started_at)
      java.time.Duration.between(startedAt, now).toMillis < window.toMillis
    }

  def applyTagFilters(
      notifications: List[StreamNotification],
      filters: List[TagFilter]
  ): List[StreamNotification] =
    val includeTags = filters.filter(_.filterType == "include").map(_.tag.toLowerCase).toSet
    val excludeTags = filters.filter(_.filterType == "exclude").map(_.tag.toLowerCase).toSet
    notifications.filter { n =>
      val streamTags = n.tags.map(_.toLowerCase).toSet
      val passesInclude = includeTags.isEmpty || streamTags.exists(includeTags.contains)
      val passesExclude = !streamTags.exists(excludeTags.contains)
      passesInclude && passesExclude
    }
