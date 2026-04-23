package com.twitch.frontend.components

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import fs2.concurrent.*
import fs2.dom.*
import com.twitch.frontend.{Model, ApiClient}
import com.twitch.core.*

object SearchSection:

  def popularGameWarning(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls <-- state.map { m =>
        if m.pendingPopularFollow.isDefined then
          List("fixed", "inset-0", "z-50", "flex", "items-center", "justify-center", "bg-black/60")
        else List("hidden")
      },
      div(
        cls := "bg-twitch-dark-card border border-gray-700 rounded-2xl shadow-2xl p-6 max-w-md w-full mx-4 flex flex-col gap-4",
        div(
          cls := "flex items-center gap-3",
          span(cls := "text-2xl", "⚠️"),
          h3(cls := "text-lg font-bold text-white", "Popular Category Warning")
        ),
        p(
          cls := "text-gray-300 text-sm leading-relaxed",
          state.map { m =>
            m.pendingPopularFollow match
              case Some(cat) =>
                s""""${cat.name}" is a very popular category. Following it may result in a large number of notifications. Are you sure you want to follow it?"""
              case None => ""
          }
        ),
        div(
          cls := "flex gap-3 justify-end mt-2",
          button(
            cls := "bg-gray-700 hover:bg-gray-600 text-white font-medium px-5 py-2 rounded-lg transition-colors cursor-pointer",
            "Cancel",
            onClick --> { _.foreach(_ =>
              state.update(_.copy(pendingPopularFollow = None))
            )}
          ),
          button(
            cls := "bg-twitch-purple hover:bg-twitch-purple-dark text-white font-medium px-5 py-2 rounded-lg transition-colors cursor-pointer",
            "OK, Follow Anyway",
            onClick --> { _.foreach(_ =>
              state.get.flatMap { m =>
                m.pendingPopularFollow match
                  case Some(cat) =>
                    state.update(_.copy(pendingPopularFollow = None)) *>
                      (ApiClient.postFollow(cat).flatMap(_ =>
                          ApiClient.fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats)))
                        )).start.void
                  case None => IO.unit
              }
            )}
          )
        )
      )
    )

  private def doSearch(state: SignallingRef[IO, Model]): IO[Unit] =
    state.get.flatMap { m =>
      if m.searchQuery.trim.isEmpty then IO.unit
      else
        state.update(_.copy(status = Some("Searching..."), searchResults = Vector.empty, paginationCursor = None, currentPage = 0)) *>
          ApiClient.searchCategories(m.searchQuery).flatMap {
            case Some(res) =>
              state.update(_.copy(
                searchResults = res.data.toVector,
                paginationCursor = res.pagination.flatMap(_.cursor),
                status = None
              ))
            case None =>
              state.update(_.copy(status = Some("Error: Search failed")))
          }
    }

  def searchInput(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "flex gap-3 justify-center mb-6",
      input.withSelf { self =>
        (
          typ := "text",
          placeholder := "Search for a category...",
          cls := "bg-twitch-dark-card border border-gray-700 text-white placeholder-gray-500 rounded-lg px-4 py-3 w-80 focus:outline-none focus:ring-2 focus:ring-twitch-purple focus:border-transparent transition-all",
          value <-- state.map(_.searchQuery),
          onInput --> { _.foreach(_ => self.value.get.flatMap(q => state.update(_.copy(searchQuery = q)))) },
          onKeyPress --> { _.foreach(e => IO.whenA(e.key == "Enter")(doSearch(state))) }
        )
      },
      button(
        cls := "bg-twitch-purple hover:bg-twitch-purple-dark text-white font-medium px-6 py-3 rounded-lg transition-colors cursor-pointer",
        "Search",
        onClick --> { _.foreach(_ => doSearch(state)) }
      )
    )

  def searchResultsView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4 mt-4",
      children <-- state.map { m =>
        val paginatedResults = m.searchResults.slice(m.currentPage * m.pageSize, (m.currentPage + 1) * m.pageSize)
        paginatedResults.map(cat => categoryCard(state, cat)).toList
      }
    )

  def paginationView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls <-- state.map { m =>
        if m.searchResults.nonEmpty then List("flex", "flex-col", "items-center", "mt-6", "gap-4")
        else List("hidden")
      },
      div(
        cls := "flex gap-3 items-center",
        button(
          cls := "bg-twitch-dark-card border border-gray-700 text-white px-4 py-2 rounded-lg hover:bg-twitch-dark-hover disabled:opacity-40 disabled:cursor-not-allowed transition-colors cursor-pointer",
          "Previous",
          disabled <-- state.map(_.currentPage == 0),
          onClick --> { _.foreach(_ => state.update(s => s.copy(currentPage = s.currentPage - 1))) }
        ),
        span(
          cls := "text-gray-400 text-sm",
          state.map { m =>
            val totalLocalPages = Math.max(1, (m.searchResults.size + m.pageSize - 1) / m.pageSize)
            s"Page ${m.currentPage + 1} of $totalLocalPages"
          }
        ),
        button(
          cls := "bg-twitch-dark-card border border-gray-700 text-white px-4 py-2 rounded-lg hover:bg-twitch-dark-hover disabled:opacity-40 disabled:cursor-not-allowed transition-colors cursor-pointer",
          "Next",
          disabled <-- state.map { m =>
            val totalLocalPages = (m.searchResults.size + m.pageSize - 1) / m.pageSize
            m.currentPage >= totalLocalPages - 1
          },
          onClick --> { _.foreach(_ => state.update(s => s.copy(currentPage = s.currentPage + 1))) }
        )
      ),
      button(
        cls <-- state.map { m =>
          if m.paginationCursor.isDefined then List("bg-twitch-purple", "hover:bg-twitch-purple-dark", "text-white", "font-medium", "px-6", "py-2", "rounded-full", "transition-colors", "cursor-pointer")
          else List("hidden")
        },
        "Load More results from Twitch",
        onClick --> { _.foreach { _ =>
          state.get.flatMap { s =>
            if s.paginationCursor.isEmpty then IO.unit
            else
              state.update(_.copy(status = Some("Fetching more..."))) *>
                ApiClient.searchCategories(s.searchQuery, s.paginationCursor).flatMap {
                  case Some(res) =>
                    state.update(st => st.copy(
                      searchResults = st.searchResults ++ res.data,
                      paginationCursor = res.pagination.flatMap(_.cursor),
                      status = None
                    ))
                  case None =>
                    state.update(_.copy(status = Some("Error: Failed to load more")))
                }
          }
        }}
      )
    )

  private def categoryCard(state: SignallingRef[IO, Model], cat: TwitchCategory): Resource[IO, HtmlDivElement[IO]] =
    val boxArtUrl = cat.box_art_url
      .replace("{width}", "280").replace("{height}", "370")
      .replaceAll("""-(\d+)x(\d+)\.""", "-280x370.")
    div(
      cls <-- state.map { m =>
        val isSelected = m.selectedCategoryIds.contains(cat.id)
        val base = List("bg-twitch-dark-card", "rounded-xl", "overflow-hidden", "border", "transition-all", "duration-200", "flex", "flex-col", "items-center")
        if isSelected then base ++ List("border-twitch-purple", "ring-2", "ring-twitch-purple", "shadow-lg", "shadow-twitch-purple/20")
        else base ++ List("border-gray-800", "hover:border-twitch-purple", "hover:shadow-lg", "hover:shadow-twitch-purple/10")
      },
      div(
        cls := "cursor-pointer flex flex-col items-center w-full",
        onClick --> { _.foreach(_ => state.update(m =>
          val newSel = if m.selectedCategoryIds.contains(cat.id) then m.selectedCategoryIds - cat.id else m.selectedCategoryIds + cat.id
          m.copy(selectedCategoryIds = newSel)
        )) },
        img(src := boxArtUrl, cls := "w-full h-48 object-cover"),
        p(cls := "text-sm font-semibold text-white p-3 text-center truncate w-full", cat.name)
      ),
      div(
        cls := "px-3 pb-3 w-full flex justify-center",
        button(
          cls <-- state.map { m =>
            val isFollowed = m.followedCategories.exists(_.id == cat.id)
            if isFollowed then List("bg-twitch-danger", "hover:bg-red-600", "text-white", "text-xs", "px-4", "py-1.5", "rounded-full", "transition-colors", "cursor-pointer", "w-full")
            else List("hidden")
          },
          "Unfollow",
          onClick --> { _.foreach(_ =>
            (ApiClient.postUnfollow(cat.id).flatMap(_ =>
                ApiClient.fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats)))
              )).start.void
          )}
        ),
        button(
          cls <-- state.map { m =>
            val isFollowed = m.followedCategories.exists(_.id == cat.id)
            if isFollowed then List("hidden")
            else List("bg-twitch-purple", "hover:bg-twitch-purple-dark", "text-white", "text-xs", "px-4", "py-1.5", "rounded-full", "transition-colors", "cursor-pointer", "w-full")
          },
          "Follow",
          onClick --> { _.foreach(_ =>
            state.get.flatMap { m =>
              if m.topGameIds.contains(cat.id) then
                state.update(_.copy(pendingPopularFollow = Some(cat)))
              else
                (ApiClient.postFollow(cat).flatMap(_ =>
                    ApiClient.fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats)))
                  )).start.void
            }
          )}
        )
      )
    )
