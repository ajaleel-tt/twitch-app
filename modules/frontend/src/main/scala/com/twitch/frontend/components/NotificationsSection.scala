package com.twitch.frontend.components

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import fs2.concurrent.*
import fs2.dom.*
import com.twitch.frontend.Model
import com.twitch.core.*

object NotificationsSection:

  def notificationsView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      styleAttr := "width: 100%;",
      div(
        styleAttr := "display: flex; flex-wrap: wrap; justify-content: center;",
        children <-- state.map { m =>
          val followedIds = m.followedCategories.map(_.id).toSet
          val relevant = m.notifications.filter(n => followedIds.contains(n.categoryId))
          if relevant.isEmpty then
            List(p(styleAttr := "color: #888;", "No streams detected live yet. Notifications will appear here."))
          else
            relevant.map(notificationCard)
        }
      )
    )

  private def notificationCard(n: StreamNotification): Resource[IO, HtmlDivElement[IO]] =
    div(
      styleAttr := "margin: 8px; padding: 10px; border: 2px solid #9146ff; border-radius: 8px; width: 200px; background: #f0e6ff; display: flex; flex-direction: column; align-items: center; cursor: pointer;",
      onClick --> { _.foreach(_ =>
        IO { val _ = org.scalajs.dom.window.open(s"https://twitch.tv/${n.streamerLogin}", "_blank") }
      )},
      img(
        src := n.thumbnailUrl,
        styleAttr := "width: 180px; height: 100px; border-radius: 4px; object-fit: cover;"
      ),
      p(
        styleAttr := "font-size: 0.7rem; font-weight: bold; margin: 4px 0; color: #9146ff;",
        s"LIVE - ${n.categoryName}"
      ),
      p(
        styleAttr := "font-size: 0.85rem; font-weight: bold; margin: 2px 0;",
        n.streamerName
      ),
      p(
        styleAttr := "font-size: 0.7rem; margin: 2px 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; width: 100%; text-align: center;",
        n.streamTitle
      ),
      p(
        styleAttr := "font-size: 0.7rem; color: #666; margin: 2px 0;",
        s"${n.viewerCount} viewers"
      )
    )
