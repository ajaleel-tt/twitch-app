package com.twitch.backend.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import com.twitch.core.TwitchCategory

class TopGamesRepository(xa: Transactor[IO]):

  def replaceTopGames(games: List[TwitchCategory]): IO[Unit] =
    val delete = sql"DELETE FROM top_games".update.run
    val inserts = games.traverse_ { g =>
      sql"INSERT INTO top_games (category_id, name, box_art_url) VALUES (${g.id}, ${g.name}, ${g.box_art_url})".update.run
    }
    (delete *> inserts).transact(xa).void

  def getTopGameIds: IO[Set[String]] =
    sql"SELECT category_id FROM top_games"
      .query[String]
      .to[Set]
      .transact(xa)
