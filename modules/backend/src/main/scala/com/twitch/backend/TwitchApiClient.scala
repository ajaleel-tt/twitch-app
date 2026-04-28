package com.twitch.backend

import cats.effect.IO
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.implicits.*
import org.http4s.headers.Authorization
import org.typelevel.ci.*
import com.twitch.core.*

class TwitchApiClient(
    clientId: String,
    clientSecret: String,
    client: Client[IO]
) extends TwitchApi:

  def searchCategories(query: String, after: Option[String], accessToken: String, pageSize: Int): IO[TwitchSearchCategoriesResponse] =
    val uri = uri"https://api.twitch.tv/helix/search/categories"
      .withQueryParam("query", query)
      .withQueryParam("first", pageSize.toString)
      .withOptionQueryParam("after", after)
    client.expect[TwitchSearchCategoriesResponse](authedRequest(uri, accessToken))

  def searchChannels(query: String, after: Option[String], accessToken: String, pageSize: Int): IO[TwitchSearchChannelsResponse] =
    val uri = uri"https://api.twitch.tv/helix/search/channels"
      .withQueryParam("query", query)
      .withQueryParam("first", pageSize.toString)
      .withOptionQueryParam("after", after)
    client.expect[TwitchSearchChannelsResponse](authedRequest(uri, accessToken))

  def getUser(accessToken: String): IO[TwitchUser] =
    val req = authedRequest(uri"https://api.twitch.tv/helix/users", accessToken)
    client.expect[TwitchUsersResponse](req).map(_.data.head)

  def exchangeCode(code: String, redirectUri: String): IO[TwitchTokenResponse] =
    val req = Request[IO](method = Method.POST, uri = uri"https://id.twitch.tv/oauth2/token").withEntity(
      UrlForm(
        "client_id" -> clientId,
        "client_secret" -> clientSecret,
        "code" -> code,
        "grant_type" -> "authorization_code",
        "redirect_uri" -> redirectUri
      )
    )
    client.run(req).use { resp =>
      if resp.status.isSuccess then resp.as[TwitchTokenResponse]
      else resp.bodyText.compile.string.flatMap { errorBody =>
        IO.raiseError(new RuntimeException(s"unexpected HTTP status: ${resp.status} for request POST https://id.twitch.tv/oauth2/token. Response body: $errorBody"))
      }
    }

  def refreshToken(refreshToken: String): IO[TwitchTokenResponse] =
    val req = Request[IO](method = Method.POST, uri = uri"https://id.twitch.tv/oauth2/token").withEntity(
      UrlForm(
        "client_id" -> clientId,
        "client_secret" -> clientSecret,
        "grant_type" -> "refresh_token",
        "refresh_token" -> refreshToken
      )
    )
    client.run(req).use { resp =>
      if resp.status.isSuccess then resp.as[TwitchTokenResponse]
      else IO.raiseError(new RuntimeException(s"Token refresh failed: ${resp.status}"))
    }

  private def authedRequest(uri: Uri, accessToken: String): Request[IO] =
    Request[IO](method = Method.GET, uri = uri).putHeaders(
      Authorization(Credentials.Token(AuthScheme.Bearer, accessToken)),
      Header.Raw(ci"Client-Id", clientId)
    )
