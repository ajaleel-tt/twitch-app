package com.twitch.backend

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.*
import doobie.h2.H2Transactor
import doobie.hikari.HikariTransactor
import com.zaxxer.hikari.HikariConfig

object TwitchServer extends IOApp.Simple:

  private val config = ServerConfig.fromEnv
  private val settings = AppSettings.load

  def run: IO[Unit] =
    val transactorResource: Resource[IO, doobie.Transactor[IO]] =
      if config.dialect == SqlDialect.Postgres then
        val hikariConfig = new HikariConfig()
        hikariConfig.setDriverClassName("org.postgresql.Driver")
        hikariConfig.setJdbcUrl(config.dbUrl)
        config.dbUser.foreach(hikariConfig.setUsername)
        config.dbPassword.foreach(hikariConfig.setPassword)
        HikariTransactor.fromHikariConfig[IO](hikariConfig)
      else
        for {
          ec <- Resource.eval(IO.executionContext)
          xa <- H2Transactor.newH2Transactor[IO](config.dbUrl, "sa", "", ec)
        } yield xa

    transactorResource.use { xa =>
      EmberClientBuilder.default[IO].build.use { client =>
        for
          app <- AppWiring.build(config, settings, xa, client)
          host = host"0.0.0.0"
          port = Port.fromInt(config.port).getOrElse(port"8080")
          _ <- (
            app.poller.start.void,
            app.topGamesPoller.start.void,
            IO.println(s"Server started at ${config.baseUrl}") *>
              EmberServerBuilder
                .default[IO]
                .withHost(host)
                .withPort(port)
                .withHttpApp(app.corsApp)
                .build
                .useForever
          ).parTupled
        yield ()
      }
    }
