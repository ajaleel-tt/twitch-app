package com.twitch.frontend.components

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import fs2.concurrent.*
import fs2.dom.*
import com.twitch.frontend.{Model, ApiClient}
import com.twitch.core.*

object TagFiltersSection:

  def tagFiltersPanel(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "w-full mb-6",
      div(
        cls := "bg-twitch-dark-card border border-gray-800 rounded-xl p-4 flex flex-col gap-4",
        filterRow(state, "include", "Only show streams tagged:", "e.g. English",
          _.newIncludeTag, (m, v) => m.copy(newIncludeTag = v)),
        filterRow(state, "exclude", "Hide streams tagged:", "e.g. Speedrun",
          _.newExcludeTag, (m, v) => m.copy(newExcludeTag = v))
      )
    )

  private def filterRow(
      state: SignallingRef[IO, Model],
      filterType: String,
      label: String,
      placeholderText: String,
      getInputValue: Model => String,
      setInputValue: (Model, String) => Model
  ): Resource[IO, HtmlDivElement[IO]] =
    val pillColor = if filterType == "include" then "bg-twitch-purple" else "bg-twitch-danger"

    def addTag: IO[Unit] =
      state.get.flatMap { m =>
        val tag = getInputValue(m).trim
        if tag.isEmpty || tag.length > 25 then IO.unit
        else
          (ApiClient.addTagFilter(filterType, tag) *>
            ApiClient.fetchTagFilters.flatMap(filters =>
              state.update(s => setInputValue(s.copy(tagFilters = filters), ""))
            )).start.void
      }

    div(
      cls := "flex flex-col gap-2",
      p(cls := "text-sm text-gray-400 font-medium", label),
      // Tag pills
      div(
        cls := "flex flex-wrap gap-2",
        children <-- state.map { m =>
          m.tagFilters.filter(_.filterType == filterType).map { f =>
            tagPill(state, f, pillColor)
          }
        }
      ),
      // Input row
      div(
        cls := "flex gap-2",
        input.withSelf { self =>
          (
            typ := "text",
            placeholder := placeholderText,
            cls := "bg-twitch-dark border border-gray-700 text-white placeholder-gray-500 rounded-lg px-3 py-1.5 text-sm w-48 focus:outline-none focus:ring-2 focus:ring-twitch-purple focus:border-transparent transition-all",
            value <-- state.map(getInputValue),
            onInput --> { _.foreach(_ =>
              self.value.get.flatMap(v => state.update(m => setInputValue(m, v)))
            )},
            onKeyPress --> { _.foreach(e =>
              IO.whenA(e.key == "Enter")(addTag)
            )}
          )
        },
        button(
          cls := s"$pillColor hover:opacity-80 text-white text-sm px-3 py-1.5 rounded-lg transition-colors cursor-pointer",
          "Add",
          onClick --> { _.foreach(_ => addTag) }
        )
      )
    )

  private def tagPill(
      state: SignallingRef[IO, Model],
      filter: TagFilter,
      colorClass: String
  ): Resource[IO, HtmlSpanElement[IO]] =
    span(
      cls := s"$colorClass text-white text-xs px-2.5 py-1 rounded-full flex items-center gap-1.5",
      filter.tag,
      button(
        cls := "hover:text-gray-300 text-white font-bold cursor-pointer text-xs leading-none",
        "✕",
        onClick --> { _.foreach(_ =>
          (ApiClient.removeTagFilter(filter.filterType, filter.tag) *>
            ApiClient.fetchTagFilters.flatMap(filters =>
              state.update(_.copy(tagFilters = filters))
            )).start.void
        )}
      )
    )
