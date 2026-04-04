package com.twitch.frontend

import cats.effect.*
import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.dom.FetchClientBuilder
import org.http4s.{Request as Http4sRequest, Method, Uri, MediaType}
import org.http4s.headers.`Content-Type`
import org.scalajs.dom
import com.twitch.core.*

object ApiClient:

  private val httpClient = FetchClientBuilder[IO].create

  def fetchUser: IO[Option[TwitchUser]] =
    httpClient.expect[String](Uri.unsafeFromString("/api/user")).attempt.map {
      case Right(body) => decode[TwitchUser](body).toOption
      case Left(_)     => None
    }

  def fetchConfig: IO[Option[AppConfig]] =
    httpClient.expect[String](Uri.unsafeFromString("/api/config")).attempt.map {
      case Right(body) => decode[AppConfig](body).toOption
      case Left(_)     => None
    }

  def fetchFollowed: IO[List[TwitchCategory]] =
    httpClient.expect[String](Uri.unsafeFromString("/api/followed")).attempt.map {
      case Right(body) => decode[FollowedCategoriesResponse](body).map(_.categories).getOrElse(Nil)
      case Left(_)     => Nil
    }

  def searchCategories(query: String, after: Option[String] = None): IO[Option[TwitchSearchCategoriesResponse]] =
    val baseUri = Uri.unsafeFromString("/api/search/categories").withQueryParam("query", query)
    val uri = after.fold(baseUri)(c => baseUri.withQueryParam("after", c))
    httpClient.expect[String](uri).attempt.map {
      case Right(body) => decode[TwitchSearchCategoriesResponse](body).toOption
      case Left(_)     => None
    }

  def postFollow(cat: TwitchCategory): IO[Unit] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString("/api/follow"))
      .withEntity(FollowRequest(cat).asJson.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))
    httpClient.expect[String](req).void

  def postUnfollow(id: String): IO[Unit] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString(s"/api/unfollow/$id"))
    httpClient.expect[String](req).void

  def postLogout: IO[Unit] =
    val req = Http4sRequest[IO](Method.POST, Uri.unsafeFromString("/api/logout"))
    httpClient.expect[String](req).void.handleError(_ => ())

  def streamNotifications(onNotification: StreamNotification => IO[Unit]): IO[Nothing] =
    IO.async[Nothing] { cb =>
      IO {
        val es = new dom.EventSource("/api/notifications/stream")
        es.addEventListener("stream-live", (e: dom.MessageEvent) => {
          decode[StreamNotification](e.data.asInstanceOf[String]).foreach { n =>
            onNotification(n).unsafeRunAndForget()
          }
        })
        es.onerror = (_: dom.Event) => {
          cb(Left(new RuntimeException("SSE connection error")))
        }
        Some(IO(es.close()))
      }
    }
