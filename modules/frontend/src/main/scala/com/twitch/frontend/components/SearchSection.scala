package com.twitch.frontend.components

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import fs2.concurrent.*
import fs2.dom.*
import com.twitch.frontend.{Model, ApiClient}
import com.twitch.core.*

object SearchSection:

  def searchInput(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      styleAttr := "display: flex; gap: 10px; justify-content: center; margin-bottom: 20px;",
      input.withSelf { self =>
        (
          typ := "text",
          placeholder := "Search for a category...",
          styleAttr := "padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; width: 250px;",
          value <-- state.map(_.searchQuery),
          onInput --> { _.foreach(_ => self.value.get.flatMap(q => state.update(_.copy(searchQuery = q)))) }
        )
      },
      button(
        "Search",
        onClick --> { _.foreach { _ =>
          state.get.flatMap { m =>
            if m.searchQuery.trim.isEmpty then IO.unit
            else
              state.update(_.copy(status = Some("Searching..."), searchResults = Nil, paginationCursor = None, currentPage = 0)) *>
                ApiClient.searchCategories(m.searchQuery).flatMap {
                  case Some(res) =>
                    state.update(_.copy(
                      searchResults = res.data,
                      paginationCursor = res.pagination.flatMap(_.cursor),
                      status = None
                    ))
                  case None =>
                    state.update(_.copy(status = Some("Error: Search failed")))
                }
          }
        }}
      )
    )

  def searchResultsView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      styleAttr := "display: flex; flex-wrap: wrap; justify-content: center; margin-top: 10px;",
      children <-- state.map { m =>
        val paginatedResults = m.searchResults.slice(m.currentPage * m.pageSize, (m.currentPage + 1) * m.pageSize)
        paginatedResults.map(cat => categoryCard(state, m, cat))
      }
    )

  def paginationView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      styleAttr <-- state.map { m =>
        val display = if m.searchResults.nonEmpty then "flex" else "none"
        s"display: $display; flex-direction: column; align-items: center; margin-top: 20px;"
      },
      div(
        styleAttr := "display: flex; gap: 10px; align-items: center;",
        button(
          styleAttr := "padding: 5px 15px;",
          "Previous",
          disabled <-- state.map(_.currentPage == 0),
          onClick --> { _.foreach(_ => state.update(s => s.copy(currentPage = s.currentPage - 1))) }
        ),
        span(state.map { m =>
          val totalLocalPages = Math.max(1, (m.searchResults.size + m.pageSize - 1) / m.pageSize)
          s"Page ${m.currentPage + 1} of $totalLocalPages"
        }),
        button(
          styleAttr := "padding: 5px 15px;",
          "Next",
          disabled <-- state.map { m =>
            val totalLocalPages = (m.searchResults.size + m.pageSize - 1) / m.pageSize
            m.currentPage >= totalLocalPages - 1
          },
          onClick --> { _.foreach(_ => state.update(s => s.copy(currentPage = s.currentPage + 1))) }
        )
      ),
      button(
        styleAttr <-- state.map { m =>
          val display = if m.paginationCursor.isDefined then "inline-block" else "none"
          s"display: $display; margin-top: 10px; background: #9146ff; padding: 5px 15px;"
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

  private def categoryCard(state: SignallingRef[IO, Model], model: Model, cat: TwitchCategory): Resource[IO, HtmlDivElement[IO]] =
    val isSelected = model.selectedCategoryIds.contains(cat.id)
    val isFollowed = model.followedCategories.exists(_.id == cat.id)
    val boxArtUrl = cat.box_art_url
      .replace("{width}", "280").replace("{height}", "370")
      .replaceAll("""-(\d+)x(\d+)\.""", "-280x370.")
    div(
      styleAttr := s"margin: 10px; padding: 10px; border: ${if isSelected then "2px solid #9146ff" else "1px solid #ddd"}; border-radius: 8px; width: 160px; background: ${if isSelected then "#f0e6ff" else "white"}; display: flex; flex-direction: column; align-items: center;",
      div(
        styleAttr := "cursor: pointer; display: flex; flex-direction: column; align-items: center;",
        onClick --> { _.foreach(_ => state.update(m =>
          val newSel = if m.selectedCategoryIds.contains(cat.id) then m.selectedCategoryIds - cat.id else m.selectedCategoryIds + cat.id
          m.copy(selectedCategoryIds = newSel)
        )) },
        img(src := boxArtUrl, styleAttr := "width: 140px; height: 185px; border-radius: 4px;"),
        p(styleAttr := "font-size: 0.9rem; font-weight: bold; margin: 5px 0; text-align: center;", cat.name)
      ),
      if isFollowed then
        button(
          styleAttr := "background: #ff4646; margin-top: 5px; padding: 5px 10px;",
          "Unfollow",
          onClick --> { _.foreach(_ =>
            state.update(_.copy(status = Some("Unfollowing..."))) *>
              ApiClient.postUnfollow(cat.id).flatMap(_ =>
                ApiClient.fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats, status = None)))
              )
          )}
        )
      else
        button(
          styleAttr := "background: #9146ff; margin-top: 5px; padding: 5px 10px;",
          "Follow",
          onClick --> { _.foreach(_ =>
            state.update(_.copy(status = Some(s"Following ${cat.name}..."))) *>
              ApiClient.postFollow(cat).flatMap(_ =>
                ApiClient.fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats, status = None)))
              )
          )}
        )
    )
