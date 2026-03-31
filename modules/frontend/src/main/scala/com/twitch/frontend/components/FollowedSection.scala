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
      cls := "grid grid-cols-3 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6 gap-4",
      children <-- state.map { m =>
        if m.followedCategories.isEmpty then
          List(div(
            cls := "col-span-full text-center py-8",
            p(cls := "text-gray-500", "You haven't followed any categories yet.")
          ))
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
      cls := "bg-twitch-dark-card rounded-xl border border-gray-800 p-3 flex flex-col items-center gap-2 hover:border-twitch-purple transition-all duration-200",
      img(src := boxArtUrl, cls := "w-24 h-32 rounded-lg object-cover"),
      p(cls := "text-sm font-semibold text-white text-center truncate w-full", cat.name),
      button(
        cls := "bg-twitch-danger/80 hover:bg-twitch-danger text-white text-xs px-3 py-1 rounded-full transition-colors cursor-pointer",
        "Unfollow",
        onClick --> { _.foreach(_ =>
          (state.update(_.copy(status = Some("Unfollowing..."))) *>
            ApiClient.postUnfollow(cat.id).flatMap(_ =>
              ApiClient.fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats, status = None)))
            )).start.void
        )}
      )
    )
