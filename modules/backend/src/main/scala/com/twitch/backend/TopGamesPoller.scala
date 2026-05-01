package com.twitch.backend

import scala.concurrent.duration.*

import cats.effect.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.implicits.*

import com.twitch.backend.db.TopGamesRepository
import com.twitch.core.{TwitchCategory, TwitchSearchCategoriesResponse}

class TopGamesPoller(
  appToken: Ref[IO, Option[AppAccessToken]],
  client: Client[IO],
  clientId: String,
  clientSecret: String,
  settings: AppSettings,
  topGamesRepo: TopGamesRepository,
) extends TwitchPoller(clientId, clientSecret, client, appToken) {

  private def fetchTopGamesPage(
    cursor: Option[String],
  )(using AppAccessToken): IO[TwitchSearchCategoriesResponse] = {
    val baseUri = uri"https://api.twitch.tv/helix/games/top"
      .withQueryParam("first", "100")
    client.expect[TwitchSearchCategoriesResponse](buildAuthedRequest(baseUri, cursor))
  }

  private def fetchAllTopGames(using AppAccessToken): IO[List[TwitchCategory]] =
    fetchPaginated[TwitchCategory](fetchTopGamesPage, limit = settings.topGamesCount)

  private def pollOnce: IO[Unit] =
    for {
      games <- withTokenRefresh(fetchAllTopGames)
      unique = games.distinctBy(_.id)
      _ <- topGamesRepo.replaceTopGames(unique)
      _ <- IO.println(s"TopGamesPoller: stored ${unique.size} top games")
    } yield ()

  def start: IO[Nothing] =
    IO.println(
      s"TopGamesPoller: starting (polling every ${settings.topGamesPollInterval.toSeconds}s)",
    ) *>
      pollOnce.handleErrorWith(e =>
        IO.println(s"TopGamesPoller first poll failed: $e, retrying in 30s") *>
          IO.sleep(30.seconds) *>
          pollOnce.handleErrorWith(e2 => IO.println(s"TopGamesPoller retry also failed: $e2")),
      ) *>
      (IO.sleep(settings.topGamesPollInterval) *> pollOnce.handleErrorWith(e =>
        IO.println(s"TopGamesPoller error: $e"),
      )).foreverM

}

object TopGamesPoller {

  def make(
    client: Client[IO],
    clientId: String,
    clientSecret: String,
    settings: AppSettings,
    topGamesRepo: TopGamesRepository,
  ): IO[TopGamesPoller] =
    for tokenRef <- IO.ref(Option.empty[AppAccessToken])
    yield new TopGamesPoller(
      appToken = tokenRef,
      client = client,
      clientId = clientId,
      clientSecret = clientSecret,
      settings = settings,
      topGamesRepo = topGamesRepo,
    )

}
