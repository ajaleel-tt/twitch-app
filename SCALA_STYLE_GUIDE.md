# Scala Style Guide

This project follows the [official Scala style guide](https://docs.scala-lang.org/style/) as a baseline. This document specifies project-specific additions and overrides. When in conflict, **this document wins**.

Goals: **readability**, **consistency**, **diff-friendliness**.

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

---

## 3. Alphabetical Ordering

**All parameters, fields, and arguments must be alphabetically sorted.**

This applies to:
- Case class fields in definitions
- Function/method parameters in definitions
- Constructor parameters in class definitions
- Named arguments at call sites
- Members within an import group

### Exceptions
- `using`/`implicit` parameters always come last (language requirement).
- Parameters with default values that depend on an earlier parameter's value.
- When implementing a trait whose parameter order is fixed by the parent.

### Examples

Case class definition:
```scala
// GOOD - alphabetical
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

// BAD - grouped by concern
case class AppSettings(
    pollerInterval: FiniteDuration,
    recentlyLiveWindow: FiniteDuration,
    parallelCategories: Int,
    streamsPageSize: Int,
    searchPageSize: Int,
    sseReconnectDelay: FiniteDuration,
    emailFrom: String,
    emailFromName: String,
    pushParallelSends: Int,
    topGamesCount: Int,
    topGamesPollInterval: FiniteDuration,
)
```

Function definition:
```scala
// GOOD - alphabetical
def make(
    client: Client[IO],
    clientId: String,
    clientSecret: String,
    followRepo: FollowRepository,
    ignoredStreamerRepo: IgnoredStreamerRepository,
    notificationQueues: Ref[IO, Map[...]]
    pushRepo: PushSubscriptionRepository,
    pushService: Option[PushService] = None,
    settings: AppSettings,
    tagFilterRepo: TagFilterRepository,
): IO[StreamPoller] =
```

Named arguments at call site (also alphabetical):
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

### Wildcards
- Wildcards (`*`) are acceptable for frequently-used libraries: `cats.effect.*`, `cats.syntax.all.*`, `org.http4s.*`, `doobie.implicits.*`
- Prefer explicit imports for less common packages.
- `{*, given}` syntax is standard for Calico HTML imports.

---

## 7. Declarations

### Case Classes
- Fields on separate lines when more than 2 fields.
- Fields alphabetically sorted with trailing comma.
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
- Multi-line parameter lists: one parameter per line, alphabetically sorted, trailing comma.

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
case class TwitchUser(
    displayName: String,
    email: Option[String] = None,
    id: String,
    login: String,
    profileImageUrl: String,
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

### Resource Management
Always use `Resource` for values that need cleanup:
```scala
for
  client <- EmberClientBuilder.default[IO].build
  xa <- HikariTransactor.fromHikariConfig[IO](config)
yield (client, xa)
```

### State
- `Ref` for mutable state, never `var`.
- `SignallingRef` for state that needs to be observed.

### Concurrency
- `.parTraverseN(n)` for bounded parallelism.
- `.parTupled` for independent parallel effects.
- `.start` + `.join` only when you need the fiber handle.

### Error Handling
- `IO.raiseError` instead of throwing exceptions.
- `.handleErrorWith` for recovery.
- `.attempt` when you need to inspect the error.

---

## 11. Comments

- **Default to no comments.** Only add one when the WHY is non-obvious.
- ScalaDoc (`/** ... */`) for public API.
- No commented-out code in committed files.
- TODO comments must reference a ticket or be resolved before merge.

---

## 12. Testing

- Framework: munit with munit-cats-effect.
- Test class naming: `*Spec` (e.g., `StreamLogicSpec`, `DatabaseSpec`).
- Use IO-based tests: `test("description") { ... }`.

---

## 13. Scalafmt Configuration

We recommend creating a `.scalafmt.conf` to mechanically enforce what it can:

```hocon
version = "3.8.6"
runner.dialect = scala3

maxColumn = 100
indent.main = 2
indent.defnSite = 4

trailingCommas = always

rewrite.rules = [SortImports, RedundantBraces, SortModifiers]
rewrite.sortModifiers.order = [
  "override", "private", "protected", "final",
  "sealed", "abstract", "implicit", "lazy"
]

newlines.afterCurlyLambdaParams = squash
align.preset = more
```

**Note:** Scalafmt cannot enforce alphabetical parameter ordering or the named-argument rule. Those require code review discipline.
