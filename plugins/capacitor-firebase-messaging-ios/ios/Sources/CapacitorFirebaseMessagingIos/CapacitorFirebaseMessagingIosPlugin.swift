import Foundation
import Capacitor
import FirebaseCore
import FirebaseMessaging

@objc(CapacitorFirebaseMessagingIosPlugin)
public class CapacitorFirebaseMessagingIosPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "CapacitorFirebaseMessagingIosPlugin"
    public let jsName = "CapacitorFirebaseMessagingIos"
    public let pluginMethods: [CAPPluginMethod] = []
}
