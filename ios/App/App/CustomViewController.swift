import UIKit
import WebKit
import Capacitor

class CustomViewController: CAPBridgeViewController, WKNavigationDelegate {

    override func viewDidLoad() {
        super.viewDidLoad()
        bridge?.webView?.navigationDelegate = self
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }

        let scheme = url.scheme ?? ""
        let host = url.host ?? ""

        // Keep whitelisted hosts in the WebView
        if scheme == "http" || scheme == "https" {
            if host.hasSuffix("id.twitch.tv") || host.hasSuffix("onrender.com") || host.hasSuffix("twitch.tv") {
                decisionHandler(.allow)
                return
            }
        }

        // Everything else (mailto:, twitch://, external https) opens via system
        if UIApplication.shared.canOpenURL(url) {
            UIApplication.shared.open(url)
        }
        decisionHandler(.cancel)
    }
}
