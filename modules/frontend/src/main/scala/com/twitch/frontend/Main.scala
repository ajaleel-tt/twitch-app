package com.twitch.frontend

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import cats.syntax.all.*
import fs2.concurrent.*
import fs2.dom.*
import com.twitch.frontend.components.*

object Main extends IOWebApp:

  def render: Resource[IO, HtmlDivElement[IO]] =
    for
      state <- SignallingRef[IO].of(Model()).toResource
      _ <- (
        ApiClient.fetchUser.flatMap(u => state.update(_.copy(user = u))),
        ApiClient.fetchConfig.flatMap(c => state.update(s => s.copy(twitchClientId = c.map(_.twitchClientId)))),
        ApiClient.fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats)))
      ).parTupled.toResource
      app <- appView(state)
    yield app

  private def appView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      styleAttr := "width: 100%; display: flex; flex-direction: column; align-items: center;",
      h1("Twitch App"),
      LoginSection.loginButton(state),
      LoginSection.statusBar(state),
      loggedInView(state)
    )

  private def loggedInView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      styleAttr <-- state.map { m =>
        val display = if m.user.isDefined then "flex" else "none"
        s"width: 100%; display: $display; flex-direction: column; align-items: center;"
      },
      h2(state.map(m => m.user.map(u => s"Welcome, ${u.display_name}!").getOrElse(""))),
      img(src <-- state.map(_.user.map(_.profile_image_url).getOrElse("")), styleAttr := "border-radius: 50%; width: 80px; margin-bottom: 10px;"),
      button(
        styleAttr := "background: #ff4646;",
        "Logout",
        onClick --> { _.foreach { _ =>
          ApiClient.postLogout *> IO(org.scalajs.dom.window.location.reload())
        }}
      ),
      hr(styleAttr := "width: 100%; margin: 20px 0;"),
      h3("Search Categories"),
      SearchSection.searchInput(state),
      SearchSection.searchResultsView(state),
      SearchSection.paginationView(state),
      hr(styleAttr := "width: 100%; margin: 20px 0;"),
      h3("Your Followed Categories"),
      FollowedSection.followedCategoriesView(state)
    )
