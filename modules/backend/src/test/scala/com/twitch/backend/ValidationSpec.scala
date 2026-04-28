package com.twitch.backend

import munit.FunSuite

class ValidationSpec extends FunSuite:

  // ── validateTag ───────────────────────────────────────────────────

  test("validateTag: valid tag returns trimmed Right") {
    assertEquals(Validation.validateTag("English"), Right("English"))
  }

  test("validateTag: trims whitespace") {
    assertEquals(Validation.validateTag("  English  "), Right("English"))
  }

  test("validateTag: empty string returns Left") {
    assert(Validation.validateTag("").isLeft)
  }

  test("validateTag: whitespace-only string returns Left") {
    assert(Validation.validateTag("   ").isLeft)
  }

  test("validateTag: 25-character tag is valid") {
    val tag = "a" * 25
    assertEquals(Validation.validateTag(tag), Right(tag))
  }

  test("validateTag: 26-character tag returns Left") {
    assert(Validation.validateTag("a" * 26).isLeft)
  }

  // ── validateFilterType ────────────────────────────────────────────

  test("validateFilterType: 'include' is valid") {
    assertEquals(Validation.validateFilterType("include"), Right("include"))
  }

  test("validateFilterType: 'exclude' is valid") {
    assertEquals(Validation.validateFilterType("exclude"), Right("exclude"))
  }

  test("validateFilterType: 'invalid' returns Left") {
    assert(Validation.validateFilterType("invalid").isLeft)
  }

  test("validateFilterType: empty string returns Left") {
    assert(Validation.validateFilterType("").isLeft)
  }

  // ── validatePlatform ──────────────────────────────────────────────

  test("validatePlatform: 'ios' is valid") {
    assertEquals(Validation.validatePlatform("ios"), Right("ios"))
  }

  test("validatePlatform: 'android' is valid") {
    assertEquals(Validation.validatePlatform("android"), Right("android"))
  }

  test("validatePlatform: 'web' is valid") {
    assertEquals(Validation.validatePlatform("web"), Right("web"))
  }

  test("validatePlatform: 'desktop' returns Left") {
    assert(Validation.validatePlatform("desktop").isLeft)
  }

  test("validatePlatform: empty string returns Left") {
    assert(Validation.validatePlatform("").isLeft)
  }

  // ── validateNonEmpty ──────────────────────────────────────────────

  test("validateNonEmpty: non-empty string returns Right") {
    assertEquals(Validation.validateNonEmpty("abc", "field"), Right("abc"))
  }

  test("validateNonEmpty: trims whitespace") {
    assertEquals(Validation.validateNonEmpty("  abc  ", "field"), Right("abc"))
  }

  test("validateNonEmpty: empty string returns Left with field name") {
    assertEquals(Validation.validateNonEmpty("", "streamerId"), Left("streamerId is required"))
  }

  test("validateNonEmpty: whitespace-only string returns Left") {
    assert(Validation.validateNonEmpty("   ", "field").isLeft)
  }
