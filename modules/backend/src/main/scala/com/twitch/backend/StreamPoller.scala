package com.twitch.backend

import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*
import cats.effect.implicits.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.typelevel.ci.*
import java.time.Instant
import com.twitch.core.*

class StreamPoller(
    clientId: String,
    clientSecret: String,
    client: Client[IO],
    db: Database,
    notificationQueues: Ref[IO, Map[String, (String, Queue[IO, StreamNotification])]],
    appToken: Ref[IO, Option[String]],
    notifiedStreamIds: Ref[IO, Set[String]],
    settings: AppSettings
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

  private def withTokenRefresh[A](f: String => IO[A]): IO[A] =
    getOrRefreshToken.flatMap(f).handleErrorWith { err =>
      appToken.set(None) *> getOrRefreshToken.flatMap(f)
    }

  private def fetchStreamsPage(token: String, categoryId: String, cursor: Option[String]): IO[TwitchStreamsResponse] =
    val baseUri = uri"https://api.twitch.tv/helix/streams"
      .withQueryParam("game_id", categoryId)
      .withQueryParam("first", settings.streamsPageSize.toString)
    val uriWithCursor = cursor.fold(baseUri)(c => baseUri.withQueryParam("after", c))
    val req = Request[IO](method = Method.GET, uri = uriWithCursor).putHeaders(
      Authorization(Credentials.Token(AuthScheme.Bearer, token)),
      Header.Raw(ci"Client-Id", clientId)
    )
    client.expect[TwitchStreamsResponse](req)

  private def fetchLiveStreams(token: String, categoryIds: List[String]): IO[List[TwitchStream]] =
    categoryIds.parTraverseN(settings.parallelCategories) { categoryId =>
      def go(cursor: Option[String], acc: List[TwitchStream]): IO[List[TwitchStream]] =
        fetchStreamsPage(token, categoryId, cursor).flatMap { resp =>
          val newAcc = resp.data.reverse ::: acc
          resp.pagination.flatMap(_.cursor) match
            case Some(next) if resp.data.nonEmpty => go(Some(next), newAcc)
            case _ => IO.pure(newAcc.reverse)
        }
      go(None, Nil)
    }.map(_.flatten)

  private def broadcastNotifications(notifications: List[StreamNotification]): IO[Unit] =
    for
      queues <- notificationQueues.get
      userIds = queues.values.map(_._1).toSet
      followedByUser <- userIds.toList.traverse(uid => db.getFollowed(uid).map(cats => uid -> cats.map(_.id).toSet))
      followedMap = followedByUser.toMap
      filtersByUser <- userIds.toList.traverse(uid => db.getTagFilters(uid).map(filters => uid -> filters))
      filtersMap = filtersByUser.toMap
      byCategoryId = notifications.groupBy(_.categoryId)
      _ <- queues.values.toList.traverse_ { case (userId, queue) =>
        val userCategoryIds = followedMap.getOrElse(userId, Set.empty)
        val relevantNotifications = userCategoryIds.toList.flatMap(id => byCategoryId.getOrElse(id, Nil))
        val filtered = StreamLogic.applyTagFilters(relevantNotifications, filtersMap.getOrElse(userId, Nil))
        filtered.traverse_(queue.offer)
      }
    yield ()

  private def recentlyWentLive(s: TwitchStream, now: Instant): Boolean =
    StreamLogic.recentlyWentLive(s, now, settings.recentlyLiveWindow)

  // First poll seeds the set without sending notifications so we don't
  // flood the user with every stream that happens to be live at startup.
  private def seedOnce: IO[Unit] =
    for
      allCategories <- db.getAllFollowedCategories
      _ <- IO.whenA(allCategories.nonEmpty) {
        for
          streams <- withTokenRefresh(token => fetchLiveStreams(token, allCategories.map(_.id)))
          liveIds = streams.iterator.filter(_.`type` == "live").map(_.id).toSet
          _ <- notifiedStreamIds.set(liveIds)
          _ <- IO.println(s"Poller: seeded ${liveIds.size} already-live streams across ${allCategories.size} categories")
        yield ()
      }
    yield ()

  private def pollOnce: IO[Unit] =
    for
      allCategories <- db.getAllFollowedCategories
      _ <- IO.whenA(allCategories.nonEmpty) {
        for
          streams <- withTokenRefresh(token => fetchLiveStreams(token, allCategories.map(_.id)))
          now <- IO(Instant.now())
          recentStreams = streams.filter(recentlyWentLive(_, now))
          alreadyNotified <- notifiedStreamIds.get
          newStreams = recentStreams.filter(s => !alreadyNotified.contains(s.id))
          // Accumulate all seen stream IDs so that API pagination flicker
          // between polls can never cause a re-notification.
          _ <- notifiedStreamIds.set(alreadyNotified ++ streams.iterator.map(_.id).toSet)
          _ <- IO.println(s"Poller: fetched ${streams.size} total streams across ${allCategories.size} categories, ${recentStreams.size} recently live, ${newStreams.size} new")
          _ <- IO.whenA(newStreams.nonEmpty) {
            broadcastNotifications(newStreams.map(StreamLogic.toNotification))
          }
        yield ()
      }
    yield ()

  def start: IO[Nothing] =
    IO.println(s"StreamPoller: starting (polling every ${settings.pollerInterval.toSeconds}s)") *>
      seedOnce.handleErrorWith(e => IO.println(s"StreamPoller seed error: $e")) *>
      (IO.sleep(settings.pollerInterval) *> pollOnce.handleErrorWith(e => IO.println(s"StreamPoller error: $e"))).foreverM

object StreamPoller:
  def make(
      clientId: String,
      clientSecret: String,
      client: Client[IO],
      db: Database,
      notificationQueues: Ref[IO, Map[String, (String, Queue[IO, StreamNotification])]],
      settings: AppSettings
  ): IO[StreamPoller] =
    for
      tokenRef <- IO.ref(Option.empty[String])
      notifiedRef <- IO.ref(Set.empty[String])
    yield new StreamPoller(clientId, clientSecret, client, db, notificationQueues, tokenRef, notifiedRef, settings)
