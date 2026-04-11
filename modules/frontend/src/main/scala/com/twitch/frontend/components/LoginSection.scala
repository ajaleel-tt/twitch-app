package com.twitch.frontend.components

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import fs2.concurrent.*
import fs2.dom.*
import com.twitch.frontend.Model

object LoginSection:

  def welcomePage(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls <-- state.map { m =>
        if m.user.isEmpty then List("flex", "flex-col", "items-center", "w-full")
        else List("hidden")
      },
      // Hero section
      div(
        cls := "flex flex-col items-center gap-8 py-20 px-8 text-center w-full max-w-2xl mx-auto",
        // App icon
        div(
          cls := "w-24 h-24 bg-twitch-purple rounded-3xl flex items-center justify-center shadow-xl",
          span(cls := "text-5xl text-white font-bold", "T")
        ),
        // Title and description
        div(
          cls := "flex flex-col items-center gap-4",
          h2(cls := "text-5xl font-extrabold text-white tracking-tight", "Twitch Category Tracker"),
          p(
            cls := "text-xl text-gray-400 leading-relaxed max-w-md",
            "Follow your favorite Twitch categories and get notified the moment streamers go live. Never miss a stream again."
          )
        ),
        // Feature highlights
        div(
          cls := "flex flex-row flex-wrap justify-center gap-3 mt-2",
          featureChip("Search & follow categories"),
          featureChip("Real-time notifications"),
          featureChip("Filter by tags")
        ),
        // Login button
        div(
          cls := "flex flex-col items-center gap-3 mt-4",
          button(
            cls := "bg-twitch-purple hover:bg-twitch-purple-dark text-white font-bold py-4 px-12 rounded-xl text-xl shadow-xl hover:shadow-2xl transition-all duration-200 cursor-pointer",
            "Login with Twitch",
            onClick --> { _.foreach(_ =>
              IO(org.scalajs.dom.window.location.href = "/auth/login")
            )}
          ),
          p(cls := "text-sm text-gray-500", "Sign in with your Twitch account to get started")
        )
      ),
      // Contact / feedback section
      div(
        cls := "w-full border-t border-gray-800 mt-4 py-10 px-8",
        div(
          cls := "flex flex-col items-center gap-5 max-w-lg mx-auto text-center",
          p(cls := "text-xs font-semibold text-gray-500 uppercase tracking-widest", "Get in touch"),
          div(
            cls := "flex flex-row flex-wrap justify-center gap-3",
            contactLink("Discord", "https://discord.gg/pk2McPvsb9"),
            contactLink("Twitch", "https://twitch.tv/aabil11"),
            contactLink("Email", "mailto:aabil11@twitchnotify.com")
          )
        )
      )
    )

  private def featureChip(text: String): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "bg-twitch-dark-card border border-gray-700 text-gray-300 text-sm font-medium px-5 py-2.5 rounded-full",
      text
    )

  private def contactLink(label: String, url: String): Resource[IO, HtmlDivElement[IO]] =
    div(
      a(
        cls := "text-sm text-gray-400 hover:text-twitch-purple transition-colors px-5 py-2.5 rounded-lg border border-gray-700 hover:border-twitch-purple inline-block",
        href := url,
        target := "_blank",
        label
      )
    )

  def statusBar(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      children <-- state.map { m =>
        m.status.toList.map(s => p(cls := "text-twitch-purple font-medium text-sm", s))
      }
    )
