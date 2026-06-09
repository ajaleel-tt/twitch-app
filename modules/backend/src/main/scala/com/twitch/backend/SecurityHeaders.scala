package com.twitch.backend

import cats.data.Kleisli
import cats.effect.IO
import org.http4s.*
import org.typelevel.ci.CIStringSyntax

object SecurityHeaders {

  private val ContentSecurityPolicy =
    "default-src 'self'; " +
      "base-uri 'self'; " +
      "object-src 'none'; " +
      "frame-ancestors 'none'; " +
      "img-src 'self' https: data:; " +
      "script-src 'self'; " +
      "style-src 'self'; " +
      "connect-src 'self'; " +
      "manifest-src 'self'; " +
      "worker-src 'self'"

  private val BaseHeaders = List(
    Header.Raw(ci"Content-Security-Policy", ContentSecurityPolicy),
    Header.Raw(ci"X-Content-Type-Options", "nosniff"),
    Header.Raw(ci"X-Frame-Options", "DENY"),
    Header.Raw(ci"Referrer-Policy", "no-referrer"),
    Header.Raw(ci"Permissions-Policy", "camera=(), microphone=(), geolocation=()"),
  )

  private val HstsHeader =
    Header.Raw(ci"Strict-Transport-Security", "max-age=31536000; includeSubDomains")

  def apply(app: HttpApp[IO], hsts: Boolean): HttpApp[IO] =
    Kleisli { req =>
      val headers = if hsts then HstsHeader :: BaseHeaders else BaseHeaders
      app(req).map(resp => resp.withHeaders(resp.headers ++ Headers(headers)))
    }

}
