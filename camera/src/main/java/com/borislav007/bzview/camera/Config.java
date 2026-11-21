package com.borislav007.bzview.camera;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Persistent settings of the camera phone. */
final class Config {

    private static final String PREFS = "bzview_camera";
    private static final String KEY_TOKEN = "token";

    private Config() {
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
