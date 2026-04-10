package com.twitch.backend

import cats.effect.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.implicits.*
import org.http4s.headers.{Authorization, `Content-Type`}
import io.circe.*
import io.circe.syntax.*

class EmailService(
    client: Client[IO],
    apiKey: String,
    fromEmail: String,
    fromName: String
):

  def sendWelcomeEmail(toEmail: String, displayName: String): IO[Unit] =
    val body = Json.obj(
      "personalizations" -> Json.arr(Json.obj(
        "to" -> Json.arr(Json.obj("email" -> toEmail.asJson))
      )),
      "from" -> Json.obj(
        "email" -> fromEmail.asJson,
        "name" -> fromName.asJson
      ),
      "subject" -> s"Welcome to Twitch App, $displayName!".asJson,
      "content" -> Json.arr(Json.obj(
        "type" -> "text/html".asJson,
        "value" -> welcomeHtml(displayName).asJson
      ))
    )

    val req = Request[IO](
      method = Method.POST,
      uri = uri"https://api.sendgrid.com/v3/mail/send"
    ).withEntity(body.noSpaces)
     .putHeaders(
       Authorization(Credentials.Token(AuthScheme.Bearer, apiKey)),
       `Content-Type`(MediaType.application.json)
     )

    client.run(req).use { resp =>
      if resp.status.isSuccess then IO.unit
      else resp.bodyText.compile.string.flatMap { errorBody =>
        IO.raiseError(new RuntimeException(
          s"SendGrid API error: ${resp.status} - $errorBody"
        ))
      }
    }

  private def welcomeHtml(displayName: String): String =
    s"""<html><body style="font-family: sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
       |<h1 style="color: #9146FF;">Welcome to Twitch App, $displayName!</h1>
       |<p>Thanks for signing up. We hope you enjoy tracking your favorite Twitch categories and getting notified when streamers go live.</p>
       |<p>Have feedback or ideas? <a href="https://discord.gg/Tk2XVDUh">Join our Discord server</a> and let us know!</p>
       |<p>Happy watching!</p>
       |</body></html>""".stripMargin
