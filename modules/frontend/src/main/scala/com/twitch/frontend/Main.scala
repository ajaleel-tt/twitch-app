package com.twitch.frontend

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import cats.syntax.all.*
import fs2.concurrent.*
import fs2.dom.*
import org.scalajs.dom
import com.twitch.core.*
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
      _ <- startNotificationStream(state).background
      app <- appView(state)
    yield app

  private def requestNotificationPermission: IO[Unit] =
    IO {
      if dom.Notification.permission == "default" then
        dom.Notification.requestPermission((_: String) => ())
    }

  private def fireBrowserNotification(n: StreamNotification): IO[Unit] =
    IO {
      if dom.Notification.permission == "granted" then
        val notification = new dom.Notification(
          s"${n.streamerName} is live playing ${n.categoryName}!",
          new dom.NotificationOptions {
            body = s"${n.categoryName}: ${n.streamTitle}"
            icon = n.thumbnailUrl
          }
        )
        notification.onclick = (_: dom.Event) => {
          val _ = dom.window.open(s"https://twitch.tv/${n.streamerLogin}", "_blank")
          notification.close()
        }
    }

  private def startNotificationStream(state: SignallingRef[IO, Model]): IO[Unit] =
    state.discrete
      .filter(_.user.isDefined)
      .take(1)
      .evalMap { _ =>
        requestNotificationPermission *>
          ApiClient.streamNotifications { n =>
            state.update(m => m.copy(notifications = (n :: m.notifications).take(50))) *>
              fireBrowserNotification(n)
          }
      }
      .compile.drain
      .handleErrorWith(e => IO.println(s"Notification stream error: $e"))

  private def appView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls := "min-h-screen bg-twitch-dark flex flex-col items-center",
      // Header bar
      div(
        cls := "w-full bg-twitch-dark-card border-b border-gray-800 px-6 py-4 flex items-center justify-center",
        h1(cls := "text-2xl font-bold text-white tracking-tight", "Twitch App")
      ),
      // Main content
      div(
        cls := "w-full max-w-5xl mx-auto px-4 py-8 flex flex-col items-center gap-6",
        LoginSection.loginButton(state),
        LoginSection.statusBar(state),
        loggedInView(state)
      )
    )

  private def loggedInView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      cls <-- state.map { m =>
        if m.user.isDefined then List("w-full", "flex", "flex-col", "items-center", "gap-8")
        else List("hidden")
      },
      // User profile
      div(
        cls := "flex flex-col items-center gap-3",
        h2(
          cls := "text-xl font-semibold text-white",
          state.map(m => m.user.map(u => s"Welcome, ${u.display_name}!").getOrElse(""))
        ),
        img(
          src <-- state.map(_.user.map(_.profile_image_url).getOrElse("")),
          cls := "rounded-full w-20 h-20 ring-2 ring-twitch-purple"
        ),
        button(
          cls := "bg-twitch-danger hover:bg-red-600 text-white font-medium px-4 py-2 rounded-lg transition-colors cursor-pointer",
          "Logout",
          onClick --> { _.foreach { _ =>
            ApiClient.postLogout *> IO(org.scalajs.dom.window.location.reload())
          }}
        )
      ),
      // Search section
      div(
        cls := "w-full border-t border-gray-800 pt-8",
        h3(cls := "text-lg font-bold text-white mb-4 text-center", "Search Categories"),
        SearchSection.searchInput(state),
        SearchSection.searchResultsView(state),
        SearchSection.paginationView(state)
      ),
      // Live Now section
      div(
        cls := "w-full border-t border-gray-800 pt-8",
        h3(cls := "text-lg font-bold text-white mb-4 text-center", "Live Now in Your Followed Categories"),
        NotificationsSection.notificationsView(state)
      ),
      // Followed Categories section
      div(
        cls := "w-full border-t border-gray-800 pt-8",
        h3(cls := "text-lg font-bold text-white mb-4 text-center", "Your Followed Categories"),
        FollowedSection.followedCategoriesView(state)
      )
    )
