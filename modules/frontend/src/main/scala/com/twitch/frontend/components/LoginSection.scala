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
      styleAttr := "margin-bottom: 20px;",
      children <-- state.map { m =>
        if m.user.isEmpty then
          List(button(
            styleAttr := "background: #9146ff;",
            "Login with Twitch",
            onClick --> { _.foreach { _ =>
              state.get.flatMap { s =>
                s.twitchClientId match
                  case Some(clientId) =>
                    val redirectUri = "http://localhost:8080/auth/callback"
                    val scope = "user:read:email"
                    val url = s"https://id.twitch.tv/oauth2/authorize?client_id=$clientId&redirect_uri=$redirectUri&response_type=code&scope=$scope"
                    IO(org.scalajs.dom.window.location.href = url)
                  case None =>
                    state.update(_.copy(status = Some("Error: Twitch Client ID not loaded yet")))
              }
            }}
          ))
        else Nil
      }
    )

  def statusBar(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      children <-- state.map { m =>
        m.status.toList.map(s => p(styleAttr := "font-weight: bold; color: #9146ff;", s))
      }
    )
