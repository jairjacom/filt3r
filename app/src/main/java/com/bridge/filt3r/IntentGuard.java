package com.bridge.filt3r;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * MainActivity must stay exported for the launcher, so any app could send it Connect or Disconnect.
 * Our own tile, widget and shortcuts carry a random per-install key; the activity ignores these
 * actions without it. Other apps cannot read the key.
 */
final class IntentGuard {
    private static final String PREFS = "intent_guard";
    private static final String KEY = "key";
    private static final String EXTRA = "com.bridge.filt3r.extra.KEY";

    private IntentGuard() {}

    /** Adds the key to an intent that our own tile, widget or shortcut sends to MainActivity. */
    static Intent attachKey(Context ctx, Intent intent) {
        return intent.putExtra(EXTRA, key(ctx));
    }

    /** True if the intent carries this install's key. */
    static boolean hasKey(Context ctx, Intent intent) {
        String given = intent.getStringExtra(EXTRA);
        return given != null && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), key(ctx).getBytes(StandardCharsets.UTF_8));
    }

    private static synchronized String key(Context ctx) {
        SharedPreferences prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = prefs.getString(KEY, null);
        if (key == null) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            key = Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE);
            prefs.edit().putString(KEY, key).apply();
        }
        return key;
    }
}
