package com.twitchnotify.app;

import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onStart() {
        super.onStart();
        WebView webView = getBridge().getWebView();
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri url = request.getUrl();
                String scheme = url.getScheme();

                // Only http/https on whitelisted hosts stay in the WebView
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String host = url.getHost();
                    if (host != null && (
                        host.endsWith("id.twitch.tv") ||
                        host.endsWith("onrender.com")
                    )) {
                        return false; // load in WebView
                    }
                }

                // Everything else (mailto:, twitch://, external https, etc.)
                // opens via system Intent
                Intent intent = new Intent(Intent.ACTION_VIEW, url);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            }
        });
    }
}
