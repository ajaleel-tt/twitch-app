package com.twitch.backend

case class ServerConfig(
  baseUrl: String,
  clientId: String,
  clientSecret: String,
  dbPassword: Option[String],
  dbUrl: String,
  dbUser: Option[String],
  dialect: SqlDialect,
  port: Int,
  redirectUri: String,
  staticDir: String,
)

object ServerConfig {

  def fromEnv: ServerConfig = {
    val clientId = sys
      .env
      .getOrElse(
        "TWITCH_CLIENT_ID", {
          System.err.println("ERROR: TWITCH_CLIENT_ID environment variable is not set")
          sys.exit(1)
        },
      )
    val clientSecret = sys
      .env
      .getOrElse(
        "TWITCH_CLIENT_SECRET", {
          System.err.println("ERROR: TWITCH_CLIENT_SECRET environment variable is not set")
          sys.exit(1)
        },
      )
    val baseUrl = sys.env.getOrElse("BASE_URL", "http://localhost:8080")
    val rawDbUrl = sys
      .env
      .getOrElse("DATABASE_URL", "jdbc:h2:./twitch_app_db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")

    val renderPattern = """^postgres(?:ql)?://([^:]+):([^@]+)@([^/]+)/(.+)$""".r
    val (jdbcUrl, user, password) = rawDbUrl match {
      case renderPattern(u, p, host, db) =>
        val hostPort = if host.contains(":") then host else s"$host:5432"
        (s"jdbc:postgresql://$hostPort/$db", Some(u), Some(p))
      case _ => (rawDbUrl, None, None)
    }

    val dialect =
      if jdbcUrl.startsWith("jdbc:postgresql") then SqlDialect.Postgres else SqlDialect.H2

    ServerConfig(
      baseUrl = baseUrl,
      clientId = clientId,
      clientSecret = clientSecret,
      dbPassword = password.orElse(sys.env.get("DATABASE_PASS")),
      dbUrl = jdbcUrl,
      dbUser = user.orElse(sys.env.get("DATABASE_USER")),
      dialect = dialect,
      port = sys.env.getOrElse("PORT", "8080").toInt,
      redirectUri = s"$baseUrl/auth/callback",
      staticDir = sys.env.getOrElse("STATIC_DIR", "./modules/frontend"),
    )
  }

}
