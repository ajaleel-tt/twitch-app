package com.twitchnotify.app;

import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final String APP_HOST = "twitch-app-grn6.onrender.com";
    private static final String TWITCH_OAUTH_HOST = "id.twitch.tv";

    @Override
    public void onStart() {
        super.onStart();
        WebView webView = getBridge().getWebView();
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String scheme = url.getScheme();

                // Only exact, trusted HTTPS origins stay in the WebView.
                if ("https".equalsIgnoreCase(scheme)) {
                    String host = url.getHost();
                    if (host != null &&
                        (APP_HOST.equalsIgnoreCase(host) || TWITCH_OAUTH_HOST.equalsIgnoreCase(host))) {
                        return false;
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
