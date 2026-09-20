package com.masjidazanclock.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AzanAlarmPlugin.class);
        registerPlugin(QiblaCompassPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
