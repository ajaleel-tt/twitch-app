package com.twitch.frontend

import scala.scalajs.js
import scala.scalajs.js.annotation.*

object CapacitorPush:

  @js.native
  @JSGlobal("Capacitor")
  private object Capacitor extends js.Object:
    def isNativePlatform(): Boolean = js.native
    def getPlatform(): String       = js.native

  private def plugin: js.Dynamic = js.Dynamic.global.Capacitor.Plugins.PushNotifications

  def isNative: Boolean =
    !js.isUndefined(js.Dynamic.global.Capacitor) && Capacitor.isNativePlatform()

  def platform: String =
    if isNative then Capacitor.getPlatform() else "web"

  // ── Result types ──────────────────────────────────────────────────

  @js.native
  trait PermissionStatus extends js.Object:
    val receive: String = js.native

  @js.native
  trait PushToken extends js.Object:
    val value: String = js.native

  @js.native
  trait RegistrationError extends js.Object:
    val error: String = js.native

  @js.native
  trait PushNotificationSchema extends js.Object:
    val title: String               = js.native
    val body: String                = js.native
    val data: js.UndefOr[js.Object] = js.native

  @js.native
  trait ActionPerformed extends js.Object:
    val notification: PushNotificationSchema = js.native

  // ── Public Scala API ──────────────────────────────────────────────

  def requestPermissions(): js.Promise[PermissionStatus] =
    plugin.requestPermissions().asInstanceOf[js.Promise[PermissionStatus]]

  def register(): js.Promise[Unit] =
    plugin.register().asInstanceOf[js.Promise[Unit]]

  def onRegistration(cb: PushToken => Unit): js.Promise[js.Any] =
    plugin.addListener("registration", { (data: js.Any) =>
      cb(data.asInstanceOf[PushToken])
    }: js.Function1[js.Any, Unit]).asInstanceOf[js.Promise[js.Any]]

  def onRegistrationError(cb: RegistrationError => Unit): js.Promise[js.Any] =
    plugin.addListener("registrationError", { (data: js.Any) =>
      cb(data.asInstanceOf[RegistrationError])
    }: js.Function1[js.Any, Unit]).asInstanceOf[js.Promise[js.Any]]

  def onPushNotificationReceived(cb: PushNotificationSchema => Unit): js.Promise[js.Any] =
    plugin.addListener("pushNotificationReceived", { (data: js.Any) =>
      cb(data.asInstanceOf[PushNotificationSchema])
    }: js.Function1[js.Any, Unit]).asInstanceOf[js.Promise[js.Any]]

  def onPushNotificationActionPerformed(cb: ActionPerformed => Unit): js.Promise[js.Any] =
    plugin.addListener("pushNotificationActionPerformed", { (data: js.Any) =>
      cb(data.asInstanceOf[ActionPerformed])
    }: js.Function1[js.Any, Unit]).asInstanceOf[js.Promise[js.Any]]
