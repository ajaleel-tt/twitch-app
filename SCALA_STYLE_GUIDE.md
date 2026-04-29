# Scala Style Guide

This project follows the [official Scala style guide](https://docs.scala-lang.org/style/) as a baseline. This document specifies project-specific additions and overrides. When in conflict, **this document wins**.

Goals: **readability**, **consistency**, **diff-friendliness**.

### Compiler Enforcement
Backend and core modules compile with `-new-syntax -no-indent`, which means:
- Scala 3 syntax is required (`if ... then`, `while ... do`)
- Significant indentation (braceless syntax) is forbidden — braces are required for all bodies

Frontend is exempt from these flags due to generated code (`scalawind.scala`).

### Excluded Files
- **Generated code** (e.g., `scalawind.scala`) is excluded from all style rules.
- **Wire-format models** have specific naming exceptions documented in Section 2.

---

## 1. Formatting Basics

### Indentation
- **2 spaces** per indent level. Never tabs.
- Continuation lines indented 2 spaces from the first line of the expression.

### Line Length
- **100 characters** hard limit.
- Exemptions: SQL string fragments in doobie queries, and long string literals that cannot be reasonably broken.

### Trailing Commas
When each element is on its own line, the **last element must have a trailing comma**.

```scala
// GOOD
case class ServerConfig(
  baseUrl: String,
  clientId: String,
  port: Int,
)

// BAD - missing trailing comma
case class ServerConfig(
  baseUrl: String,
  clientId: String,
  port: Int
)
```

### Alignment
Do not align `=` signs, `->` arrows, or type annotations across consecutive lines.

```scala
// GOOD
val followRepo = new db.FollowRepository(xa, config.dialect)
val tagFilterRepo = new db.TagFilterRepository(xa, config.dialect)
val userRepo = new db.UserRepository(xa)

// BAD
val followRepo    = new db.FollowRepository(xa, config.dialect)
val tagFilterRepo = new db.TagFilterRepository(xa, config.dialect)
val userRepo      = new db.UserRepository(xa)
```

### Blank Lines
- One blank line between method definitions.
- No trailing whitespace on any line.

---

## 2. Naming Conventions

Follow the [official naming conventions](https://docs.scala-lang.org/style/naming-conventions.html):

| Category | Convention | Example |
|---|---|---|
| Classes, traits, objects, enums | UpperCamelCase | `StreamPoller`, `PushService` |
| Methods, values, variables, parameters | lowerCamelCase | `clientId`, `fetchLiveStreams` |
| Constants (in companion objects) | UpperCamelCase | `val DefaultPageSize = 20` |
| Packages | all lowercase | `com.twitch.backend` |
| Type parameters | Start with `A` | `F[A]`, `Map[Key, Value]` |

### Additional Rules
- **Acronyms as words**: `clientId` not `clientID`, `baseUrl` not `baseURL`.
- **Boolean values**: prefer `is`/`has`/`should` prefix: `isLive`, `hasEmail`.
- **No underscore prefixes** for private members.
- **No `get`/`set` prefixes** on accessors/mutators.
- **Parentheses signal side effects**: include `()` on no-arg methods with side effects; omit for pure accessors.

### Wire-Format Models (Exception)
Case classes that map directly to external JSON APIs (e.g., Twitch API responses) **must use the field names from the wire format**, even when that means `snake_case`. These models rely on Circe's `derives Codec.AsObject` to automatically match JSON keys to field names.

```scala
// CORRECT - matches Twitch API JSON field names
case class TwitchStream(
  game_id: String,
  game_name: String,
  id: String,
  tags: Option[List[String]] = None,
) derives Codec.AsObject
```

Internal models that do not map to an external API use standard `camelCase`.

---

## 3. Ordering

**Prefer alphabetical ordering** for case class fields, function parameters, and named arguments at call sites when the data is unordered (config objects, DTOs, settings).

**Preserve semantic or API order** when it aids readability — for example, when parameters form a logical sequence, or when the order matches an external API contract.

When in doubt, alphabetical is the safe default.

### Always Alphabetical
- Members within an import group: `import cats.effect.{IO, Ref, Resource}`
- Named arguments at call sites for config/DTO constructors

### Exceptions
- `using`/`implicit` parameters always come last (language requirement).
- Parameters with default values that depend on an earlier parameter's value.
- When implementing a trait whose parameter order is fixed by the parent.

---

## 4. Named Arguments for Same-Type Parameters

When a function accepts **more than one parameter of the same type**, callers **must use named arguments**. This prevents accidental argument-swap bugs.

```scala
// BAD - two Strings, easy to swap
twitchApi.exchangeCode(code, redirectUri)

// GOOD
twitchApi.exchangeCode(code = code, redirectUri = redirectUri)
```

### Exemptions
- Builder patterns where position is obvious: `.withQueryParam("key", value)`
- DSL code (http4s route matchers, circe codecs)
- Single-argument functions

---

## 5. Operator Chaining

When an operator chain (`*>`, `>>`, `<*`, `>>=`) would exceed 100 characters, break it across lines. **End each line with the operator** so Scala does not insert semicolons. Continuations indent 2 spaces.

```scala
// GOOD
IO.println("starting") *>
  seedOnce.handleErrorWith(e => IO.println(s"error: $e")) *>
  (IO.sleep(interval) *> pollOnce).foreverM
```

### Method Chains
Break with the dot at the start of the continuation line:
```scala
uri"https://api.twitch.tv/helix/streams"
  .withQueryParam("game_id", categoryId)
  .withQueryParam("first", pageSize.toString)
```

---

## 6. Imports

### Grouping (separated by blank lines)
1. Scala standard library (`scala.*`)
2. Java standard library (`java.*`, `javax.*`)
3. Third-party libraries (`cats.*`, `org.http4s.*`, `io.circe.*`, `doobie.*`, `fs2.*`, `calico.*`)
4. Project imports (`com.twitch.*`)

### Ordering
- Groups sorted alphabetically within each group.
- Members within an import sorted alphabetically: `import cats.effect.{IO, Ref, Resource}`

### Wildcard Imports
Wildcard imports are allowed for library DSLs, syntax packages, and frequently-used preludes, such as `cats.syntax.all.*`, `cats.effect.*`, `org.http4s.*`, `doobie.implicits.*`, and Calico HTML imports (`{*, given}`).

Prefer explicit imports for Java standard library packages, project/domain packages, and third-party packages where only one or two symbols are used. Do not churn existing files just to remove wildcards unless the imports are already being touched.

---

## 7. Declarations

### Braces Required (Backend/Core)
All class, object, trait, and enum bodies must use braces. This is enforced by `-no-indent`.

```scala
object AppSettings {
  def load: IO[AppSettings] = IO.blocking { ... }
}

enum SqlDialect {
  case H2, Postgres
}
```

### Case Classes
- Fields on separate lines when more than 2 fields.
- Prefer alphabetical field ordering for config/DTO-like classes (see Section 3).
- Trailing comma on the last field.
- `derives` clause on the closing parenthesis line.

### Method Signatures
- **Public methods must have explicit return types.**
- Private methods should have explicit return types when the body is non-trivial.
- Multi-line parameter lists: one parameter per line, trailing comma. Prefer alphabetical ordering for config/DTO-like signatures (see Section 3).

### Modifier Order
`override` > access modifiers (`protected`/`private`) > `final` > `def`/`val`/`var`

---

## 8. Control Structures

### For Comprehensions
Prefer for comprehensions over chained `map`/`flatMap` for 3+ operations. Use braces (required by `-no-indent` in backend/core):

```scala
for {
  user <- getUser(id)
  prefs <- getPrefs(user.id)
  streams <- fetchStreams(prefs)
} yield (user, streams)
```

### If/Else
Use Scala 3 `if ... then ... else` syntax (required by `-new-syntax`):

```scala
val dialect =
  if jdbcUrl.startsWith("jdbc:postgresql") then SqlDialect.Postgres
  else SqlDialect.H2
```

### Pattern Matching
- Exhaustive matching required (no unchecked warnings).

---

## 9. Scala 3 Features

### Enums
Use `enum` for algebraic data types:
```scala
enum SqlDialect {
  case H2, Postgres
}
```

### Derives
Use `derives` for typeclass derivation, placed on the closing parenthesis line:
```scala
case class StreamNotification(
  categoryId: String,
  categoryName: String,
  viewerCount: Int,
) derives Codec.AsObject
```

### Given/Using
- Prefer `using` over `implicit` parameters.
- Anonymous `given` instances when the type alone is sufficient to identify them.

### Extension Methods
- Group related extensions in a single `extension` block.
- Place in the companion object or a dedicated extensions object.

---

## 10. Cats-Effect Patterns

This project follows the [Five Simple Rules](https://github.com/typelevel/cats-effect?tab=readme-ov-file#five-simple-rules) from the cats-effect documentation.

1. **Wrap all side-effects** in `IO.delay`, `IO.blocking`, `IO.interruptible`, or `IO.async`.
2. **Use `Resource` or `bracket`** for anything requiring cleanup. Never `try`/`finally`.
3. **Never hard-block a thread.** Use `IO.sleep` not `Thread.sleep`. Use `IO.blocking` for unavoidable blocking calls. (doobie handles this for DB operations.)
4. **Use `IOApp`** as the entry point. Never `def main` with `unsafeRunSync`.
5. **Avoid cats-effect `unsafe` runtime escapes** (`unsafeRunSync`, `unsafeToFuture`, etc.). `Uri.unsafeFromString` and similar non-runtime methods are fine.

### Additional Patterns
- `Ref` for mutable state, never `var`. `SignallingRef` for observable state.
- `.parTraverseN(n)` for bounded parallelism. `.parTupled` for independent parallel effects.
- `IO.raiseError` instead of throwing exceptions. `.handleErrorWith` for recovery.

---

## 11. Cats Guidelines

This project follows the [Typelevel Cats guidelines](https://typelevel.org/cats/guidelines.html) where applicable to application code:

- **Partially-applied type pattern**: `OptionT.pure[IO](42)` not `OptionT.pure[IO, Int](42)`.
- **Typeclass syntax**: Use `cats.syntax.all.*` extensions rather than calling typeclass methods directly.
- **`Nested` over transformer `Applicative`**: When you only need `Applicative` (not `Monad`), use `Nested` rather than a monad transformer.

---

## 12. Comments

- **Default to no comments.** Only add one when the WHY is non-obvious.
- No commented-out code in committed files.
- TODO comments must reference a ticket or be resolved before merge.

---

## 13. Testing

- Framework: munit with munit-cats-effect.
- Test class naming: `*Spec` (e.g., `StreamLogicSpec`, `DatabaseSpec`).
- Use IO-based tests: `test("description") { ... }`.

---

## 14. Scalafmt

This project uses [scalafmt](https://scalameta.org/scalafmt/) for automated formatting. The configuration lives in `.scalafmt.conf` at the project root and enforces line length, trailing commas, import sorting, and modifier ordering.

To format all files: `sbt scalafmtAll`

**What scalafmt enforces:** line length, indentation, trailing commas, import sorting, modifier ordering, generated file exclusions.

**What requires code review:** parameter ordering preferences (Section 3), named arguments for same-type parameters (Section 4), wire-format naming exceptions (Section 2).
