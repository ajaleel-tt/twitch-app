package com.twitch.backend

import cats.effect.*
import com.twitch.core.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.implicits.*

abstract class TwitchPoller(
    protected val clientId: String,
    protected val clientSecret: String,
    protected val client: Client[IO],
    protected val appToken: Ref[IO, Option[String]]
):

  private def fetchAppToken: IO[String] =
    val req = Request[IO](method = Method.POST, uri = uri"https://id.twitch.tv/oauth2/token").withEntity(
      UrlForm(
        "client_id" -> clientId,
        "client_secret" -> clientSecret,
        "grant_type" -> "client_credentials"
      )
    )
    client.run(req).use { resp =>
      if resp.status.isSuccess then
        resp.as[TwitchTokenResponse].map(_.access_token)
      else
        resp.bodyText.compile.string.flatMap { body =>
          IO.raiseError(new RuntimeException(s"Failed to get app token: ${resp.status} $body"))
        }
    }

  private def getOrRefreshToken: IO[String] =
    appToken.get.flatMap {
      case Some(t) => IO.pure(t)
      case None    => fetchAppToken.flatTap(t => appToken.set(Some(t)))
    }

  protected def withTokenRefresh[A](f: String => IO[A]): IO[A] =
    getOrRefreshToken.flatMap(f).handleErrorWith { _ =>
      appToken.set(None) *> getOrRefreshToken.flatMap(f)
    }
