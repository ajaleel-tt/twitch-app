# Scala Style Guide

This project follows the [official Scala style guide](https://docs.scala-lang.org/style/) as a baseline. This document specifies project-specific additions and overrides. When in conflict, **this document wins**.

Goals: **readability**, **consistency**, **diff-friendliness**.

### Adoption Policy
This guide applies to **new and changed code**. Existing code is not required to be retroactively reformatted. When modifying a file, apply these rules to the lines you touch. Whole-file reformatting is welcome but should be done in dedicated cleanup commits, not mixed with functional changes.

### Excluded Files
- **Generated code** (e.g., `scalawind.scala`) is excluded from all rules in this guide.
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
When each element is on its own line, the **last element must have a trailing comma**. This ensures adding a new element is a one-line diff.

```scala
// GOOD
case class ServerConfig(
    baseUrl: String,
    clientId: String,
    clientSecret: String,
    port: Int,
)

// BAD - missing trailing comma
case class ServerConfig(
    baseUrl: String,
    clientId: String,
    clientSecret: String,
    port: Int
)
```

This applies to: case class fields, function parameters, function arguments, collection literals, and import groups.

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
Case classes that map directly to external JSON APIs (e.g., Twitch API responses) **must use the field names from the wire format**, even when that means `snake_case`. These models rely on Circe's `derives Codec.AsObject` to automatically match JSON keys to field names. Renaming fields to `camelCase` would break serialization unless custom codecs are added.

```scala
// CORRECT - matches Twitch API JSON field names
case class TwitchStream(
    game_id: String,
    game_name: String,
    id: String,
    started_at: String,
    thumbnail_url: String,
    title: String,
    `type`: String,
    user_id: String,
    user_login: String,
    user_name: String,
    viewer_count: Int,
    tags: Option[List[String]] = None,
) derives Codec.AsObject
```

Internal models that do not map to an external API use standard `camelCase`.

---

## 3. Ordering

### Prefer Alphabetical for Unordered Data

**Prefer alphabetical ordering** for case class fields, function parameters, and named arguments at call sites when the data is unordered (config objects, DTOs, settings). Alphabetical ordering makes it easy to find fields in large declarations and produces predictable diffs.

**Preserve semantic or API order** when it aids readability — for example, when parameters form a logical sequence (e.g., `host`/`port`/`path`), when a constructor mirrors a builder chain, or when the order matches an external API contract.

When in doubt, alphabetical is the safe default.

### Always Alphabetical
- Members within an import group: `import cats.effect.{IO, Ref, Resource}`
- Named arguments at call sites for config/DTO constructors

### Examples

Config-style case class (alphabetical):
```scala
case class AppSettings(
    emailFrom: String,
    emailFromName: String,
    parallelCategories: Int,
    pollerInterval: FiniteDuration,
    pushParallelSends: Int,
    recentlyLiveWindow: FiniteDuration,
    searchPageSize: Int,
    sseReconnectDelay: FiniteDuration,
    streamsPageSize: Int,
    topGamesCount: Int,
    topGamesPollInterval: FiniteDuration,
)
```

Named arguments at call site (alphabetical):
```scala
ServerConfig(
    baseUrl = baseUrl,
    clientId = clientId,
    clientSecret = clientSecret,
    dbPassword = password,
    dbUrl = jdbcUrl,
    dbUser = user,
    dialect = dialect,
    port = port,
    redirectUri = s"$baseUrl/auth/callback",
    staticDir = staticDir,
)
```

Semantic order is fine when it helps (e.g., a pipeline that reads left-to-right):
```scala
def fetchStreamsPage(
    token: String,
    categoryId: String,
    cursor: Option[String],
): IO[TwitchStreamsResponse] =
```

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

// GOOD - named arguments prevent confusion
twitchApi.exchangeCode(
    code = code,
    redirectUri = redirectUri,
)

// OK - only one String parameter, no ambiguity
IO.println(s"Server started")

// BAD - four Strings, impossible to verify correctness at a glance
new EmailService(client, key, settings.emailFrom, settings.emailFromName)

// GOOD
new EmailService(
    apiKey = key,
    client = client,
    fromEmail = settings.emailFrom,
    fromName = settings.emailFromName,
)
```

### Exemptions
- Builder patterns where position is obvious: `.withQueryParam("key", value)`
- DSL code (http4s route matchers, circe codecs)
- Single-argument functions

---

## 5. Operator Chaining

When an operator chain (`*>`, `>>`, `<*`, `>>=`) would exceed 100 characters, break it across lines. **End each line with the operator** so Scala does not insert semicolons. Continuations indent 2 spaces.

```scala
// BAD - 132 characters, unreadable
IO.println("starting") *> seedOnce.handleErrorWith(e => IO.println(s"error: $e")) *> (IO.sleep(interval) *> pollOnce).foreverM

// GOOD - broken at operators
IO.println("starting") *>
  seedOnce.handleErrorWith(e => IO.println(s"error: $e")) *>
  (IO.sleep(interval) *> pollOnce).foreverM
```

For long database init chains:
```scala
// BAD - one enormous line
(createFollowed *> createTagFilters *> createIgnoredStreamers *> createSessions *> createUsers).transact(xa).void

// GOOD
(createFollowed *>
  createTagFilters *>
  createIgnoredStreamers *>
  createSessions *>
  createUsers
).transact(xa).void
```

### Method Chains
Break with the dot at the start of the continuation line:
```scala
uri"https://api.twitch.tv/helix/streams"
  .withQueryParam("game_id", categoryId)
  .withQueryParam("first", pageSize.toString)
  .withOptionQueryParam("after", after)
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

### Case Classes
- Fields on separate lines when more than 2 fields.
- Prefer alphabetical field ordering for config/DTO-like classes; preserve semantic order when it aids readability (see Section 3).
- Trailing comma on the last field.
- `derives` clause on the closing parenthesis line.
- Single-field case classes may be on one line.

```scala
case class FollowRequest(
    category: TwitchCategory,
) derives Codec.AsObject

case class StreamNotification(
    categoryId: String,
    categoryName: String,
    streamerId: String,
    streamerLogin: String,
    streamerName: String,
    streamTitle: String,
    tags: List[String] = Nil,
    thumbnailUrl: String,
    viewerCount: Int,
) derives Codec.AsObject
```

Note: fields with default values sort alphabetically like any other field (unless they depend on another parameter's value).

### Method Signatures
- **Public methods must have explicit return types.**
- Private methods should have explicit return types when the body is non-trivial.
- Multi-line parameter lists: one parameter per line, trailing comma. Prefer alphabetical ordering for config/DTO-like signatures (see Section 3).

```scala
def filteredNotificationsForUser(
    byCategoryId: Map[String, List[StreamNotification]],
    filtersMap: Map[String, List[TagFilter]],
    followedMap: Map[String, Set[String]],
    ignoredMap: Map[String, Set[String]],
    userId: String,
): List[StreamNotification] =
```

### Class Definitions
- Constructor parameters on separate lines when more than 2 or exceeding 100 characters.
- Use braceless syntax (`:`) for class/object/trait bodies.
- Extend clauses on same line if they fit within 100 characters.

### Modifier Order
`override` > access modifiers (`protected`/`private`) > `final` > `def`/`val`/`var`

---

## 8. Control Structures

### For Comprehensions
Prefer for comprehensions over chained `map`/`flatMap` for 3+ operations.

```scala
// GOOD - clear sequential flow
for
  user <- getUser(id)
  prefs <- getPrefs(user.id)
  streams <- fetchStreams(prefs)
yield (user, streams)

// AVOID for complex chains - harder to read
getUser(id).flatMap(user =>
  getPrefs(user.id).flatMap(prefs =>
    fetchStreams(prefs).map(streams =>
      (user, streams))))
```

### If/Else
Use Scala 3 `if ... then ... else` syntax:

```scala
val dialect =
  if jdbcUrl.startsWith("jdbc:postgresql") then
    SqlDialect.Postgres
  else
    SqlDialect.H2
```

### Pattern Matching
- Prefer `match` on its own line for multi-case expressions.
- Exhaustive matching required (no unchecked warnings).

---

## 9. Scala 3 Features

### Optional Braces
- **Braceless syntax** for: class/object/trait bodies, enum bodies, if/else, match/case, for comprehensions.
- **Keep braces** for: `HttpRoutes.of[IO] { ... }`, lambda blocks passed to higher-order functions, `Resource.make { ... } { ... }`.

### Enums
Use `enum` for algebraic data types:
```scala
enum SqlDialect:
  case H2, Postgres
```

### Derives
Use `derives` for typeclass derivation, placed on the closing parenthesis line:
```scala
// Wire-format model: snake_case field names match the JSON API
case class TwitchUser(
    display_name: String,
    email: Option[String] = None,
    id: String,
    login: String,
    profile_image_url: String,
) derives Codec.AsObject

// Internal model: standard camelCase
case class StreamNotification(
    categoryId: String,
    categoryName: String,
    streamerId: String,
    tags: List[String] = Nil,
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

This project follows the [Five Simple Rules](https://github.com/typelevel/cats-effect?tab=readme-ov-file#five-simple-rules) from the cats-effect documentation. Adhering to these rules unlocks performance, resource safety, reliable interruption, and full access to the cats-effect ecosystem.

### Rule 1: Wrap All Side-Effects

Every side-effecting operation must be wrapped in `IO.delay`, `IO.blocking`, `IO.interruptible`, or `IO.async`. Prefer many small `delay` blocks composed together over one large one.

```scala
// GOOD - side effect wrapped
IO.delay(System.currentTimeMillis())

// GOOD - blocking I/O marked as such
IO.blocking(scala.io.Source.fromFile("data.csv").mkString)

// BAD - bare side effect inside a map/flatMap
ref.get.map { state =>
  println(s"Current state: $state")  // side effect not wrapped!
  state
}

// GOOD
ref.get.flatMap { state =>
  IO.println(s"Current state: $state").as(state)
}
```

### Rule 2: Use `Resource` or `bracket` for Cleanup

Anything that acquires a resource requiring cleanup must use `Resource` or `bracket`. Never rely on try/finally or manual cleanup.

```scala
// GOOD
for
  client <- EmberClientBuilder.default[IO].build
  xa <- HikariTransactor.fromHikariConfig[IO](config)
yield (client, xa)

// BAD - manual cleanup, easy to leak on error
val client = buildClient()
try { useClient(client) }
finally { client.close() }
```

### Rule 3: Never Hard-Block a Thread

Threads must only be blocked inside `IO.blocking` or `IO.interruptible`. Never call `Thread.sleep`, `.get` on a `Future`, or any other blocking operation on the compute pool.

```scala
// GOOD - uses IO.sleep on the IO scheduler
IO.sleep(settings.pollerInterval) *> pollOnce

// BAD - blocks a compute thread
IO.delay(Thread.sleep(5000))

// GOOD - blocking JDBC wrapped properly
IO.blocking(connection.prepareStatement(sql).execute())
```

Note: doobie already handles this for database operations via its `Transactor`, so direct JDBC wrapping is rarely needed in this project.

### Rule 4: Use `IOApp`

The application entry point must extend `IOApp` or `IOApp.Simple`. Never write a `def main` that calls `unsafeRunSync` or similar.

```scala
// GOOD
object TwitchServer extends IOApp.Simple:
  def run: IO[Unit] = ...

// BAD
object TwitchServer:
  def main(args: Array[String]): Unit =
    program.unsafeRunSync()
```

### Rule 5: Avoid Cats-Effect `unsafe` Runtime Escapes

Never call `unsafeRunSync`, `unsafeRunAndForget`, `unsafeToFuture`, or other cats-effect runtime escape hatches. These break the guarantees that the IO runtime provides. If you find yourself reaching for one, restructure the code so the `IO` is composed into the main `run` method instead.

Note: `unsafe` methods in other libraries (e.g., `Uri.unsafeFromString`) are fine when the input is known at compile time or otherwise guaranteed to be valid. This rule is specifically about bypassing the cats-effect runtime.

### Additional Patterns

**State:**
- `Ref` for mutable state, never `var`.
- `SignallingRef` for state that needs to be observed.

**Concurrency:**
- `.parTraverseN(n)` for bounded parallelism.
- `.parTupled` for independent parallel effects.
- `.start` + `.join` only when you need the fiber handle.

**Error Handling:**
- `IO.raiseError` instead of throwing exceptions.
- `.handleErrorWith` for recovery.
- `.attempt` when you need to inspect the error.

---

## 11. Cats Guidelines

This project follows the [Typelevel Cats guidelines](https://typelevel.org/cats/guidelines.html). While many of those guidelines target library authors, several apply directly to application code.

### Partially-Applied Type Pattern

When writing generic functions with multiple type parameters where only some can be inferred, use the partially-applied type pattern. Split the call into two steps so the caller provides the non-inferable types and the compiler infers the rest.

```scala
// GOOD - F is provided, A is inferred from the argument
OptionT.pure[IO](42)

// BAD - forces the caller to specify everything
OptionT.pure[IO, Int](42)
```

### Implicit Instance Priority

When defining multiple related implicit (or `given`) instances in an inheritance hierarchy, place more specific instances at higher priority. Separate them into numbered abstract classes or traits (0 = highest priority), each inheriting from the next lower priority.

```scala
// Higher priority (more specific)
trait MyInstances0 extends MyInstances1:
  given specificInstance: MyTypeclass[SpecificType] = ...

// Lower priority (more general)
trait MyInstances1:
  given generalInstance[A]: MyTypeclass[A] = ...
```

### Prefer Typeclass Syntax

Use the typeclass syntax extensions (`cats.syntax.all.*`) rather than calling typeclass methods directly. This keeps code concise and idiomatic.

```scala
// GOOD - syntax extension
list.traverse(fetchItem)
(resultA, resultB).parTupled

// LESS IDIOMATIC - direct typeclass call
Traverse[List].traverse(list)(fetchItem)
```

### Prefer `Nested` Over Transformer `Applicative`

When you only need `Applicative` (not `Monad`) for a composed effect, use `Nested` rather than a monad transformer. This avoids subtle behavioral differences between `Applicative` and `Monad` composition.

```scala
// GOOD - Nested for applicative composition
import cats.data.Nested
Nested(IO(Option(42))).map(_ + 1).value

// CAUTION - EitherT with only Applicative can behave unexpectedly
```

---

## 12. Comments

- **Default to no comments.** Only add one when the WHY is non-obvious.
- ScalaDoc (`/** ... */`) for public API.
- No commented-out code in committed files.
- TODO comments must reference a ticket or be resolved before merge.

---

## 13. Testing

- Framework: munit with munit-cats-effect.
- Test class naming: `*Spec` (e.g., `StreamLogicSpec`, `DatabaseSpec`).
- Use IO-based tests: `test("description") { ... }`.

---

## 14. Scalafmt Configuration

We recommend creating a `.scalafmt.conf` to mechanically enforce what it can:

```hocon
version = "3.8.6"
runner.dialect = scala3

maxColumn = 100
indent.main = 2
indent.defnSite = 2

trailingCommas = always

rewrite.rules = [SortImports, RedundantBraces, SortModifiers]
rewrite.sortModifiers.order = [
  "override", "private", "protected", "final",
  "sealed", "abstract", "implicit", "lazy"
]

newlines.afterCurlyLambdaParams = squash
align.preset = more
```

**Note:** Scalafmt cannot enforce parameter ordering preferences or the named-argument rule. Those require code review discipline.
