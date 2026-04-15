package com.twitch.frontend

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Scala.js facade for the Capacitor Push Notifications plugin.
  *
  * At runtime this resolves to:
  *   import { PushNotifications } from '@capacitor/push-notifications';
  * which Capacitor bundles into the native app shell.
  *
  * On the web (no Capacitor), the global `Capacitor` object will be absent
  * or `Capacitor.isNativePlatform()` returns false, so callers should
  * gate usage behind `CapacitorPush.isNative`.
  */
object CapacitorPush:

  // ── Capacitor global object ───────────────────────────────────────

  @js.native
  @JSGlobal("Capacitor")
  private object Capacitor extends js.Object:
    def isNativePlatform(): Boolean = js.native
    def getPlatform(): String       = js.native

  def isNative: Boolean =
    !js.isUndefined(js.Dynamic.global.Capacitor) && Capacitor.isNativePlatform()

  def platform: String =
    if isNative then Capacitor.getPlatform() else "web"

  // ── Push Notifications plugin (available on the Capacitor global) ─

  @js.native
  @JSGlobal("Capacitor.Plugins.PushNotifications")
  private object PushNotificationsPlugin extends js.Object:
    def requestPermissions(): js.Promise[PermissionStatus]          = js.native
    def register(): js.Promise[Unit]                                = js.native
    def addListener(event: String, cb: js.Function1[js.Any, Unit]): js.Promise[js.Any] = js.native

  // ── Result types ──────────────────────────────────────────────────

  @js.native
  trait PermissionStatus extends js.Object:
    val receive: String = js.native // "prompt" | "prompt-with-rationale" | "granted" | "denied"

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
    PushNotificationsPlugin.requestPermissions()

  def register(): js.Promise[Unit] =
    PushNotificationsPlugin.register()

  def onRegistration(cb: PushToken => Unit): js.Promise[js.Any] =
    PushNotificationsPlugin.addListener("registration", (data: js.Any) =>
      cb(data.asInstanceOf[PushToken])
    )

  def onRegistrationError(cb: RegistrationError => Unit): js.Promise[js.Any] =
    PushNotificationsPlugin.addListener("registrationError", (data: js.Any) =>
      cb(data.asInstanceOf[RegistrationError])
    )

  def onPushNotificationReceived(cb: PushNotificationSchema => Unit): js.Promise[js.Any] =
    PushNotificationsPlugin.addListener("pushNotificationReceived", (data: js.Any) =>
      cb(data.asInstanceOf[PushNotificationSchema])
    )

  def onPushNotificationActionPerformed(cb: ActionPerformed => Unit): js.Promise[js.Any] =
    PushNotificationsPlugin.addListener("pushNotificationActionPerformed", (data: js.Any) =>
      cb(data.asInstanceOf[ActionPerformed])
    )
