package com.twitch.backend

import cats.effect.*
import com.twitch.core.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.typelevel.ci.*

class TopGamesPoller(
    clientId: String,
    clientSecret: String,
    client: Client[IO],
    db: Database,
    appToken: Ref[IO, Option[String]],
    settings: AppSettings
) extends TwitchPoller(clientId, clientSecret, client, appToken):

  private def fetchTopGamesPage(token: String, cursor: Option[String]): IO[TwitchSearchCategoriesResponse] =
    val baseUri = uri"https://api.twitch.tv/helix/games/top"
      .withQueryParam("first", "100")
    val uriWithCursor = cursor.fold(baseUri)(c => baseUri.withQueryParam("after", c))
    val req = Request[IO](method = Method.GET, uri = uriWithCursor).putHeaders(
      Authorization(Credentials.Token(AuthScheme.Bearer, token)),
      Header.Raw(ci"Client-Id", clientId)
    )
    client.expect[TwitchSearchCategoriesResponse](req)

  private def fetchAllTopGames(token: String): IO[List[TwitchCategory]] =
    val target = settings.topGamesCount
    def go(cursor: Option[String], acc: List[TwitchCategory]): IO[List[TwitchCategory]] =
      if acc.size >= target then IO.pure(acc.take(target))
      else
        fetchTopGamesPage(token, cursor).flatMap { resp =>
          val newAcc = acc ::: resp.data
          if newAcc.size >= target then IO.pure(newAcc.take(target))
          else
            resp.pagination.flatMap(_.cursor) match
              case Some(next) if resp.data.nonEmpty => go(Some(next), newAcc)
              case _ => IO.pure(newAcc)
        }
    go(None, Nil)

  private def pollOnce: IO[Unit] =
    for
      games <- withTokenRefresh(fetchAllTopGames)
      unique = games.distinctBy(_.id)
      _ <- db.replaceTopGames(unique)
      _ <- IO.println(s"TopGamesPoller: stored ${unique.size} top games")
    yield ()

  def start: IO[Nothing] =
    IO.println(s"TopGamesPoller: starting (polling every ${settings.topGamesPollInterval.toSeconds}s)") *>
      pollOnce.handleErrorWith(e => IO.println(s"TopGamesPoller error: $e")) *>
      (IO.sleep(settings.topGamesPollInterval) *> pollOnce.handleErrorWith(e => IO.println(s"TopGamesPoller error: $e"))).foreverM

object TopGamesPoller:
  def make(
      clientId: String,
      clientSecret: String,
      client: Client[IO],
      db: Database,
      settings: AppSettings
  ): IO[TopGamesPoller] =
    for
      tokenRef <- IO.ref(Option.empty[String])
    yield new TopGamesPoller(clientId, clientSecret, client, db, tokenRef, settings)
