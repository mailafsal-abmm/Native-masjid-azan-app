package com.masjidazanclock.app;

import android.os.Bundle;
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
        // Without this, the phone's back button/gesture can silently do
        // nothing on some Android versions (especially 13/14, which
        // changed how back navigation works system-wide) — relying on
        // Capacitor's older default alone isn't reliable everywhere.
        // This: goes back within the app's own screen history if there is
        // any (e.g. Settings/Qibla open), otherwise exits/backgrounds the
        // app normally, exactly like any other Android app.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (getBridge() != null && getBridge().getWebView().canGoBack()) {
                    getBridge().getWebView().goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }
}
