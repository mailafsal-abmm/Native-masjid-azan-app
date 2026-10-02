package com.masjidazanclock.app;

import android.os.Bundle;
import android.webkit.ValueCallback;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AzanAlarmPlugin.class);
        registerPlugin(QiblaCompassPlugin.class);
        super.onCreate(savedInstanceState);

        // Always clear the WebView's cache on startup and reload. Android's
        // WebView can otherwise keep serving old cached HTML/JS/CSS even
        // after installing a fresh APK — showing outdated screens/behavior
        // until the cache naturally expires on its own. For an app like
        // this, where all content is bundled locally (nothing fetched over
        // a real network), clearing cache costs nothing noticeable — the
        // reload is instant — so it's safe to do unconditionally on every
        // launch rather than trying to track version numbers.
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().clearCache(true);
            getBridge().getWebView().reload();
        }

        // Explicit back-button handling using the modern AndroidX API.
        //
        // Settings and Qibla are shown/hidden with plain CSS classes in the
        // page's own JS, not real browser navigation — so WebView.canGoBack()
        // (what the previous version of this checked) is ALWAYS false while
        // either is open, meaning the app always jumped straight to exiting
        // instead of closing the open panel first. That was the real bug.
        //
        // The fix: ask the page's own JS (via handleAndroidBack(), defined
        // in index.html) whether something is open and gets closed by this
        // press. Only exit/minimize the app when JS reports nothing was open.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (getBridge() == null || getBridge().getWebView() == null) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    return;
                }
                getBridge().getWebView().evaluateJavascript(
                    "(function(){ try { return window.handleAndroidBack ? window.handleAndroidBack() : false; } catch(e) { return false; } })()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String result) {
                            boolean handledByPage = "true".equals(result);
                            if (!handledByPage) {
                                setEnabled(false);
                                getOnBackPressedDispatcher().onBackPressed();
                            }
                        }
                    }
                );
            }
        });
    }
}
