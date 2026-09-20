package com.masjidazanclock.app;

import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * QiblaCompassPlugin
 * -------------------
 * Streams a smooth, accurate compass heading using the phone's fused
 * ROTATION_VECTOR sensor (which combines accelerometer + magnetometer +
 * gyroscope internally) instead of the raw, jitter-prone DeviceOrientation
 * events available to a plain website. An exponential smoothing filter
 * removes small jitter, and — if the app has supplied the current GPS
 * coordinates via setLocation() — true-north correction is applied using
 * Android's GeomagneticField model.
 */
@CapacitorPlugin(name = "QiblaCompass")
public class QiblaCompassPlugin extends Plugin implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private float smoothedHeading = -1f;
    private float declination = 0f;
    private long lastEmit = 0L;

    private static final float SMOOTHING = 0.15f;
    private static final long EMIT_INTERVAL_MS = 100L;

    @Override
    public void load() {
        sensorManager = (SensorManager) getContext().getSystemService(android.content.Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
    }

    @PluginMethod
    public void startWatching(PluginCall call) {
        if (sensorManager != null && rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
            call.resolve();
        } else {
            call.reject("Rotation vector sensor not available on this device");
        }
    }

    @PluginMethod
    public void stopWatching(PluginCall call) {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        call.resolve();
    }

    @PluginMethod
    public void setLocation(PluginCall call) {
        Double lat = call.getDouble("lat");
        Double lng = call.getDouble("lng");
        if (lat != null && lng != null) {
            GeomagneticField field = new GeomagneticField(
                lat.floatValue(), lng.floatValue(), 0f, System.currentTimeMillis()
            );
            declination = field.getDeclination();
        }
        call.resolve();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;

        float[] rotationMatrix = new float[9];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);

        float[] orientation = new float[3];
        SensorManager.getOrientation(rotationMatrix, orientation);

        float azimuthRad = orientation[0];
        float azimuthDeg = (float) Math.toDegrees(azimuthRad);
        azimuthDeg = (azimuthDeg + declination + 360f) % 360f;

        if (smoothedHeading < 0) {
            smoothedHeading = azimuthDeg;
        } else {
            float diff = ((azimuthDeg - smoothedHeading + 540f) % 360f) - 180f;
            smoothedHeading = (smoothedHeading + SMOOTHING * diff + 360f) % 360f;
        }

        long now = System.currentTimeMillis();
        if (now - lastEmit >= EMIT_INTERVAL_MS) {
            lastEmit = now;
            JSObject data = new JSObject();
            data.put("heading", smoothedHeading);
            notifyListeners("heading", data);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    protected void handleOnDestroy() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        super.handleOnDestroy();
    }
}
