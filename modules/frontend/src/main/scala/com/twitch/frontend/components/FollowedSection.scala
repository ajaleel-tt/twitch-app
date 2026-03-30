package com.twitch.frontend.components

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import fs2.concurrent.*
import fs2.dom.*
import com.twitch.frontend.{Model, ApiClient}
import com.twitch.core.*

object FollowedSection:

  def followedCategoriesView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      styleAttr := "display: flex; flex-wrap: wrap; justify-content: center;",
      children <-- state.map { m =>
        if m.followedCategories.isEmpty then
          List(p("You haven't followed any categories yet."))
        else
          m.followedCategories.map { cat =>
            followedCategoryCard(state, cat)
          }
      }
    )

  private def followedCategoryCard(state: SignallingRef[IO, Model], cat: TwitchCategory): Resource[IO, HtmlDivElement[IO]] =
    val boxArtUrl = cat.box_art_url
      .replace("{width}", "140").replace("{height}", "184")
      .replaceAll("""-(\d+)x(\d+)\.""", "-140x184.")
    div(
      styleAttr := "margin: 10px; padding: 10px; border: 1px solid #ddd; border-radius: 8px; width: 120px; background: white; display: flex; flex-direction: column; align-items: center;",
      img(src := boxArtUrl, styleAttr := "width: 70px; height: 92px; border-radius: 4px;"),
      p(styleAttr := "font-size: 0.7rem; font-weight: bold; margin: 5px 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; width: 100%; text-align: center;", cat.name),
      button(
        styleAttr := "background: #ff4646; font-size: 0.7rem; padding: 2px 5px;",
        "Unfollow",
        onClick --> { _.foreach(_ =>
          (state.update(_.copy(status = Some("Unfollowing..."))) *>
            ApiClient.postUnfollow(cat.id).flatMap(_ =>
              ApiClient.fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats, status = None)))
            )).start.void
        )}
      )
    )
