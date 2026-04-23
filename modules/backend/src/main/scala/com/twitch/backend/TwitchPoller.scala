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

  protected def buildAuthedRequest(baseUri: Uri, token: String, cursor: Option[String]): Request[IO] =
    import org.http4s.headers.Authorization
    import org.typelevel.ci.*
    val uriWithCursor = cursor.fold(baseUri)(c => baseUri.withQueryParam("after", c))
    Request[IO](method = Method.GET, uri = uriWithCursor).putHeaders(
      Authorization(Credentials.Token(AuthScheme.Bearer, token)),
      Header.Raw(ci"Client-Id", clientId)
    )

  protected def fetchPaginated[A](
      fetchPage: (String, Option[String]) => IO[PaginatedResponse[A]]
  )(token: String): IO[List[A]] =
    def go(cursor: Option[String], acc: List[A]): IO[List[A]] =
      fetchPage(token, cursor).flatMap { resp =>
        val newAcc = acc ::: resp.pageData
        resp.pageCursor match
          case Some(next) if resp.pageData.nonEmpty => go(Some(next), newAcc)
          case _ => IO.pure(newAcc)
      }
    go(None, Nil)

  trait PaginatedResponse[A]:
    def pageData: List[A]
    def pageCursor: Option[String]
