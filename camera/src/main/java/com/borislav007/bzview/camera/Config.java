package com.borislav007.bzview.camera;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Persistent settings of the camera phone. */
final class Config {

    private static final String PREFS = "bzview_camera";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_CAMERA = "camera_id";
    private static final String KEY_ASKED_BATTERY = "asked_battery_exemption";

    static synchronized boolean askedBatteryExemption(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED_BATTERY, false);
    }

    static synchronized void markAskedBatteryExemption(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED_BATTERY, true).apply();
    }

    private Config() {
    }

    /** Camera id chosen by the user, or null to use the default back camera. */
    static synchronized String selectedCamera(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CAMERA, null);
    }

    static synchronized void setSelectedCamera(Context context, String id) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CAMERA, id).apply();
    }

    /** Secret that viewers need to connect. Created once, kept across restarts. */
    static synchronized String token(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_TOKEN, null);
        if (token == null) {
            byte[] bytes = new byte[6];
            new SecureRandom().nextBytes(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            token = sb.toString();
            prefs.edit().putString(KEY_TOKEN, token).apply();
        }
        return token;
    }
}
