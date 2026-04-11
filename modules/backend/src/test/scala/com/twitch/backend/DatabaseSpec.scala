package com.twitch.backend

import munit.CatsEffectSuite
import cats.effect.*
import doobie.h2.H2Transactor
import com.twitch.core.*

class DatabaseSpec extends CatsEffectSuite:

  private val dbFixture = ResourceSuiteLocalFixture(
    "database",
    for
      ec <- Resource.eval(IO.executionContext)
      xa <- H2Transactor.newH2Transactor[IO](
        "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "sa", "", ec
      )
      db = new Database(xa)
      _ <- Resource.eval(db.initDb)
    yield db
  )

  override def munitFixtures = List(dbFixture)

  private def db = dbFixture()

  // ── Tag filter tests ───────────────────────────────────────────────

  test("addTagFilter normalizes tag to lowercase") {
    for
      _ <- db.addTagFilter("user1", "include", "ENGLISH")
      filters <- db.getTagFilters("user1")
    yield assertEquals(filters.map(_.tag), List("english"))
  }

  test("addTagFilter and getTagFilters round-trip") {
    for
      _ <- db.addTagFilter("user2", "include", "english")
      _ <- db.addTagFilter("user2", "exclude", "speedrun")
      filters <- db.getTagFilters("user2")
    yield {
      assertEquals(filters.size, 2)
      assert(filters.exists(f => f.filterType == "include" && f.tag == "english"))
      assert(filters.exists(f => f.filterType == "exclude" && f.tag == "speedrun"))
    }
  }

  test("addTagFilter is idempotent (no duplicate)") {
    for
      _ <- db.addTagFilter("user3", "include", "english")
      _ <- db.addTagFilter("user3", "include", "english")
      filters <- db.getTagFilters("user3")
    yield assertEquals(filters.count(_.tag == "english"), 1)
  }

  test("removeTagFilter removes existing filter") {
    for
      _ <- db.addTagFilter("user4", "include", "english")
      _ <- db.removeTagFilter("user4", "include", "english")
      filters <- db.getTagFilters("user4")
    yield assertEquals(filters.size, 0)
  }

  test("removeTagFilter on non-existent filter does not error") {
    db.removeTagFilter("user5", "include", "nonexistent")
  }

  // ── Follow tests ───────────────────────────────────────────────────

  private val testCategory = TwitchCategory("cat1", "Test Category", "https://example.com/art.jpg")

  test("follow and getFollowed round-trip") {
    for
      _ <- db.follow("user6", testCategory)
      followed <- db.getFollowed("user6")
    yield {
      assertEquals(followed.size, 1)
      assertEquals(followed.head.id, "cat1")
      assertEquals(followed.head.name, "Test Category")
    }
  }

  test("unfollow removes category") {
    for
      _ <- db.follow("user7", testCategory)
      _ <- db.unfollow("user7", "cat1")
      followed <- db.getFollowed("user7")
    yield assertEquals(followed.size, 0)
  }

  test("getAllFollowedCategories deduplicates across users") {
    for
      _ <- db.follow("user8", testCategory)
      _ <- db.follow("user9", testCategory)
      all <- db.getAllFollowedCategories
    yield {
      val matching = all.filter(_.id == "cat1")
      assertEquals(matching.size, 1)
    }
  }

  // ── User tests ─────────────────────────────────────────────────────

  test("insertUser and findUser round-trip") {
    for
      _ <- db.insertUser("newuser1", "testlogin1", "TestUser1", Some("test@example.com"))
      found <- db.findUser("newuser1")
    yield {
      assert(found.isDefined)
      assertEquals(found.get.userId, "newuser1")
      assertEquals(found.get.login, Some("testlogin1"))
      assertEquals(found.get.displayName, Some("TestUser1"))
      assertEquals(found.get.email, Some("test@example.com"))
      assertEquals(found.get.welcomeEmailSent, false)
    }
  }

  test("findUser returns None for unknown user") {
    for
      found <- db.findUser("nonexistent")
    yield assert(found.isEmpty)
  }

  test("markWelcomeEmailSent updates flag") {
    for
      _ <- db.insertUser("newuser2", "testlogin2", "TestUser2", Some("test2@example.com"))
      _ <- db.markWelcomeEmailSent("newuser2")
      found <- db.findUser("newuser2")
    yield assertEquals(found.get.welcomeEmailSent, true)
  }

  test("updateLastLogin updates timestamp") {
    for
      _ <- db.insertUser("newuser3", "testlogin3", "TestUser3", Some("test3@example.com"))
      before <- db.findUser("newuser3")
      _ <- IO.sleep(scala.concurrent.duration.Duration(10, "ms"))
      _ <- db.updateLastLogin("newuser3", "testlogin3", "TestUser3", Some("test3@example.com"))
      after <- db.findUser("newuser3")
    yield assert(after.get.lastLoginAt >= before.get.lastLoginAt)
  }

  test("updateLastLogin updates email via COALESCE") {
    for
      _ <- db.insertUser("newuser4", "testlogin4", "TestUser4", None)
      _ <- db.updateLastLogin("newuser4", "testlogin4", "TestUser4", Some("new@example.com"))
      found <- db.findUser("newuser4")
    yield assertEquals(found.get.email, Some("new@example.com"))
  }
