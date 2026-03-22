package com.twitch.frontend

import calico.*
import calico.html.io.{*, given}
import cats.effect.*
import cats.syntax.all.*
import fs2.concurrent.*
import fs2.dom.*
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.dom.FetchClientBuilder
import org.http4s.{Request as Http4sRequest, Method, Uri, MediaType}
import org.http4s.headers.`Content-Type`
import com.twitch.core.*

case class Model(
    status: Option[String] = None,
    user: Option[TwitchUser] = None,
    twitchClientId: Option[String] = None,
    searchQuery: String = "",
    searchResults: List[TwitchCategory] = Nil,
    selectedCategoryIds: Set[String] = Set.empty,
    followedCategories: List[TwitchCategory] = Nil,
    paginationCursor: Option[String] = None,
    currentPage: Int = 0,
    pageSize: Int = 5
)

object Main extends IOWebApp:

  private val httpClient = FetchClientBuilder[IO].create

  private def fetchUser: IO[Option[TwitchUser]] =
    httpClient.expect[String](Uri.unsafeFromString("/api/user")).attempt.map {
      case Right(body) => decode[TwitchUser](body).toOption
      case Left(_)     => None
    }

  private def fetchConfig: IO[Option[AppConfig]] =
    httpClient.expect[String](Uri.unsafeFromString("/api/config")).attempt.map {
      case Right(body) => decode[AppConfig](body).toOption
      case Left(_)     => None
    }

  private def fetchFollowed: IO[List[TwitchCategory]] =
    httpClient.expect[String](Uri.unsafeFromString("/api/followed")).attempt.map {
      case Right(body) => decode[FollowedCategoriesResponse](body).map(_.categories).getOrElse(Nil)
      case Left(_)     => Nil
    }

  private def searchCategories(query: String, after: Option[String] = None): IO[Option[TwitchSearchCategoriesResponse]] =
    val baseUri = Uri.unsafeFromString("/api/search/categories").withQueryParam("query", query)
    val uri = after.fold(baseUri)(c => baseUri.withQueryParam("after", c))
    httpClient.expect[String](uri).attempt.map {
      case Right(body) => decode[TwitchSearchCategoriesResponse](body).toOption
      case Left(_)     => None
    }

  private def postFollow(cat: TwitchCategory): IO[Boolean] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString("/api/follow"))
      .withEntity(FollowRequest(cat).asJson.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))
    httpClient.successful(req)

  private def postUnfollow(id: String): IO[Boolean] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString(s"/api/unfollow/$id"))
    httpClient.successful(req)

  private def postLogout: IO[Unit] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString("/api/logout"))
    httpClient.expect[String](req).void.handleError(_ => ())

  def render: Resource[IO, HtmlDivElement[IO]] =
    for
      state <- SignallingRef[IO].of(Model()).toResource
      _ <- (
        fetchUser.flatMap(u => state.update(_.copy(user = u))),
        fetchConfig.flatMap(c => state.update(s => s.copy(twitchClientId = c.map(_.twitchClientId)))),
        fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats)))
      ).parTupled.toResource
      app <- appView(state)
    yield app

  private def appView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      styleAttr := "width: 100%; display: flex; flex-direction: column; align-items: center;",
      h1("Twitch App"),
      loginButton(state),
      statusBar(state),
      loggedInView(state)
    )

  private def loginButton(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
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

  private def statusBar(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      children <-- state.map { m =>
        m.status.toList.map(s => p(styleAttr := "font-weight: bold; color: #9146ff;", s))
      }
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
          postLogout *> IO(org.scalajs.dom.window.location.reload())
        }}
      ),
      hr(styleAttr := "width: 100%; margin: 20px 0;"),
      h3("Search Categories"),
      searchSection(state),
      searchResultsView(state),
      paginationView(state),
      hr(styleAttr := "width: 100%; margin: 20px 0;"),
      h3("Your Followed Categories"),
      followedCategoriesView(state)
    )

  private def searchSection(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
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
                searchCategories(m.searchQuery).flatMap {
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

  private def searchResultsView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
    div(
      styleAttr := "display: flex; flex-wrap: wrap; justify-content: center; margin-top: 10px;",
      children <-- state.map { m =>
        val paginatedResults = m.searchResults.slice(m.currentPage * m.pageSize, (m.currentPage + 1) * m.pageSize)
        paginatedResults.map(cat => categoryCard(state, m, cat))
      }
    )

  private def categoryCard(state: SignallingRef[IO, Model], model: Model, cat: TwitchCategory): Resource[IO, HtmlDivElement[IO]] =
    val isSelected = model.selectedCategoryIds.contains(cat.id)
    val isFollowed = model.followedCategories.exists(_.id == cat.id)
    val boxArtUrl = cat.box_art_url.replace("{width}", "140").replace("{height}", "185")
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
              postUnfollow(cat.id).flatMap(_ =>
                fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats, status = None)))
              )
          )}
        )
      else
        button(
          styleAttr := "background: #9146ff; margin-top: 5px; padding: 5px 10px;",
          "Follow",
          onClick --> { _.foreach(_ =>
            state.update(_.copy(status = Some(s"Following ${cat.name}..."))) *>
              postFollow(cat).flatMap(_ =>
                fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats, status = None)))
              )
          )}
        )
    )

  private def paginationView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
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
                searchCategories(s.searchQuery, s.paginationCursor).flatMap {
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

  private def followedCategoriesView(state: SignallingRef[IO, Model]): Resource[IO, HtmlDivElement[IO]] =
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
    val boxArtUrl = cat.box_art_url.replace("{width}", "70").replace("{height}", "92")
    div(
      styleAttr := "margin: 10px; padding: 10px; border: 1px solid #ddd; border-radius: 8px; width: 120px; background: white; display: flex; flex-direction: column; align-items: center;",
      img(src := boxArtUrl, styleAttr := "width: 70px; height: 92px; border-radius: 4px;"),
      p(styleAttr := "font-size: 0.7rem; font-weight: bold; margin: 5px 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; width: 100%; text-align: center;", cat.name),
      button(
        styleAttr := "background: #ff4646; font-size: 0.7rem; padding: 2px 5px;",
        "Unfollow",
        onClick --> { _.foreach(_ =>
          state.update(_.copy(status = Some("Unfollowing..."))) *>
            postUnfollow(cat.id).flatMap(_ =>
              fetchFollowed.flatMap(cats => state.update(_.copy(followedCategories = cats, status = None)))
            )
        )}
      )
    )
