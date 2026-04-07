package com.twitch.frontend.components

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import fs2.concurrent.*
import fs2.dom.*
import com.twitch.frontend.Model

object LoginSection:

  def loginButton(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "flex flex-col items-center gap-4 py-8",
      children <-- state.map { m =>
        if m.user.isEmpty then
          List(button(
            cls := "bg-twitch-purple hover:bg-twitch-purple-dark text-white font-semibold py-3 px-8 rounded-lg text-lg shadow-lg hover:shadow-xl transition-all duration-200 cursor-pointer",
            "Login with Twitch",
            onClick --> { _.foreach(_ =>
              IO(org.scalajs.dom.window.location.href = "/auth/login")
            )}
          ))
        else Nil
      }
    )

  def statusBar(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      children <-- state.map { m =>
        m.status.toList.map(s => p(cls := "text-twitch-purple font-medium text-sm", s))
      }
    )
