package com.twitch.backend

import scala.concurrent.duration.*

import java.time.Instant

import munit.FunSuite

import com.twitch.core.*

class StreamLogicSpec extends FunSuite {

  // ── Test fixtures ──────────────────────────────────────────────────

  private def mkStream(
    id: String = "123",
    userId: String = "user1",
    userLogin: String = "streamer1",
    userName: String = "Streamer One",
    gameId: String = "game1",
    gameName: String = "Some Game",
    tpe: String = "live",
    title: String = "Playing games",
    viewerCount: Int = 100,
    startedAt: String = "2024-01-01T12:00:00Z",
    thumbnailUrl: String = "https://twitch.tv/thumb-{width}x{height}.jpg",
    tags: Option[List[String]] = None,
  ): TwitchStream =
    TwitchStream(
      id,
      userId,
      userLogin,
      userName,
      gameId,
      gameName,
      tpe,
      title,
      viewerCount,
      startedAt,
      thumbnailUrl,
      tags,
    )

  private def mkNotification(
    categoryId: String = "game1",
    tags: List[String] = Nil,
  ): StreamNotification =
    StreamNotification(
      categoryId = categoryId,
      categoryName = "Some Game",
      streamerId = "user1",
      streamerLogin = "streamer1",
      streamerName = "Streamer One",
      streamTitle = "Playing games",
      tags = tags,
      thumbnailUrl = "thumb.jpg",
      viewerCount = 100,
    )

  // ── toNotification ─────────────────────────────────────────────────

  test("toNotification maps all fields correctly") {
    val stream = mkStream(
      id = "s1",
      userId = "u1",
      userLogin = "login1",
      userName = "Name1",
      gameId = "g1",
      gameName = "Game1",
      title = "My Title",
      viewerCount = 42,
      thumbnailUrl = "https://example.com/thumb-{width}x{height}.jpg",
      tags = Some(List("English", "FPS")),
    )
    val n = StreamLogic.toNotification(stream)
    assertEquals(n.categoryId, "g1")
    assertEquals(n.categoryName, "Game1")
    assertEquals(n.streamerId, "u1")
    assertEquals(n.streamerLogin, "login1")
    assertEquals(n.streamerName, "Name1")
    assertEquals(n.streamTitle, "My Title")
    assertEquals(n.viewerCount, 42)
    assertEquals(n.thumbnailUrl, "https://example.com/thumb-320x180.jpg")
    assertEquals(n.tags, List("English", "FPS"))
  }

  test("toNotification replaces width and height placeholders") {
    val stream = mkStream(thumbnailUrl = "https://cdn.twitch.tv/preview-{width}x{height}.jpg")
    val n = StreamLogic.toNotification(stream)
    assertEquals(n.thumbnailUrl, "https://cdn.twitch.tv/preview-320x180.jpg")
  }

  test("toNotification converts None tags to Nil") {
    val stream = mkStream(tags = None)
    assertEquals(StreamLogic.toNotification(stream).tags, Nil)
  }

  test("toNotification converts Some(tags) to list") {
    val stream = mkStream(tags = Some(List("English")))
    assertEquals(StreamLogic.toNotification(stream).tags, List("English"))
  }

  // ── recentlyWentLive ──────────────────────────────────────────────

  private val window = 5.minutes
  private val now = Instant.parse("2024-01-01T12:10:00Z")

  test("recentlyWentLive: stream started 4 minutes ago is recent") {
    val stream = mkStream(startedAt = "2024-01-01T12:06:01Z")
    assert(StreamLogic.recentlyWentLive(stream, now, window))
  }

  test("recentlyWentLive: stream started exactly 5 minutes ago is NOT recent") {
    val stream = mkStream(startedAt = "2024-01-01T12:05:00Z")
    assert(!StreamLogic.recentlyWentLive(stream, now, window))
  }

  test("recentlyWentLive: stream started 6 minutes ago is NOT recent") {
    val stream = mkStream(startedAt = "2024-01-01T12:04:00Z")
    assert(!StreamLogic.recentlyWentLive(stream, now, window))
  }

  test("recentlyWentLive: stream started 0 seconds ago is recent") {
    val stream = mkStream(startedAt = "2024-01-01T12:10:00Z")
    assert(StreamLogic.recentlyWentLive(stream, now, window))
  }

  test("recentlyWentLive: non-live stream is never recent") {
    val stream = mkStream(tpe = "vodcast", startedAt = "2024-01-01T12:09:00Z")
    assert(!StreamLogic.recentlyWentLive(stream, now, window))
  }

  // ── findNewStreams ─────────────────────────────────────────────────

  test("findNewStreams: returns recent streams not already notified") {
    val s1 = mkStream(id = "s1", startedAt = "2024-01-01T12:06:01Z") // 4 min ago — recent
    val s2 = mkStream(
      id = "s2",
      startedAt = "2024-01-01T12:06:01Z",
    ) // 4 min ago — recent but already notified
    val s3 = mkStream(id = "s3", startedAt = "2024-01-01T12:00:00Z") // 10 min ago — not recent
    val alreadyNotified = Set("s2")
    val (newStreams, _) = StreamLogic.findNewStreams(List(s1, s2, s3), alreadyNotified, now, window)
    assertEquals(newStreams.map(_.id), List("s1"))
  }

  test("findNewStreams: returns empty when all recent streams already notified") {
    val s1 = mkStream(id = "s1", startedAt = "2024-01-01T12:06:01Z")
    val (newStreams, _) = StreamLogic.findNewStreams(List(s1), Set("s1"), now, window)
    assertEquals(newStreams, Nil)
  }

  test("findNewStreams: returns empty when no streams are recent") {
    val s1 = mkStream(id = "s1", startedAt = "2024-01-01T12:00:00Z") // 10 min ago
    val (newStreams, _) = StreamLogic.findNewStreams(List(s1), Set.empty, now, window)
    assertEquals(newStreams, Nil)
  }

  test("findNewStreams: updated notified set includes ALL stream IDs, not just new ones") {
    val s1 = mkStream(id = "s1", startedAt = "2024-01-01T12:06:01Z") // recent, new
    val s2 = mkStream(id = "s2", startedAt = "2024-01-01T12:00:00Z") // old, not recent
    val alreadyNotified = Set("s0") // previously seen
    val (_, updatedNotified) =
      StreamLogic.findNewStreams(List(s1, s2), alreadyNotified, now, window)
    assertEquals(updatedNotified, Set("s0", "s1", "s2"))
  }

  test("findNewStreams: non-live streams are excluded from new but included in notified set") {
    val s1 = mkStream(id = "s1", tpe = "vodcast", startedAt = "2024-01-01T12:09:00Z")
    val (newStreams, updatedNotified) = StreamLogic.findNewStreams(List(s1), Set.empty, now, window)
    assertEquals(newStreams, Nil)
    assert(updatedNotified.contains("s1"))
  }

  test("findNewStreams: handles empty input") {
    val (newStreams, updatedNotified) = StreamLogic.findNewStreams(Nil, Set("s0"), now, window)
    assertEquals(newStreams, Nil)
    assertEquals(updatedNotified, Set("s0"))
  }

  // ── applyTagFilters ───────────────────────────────────────────────

  test("applyTagFilters: no filters passes all notifications") {
    val notifications =
      List(mkNotification(tags = List("English")), mkNotification(tags = List("Spanish")))
    val result = StreamLogic.applyTagFilters(notifications, Nil)
    assertEquals(result.size, 2)
  }

  test("applyTagFilters: include filter passes matching tag (case-insensitive)") {
    val n = mkNotification(tags = List("English"))
    val filters = List(TagFilter("include", "english"))
    assertEquals(StreamLogic.applyTagFilters(List(n), filters).size, 1)
  }

  test("applyTagFilters: include filter rejects non-matching tag") {
    val n = mkNotification(tags = List("Spanish"))
    val filters = List(TagFilter("include", "english"))
    assertEquals(StreamLogic.applyTagFilters(List(n), filters).size, 0)
  }

  test("applyTagFilters: include filter rejects stream with no tags") {
    val n = mkNotification(tags = Nil)
    val filters = List(TagFilter("include", "english"))
    assertEquals(StreamLogic.applyTagFilters(List(n), filters).size, 0)
  }

  test("applyTagFilters: exclude filter removes matching tag (case-insensitive)") {
    val n = mkNotification(tags = List("Speedrun"))
    val filters = List(TagFilter("exclude", "speedrun"))
    assertEquals(StreamLogic.applyTagFilters(List(n), filters).size, 0)
  }

  test("applyTagFilters: exclude filter passes non-matching tag") {
    val n = mkNotification(tags = List("English"))
    val filters = List(TagFilter("exclude", "speedrun"))
    assertEquals(StreamLogic.applyTagFilters(List(n), filters).size, 1)
  }

  test("applyTagFilters: exclude filter passes stream with no tags") {
    val n = mkNotification(tags = Nil)
    val filters = List(TagFilter("exclude", "speedrun"))
    assertEquals(StreamLogic.applyTagFilters(List(n), filters).size, 1)
  }

  test("applyTagFilters: include + exclude, stream has both tags, exclude wins") {
    val n = mkNotification(tags = List("English", "Speedrun"))
    val filters = List(TagFilter("include", "english"), TagFilter("exclude", "speedrun"))
    assertEquals(StreamLogic.applyTagFilters(List(n), filters).size, 0)
  }

  test("applyTagFilters: include + exclude, stream matches include only") {
    val n = mkNotification(tags = List("English"))
    val filters = List(TagFilter("include", "english"), TagFilter("exclude", "speedrun"))
    assertEquals(StreamLogic.applyTagFilters(List(n), filters).size, 1)
  }

  test("applyTagFilters: include + exclude, stream matches exclude only") {
    val n = mkNotification(tags = List("Speedrun"))
    val filters = List(TagFilter("include", "english"), TagFilter("exclude", "speedrun"))
    assertEquals(StreamLogic.applyTagFilters(List(n), filters).size, 0)
  }

  test("applyTagFilters: multiple include filters, stream matches one") {
    val n = mkNotification(tags = List("French"))
    val filters = List(TagFilter("include", "english"), TagFilter("include", "french"))
    assertEquals(StreamLogic.applyTagFilters(List(n), filters).size, 1)
  }

  test("applyTagFilters: multiple include filters, stream matches none") {
    val n = mkNotification(tags = List("Japanese"))
    val filters = List(TagFilter("include", "english"), TagFilter("include", "french"))
    assertEquals(StreamLogic.applyTagFilters(List(n), filters).size, 0)
  }

  // ── applyIgnoredStreamers ─────────────────────────────────────────

  private def mkNotificationWithStreamer(
    streamerId: String,
    streamerLogin: String = "login",
    streamerName: String = "Name",
    categoryId: String = "game1",
  ): StreamNotification =
    StreamNotification(
      categoryId = categoryId,
      categoryName = "Some Game",
      streamerId = streamerId,
      streamerLogin = streamerLogin,
      streamerName = streamerName,
      streamTitle = "Playing games",
      thumbnailUrl = "thumb.jpg",
      viewerCount = 100,
    )

  test("applyIgnoredStreamers: empty ignored set passes all notifications") {
    val notifications = List(mkNotificationWithStreamer("u1"), mkNotificationWithStreamer("u2"))
    val result = StreamLogic.applyIgnoredStreamers(notifications, Set.empty)
    assertEquals(result.size, 2)
  }

  test("applyIgnoredStreamers: filters out ignored streamer") {
    val notifications = List(mkNotificationWithStreamer("u1"), mkNotificationWithStreamer("u2"))
    val result = StreamLogic.applyIgnoredStreamers(notifications, Set("u1"))
    assertEquals(result.size, 1)
    assertEquals(result.head.streamerId, "u2")
  }

  test("applyIgnoredStreamers: filters out all if all ignored") {
    val notifications = List(mkNotificationWithStreamer("u1"), mkNotificationWithStreamer("u2"))
    val result = StreamLogic.applyIgnoredStreamers(notifications, Set("u1", "u2"))
    assertEquals(result.size, 0)
  }

  test("applyIgnoredStreamers: passes all if none match ignored set") {
    val notifications = List(mkNotificationWithStreamer("u1"), mkNotificationWithStreamer("u2"))
    val result = StreamLogic.applyIgnoredStreamers(notifications, Set("u99"))
    assertEquals(result.size, 2)
  }

  test("applyIgnoredStreamers: handles empty notifications list") {
    val result = StreamLogic.applyIgnoredStreamers(Nil, Set("u1"))
    assertEquals(result, Nil)
  }

  // ── filteredNotificationsForUser ──────────────────────────────────

  private val game1Notif = mkNotification(categoryId = "game1", tags = List("English"))
  private val game2Notif = mkNotification(categoryId = "game2", tags = List("Spanish"))
  private val game3Notif = mkNotification(categoryId = "game3", tags = List("English", "Speedrun"))

  private val byCategoryId = Map(
    "game1" -> List(game1Notif),
    "game2" -> List(game2Notif),
    "game3" -> List(game3Notif),
  )

  test("filteredNotificationsForUser: returns notifications only for followed categories") {
    val prefs = UserPreferences(
      followed = Map("alice" -> Set("game1", "game3")),
      filters = Map.empty,
      ignored = Map.empty,
    )
    val result = StreamLogic.filteredNotificationsForUser("alice", byCategoryId, prefs)
    assertEquals(result.map(_.categoryId).toSet, Set("game1", "game3"))
  }

  test("filteredNotificationsForUser: returns empty when user follows no categories") {
    val prefs = UserPreferences(
      followed = Map("alice" -> Set.empty[String]),
      filters = Map.empty,
      ignored = Map.empty,
    )
    val result = StreamLogic.filteredNotificationsForUser("alice", byCategoryId, prefs)
    assertEquals(result, Nil)
  }

  test("filteredNotificationsForUser: returns empty when user is not in followedMap") {
    val prefs = UserPreferences(followed = Map.empty, filters = Map.empty, ignored = Map.empty)
    val result = StreamLogic.filteredNotificationsForUser("unknown", byCategoryId, prefs)
    assertEquals(result, Nil)
  }

  test("filteredNotificationsForUser: applies tag filters") {
    val prefs = UserPreferences(
      followed = Map("alice" -> Set("game1", "game2", "game3")),
      filters = Map("alice" -> List(TagFilter("include", "english"))),
      ignored = Map.empty,
    )
    val result = StreamLogic.filteredNotificationsForUser("alice", byCategoryId, prefs)
    assertEquals(result.map(_.categoryId).toSet, Set("game1", "game3"))
  }

  test("filteredNotificationsForUser: applies ignored streamers") {
    val n1 = StreamNotification(
      categoryId = "game1",
      categoryName = "G1",
      streamerId = "u1",
      streamerLogin = "login1",
      streamerName = "Name1",
      streamTitle = "Title",
      thumbnailUrl = "thumb.jpg",
      viewerCount = 100,
    )
    val n2 = StreamNotification(
      categoryId = "game1",
      categoryName = "G1",
      streamerId = "u2",
      streamerLogin = "login2",
      streamerName = "Name2",
      streamTitle = "Title",
      thumbnailUrl = "thumb.jpg",
      viewerCount = 200,
    )
    val byCategory = Map("game1" -> List(n1, n2))
    val prefs = UserPreferences(
      followed = Map("alice" -> Set("game1")),
      filters = Map.empty,
      ignored = Map("alice" -> Set("u1")),
    )
    val result = StreamLogic.filteredNotificationsForUser("alice", byCategory, prefs)
    assertEquals(result.map(_.streamerId), List("u2"))
  }

  test(
    "filteredNotificationsForUser: applies category filter, tag filter, and ignored streamer together",
  ) {
    val n1 = StreamNotification(
      categoryId = "game1",
      categoryName = "G1",
      streamerId = "u1",
      streamerLogin = "l1",
      streamerName = "N1",
      streamTitle = "T",
      tags = List("English"),
      thumbnailUrl = "t.jpg",
      viewerCount = 100,
    )
    val n2 = StreamNotification(
      categoryId = "game1",
      categoryName = "G1",
      streamerId = "u2",
      streamerLogin = "l2",
      streamerName = "N2",
      streamTitle = "T",
      tags = List("English"),
      thumbnailUrl = "t.jpg",
      viewerCount = 200,
    )
    val n3 = StreamNotification(
      categoryId = "game2",
      categoryName = "G2",
      streamerId = "u3",
      streamerLogin = "l3",
      streamerName = "N3",
      streamTitle = "T",
      tags = List("Spanish"),
      thumbnailUrl = "t.jpg",
      viewerCount = 300,
    )
    val byCategory = Map("game1" -> List(n1, n2), "game2" -> List(n3))
    val prefs = UserPreferences(
      followed = Map("alice" -> Set("game1", "game2")),
      filters = Map("alice" -> List(TagFilter("include", "english"))),
      ignored = Map("alice" -> Set("u1")),
    )
    val result = StreamLogic.filteredNotificationsForUser("alice", byCategory, prefs)
    assertEquals(result.map(_.streamerId), List("u2"))
  }

}
