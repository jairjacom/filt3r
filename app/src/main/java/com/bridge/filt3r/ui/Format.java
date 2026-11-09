package com.bridge.filt3r.ui;

import android.content.Context;

import com.bridge.filt3r.R;

import java.util.Locale;

/** Number formatting shared by the screens. */
final class Format {

    private Format() {}

    /** 1536 -> "1.5 kB"; powers of 1024. */
    static String bytes(long b) {
        if (b < 1024) {
            return b + " B";
        }
        double v = b;
        String[] units = {"kB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, v < 10 ? "%.1f %s" : "%.0f %s", v, units[i]);
    }

    /** 125000 -> "2 min", 7500000 -> "2 h 5 min". */
    static String duration(Context ctx, long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) {
            return ctx.getString(R.string.duration_seconds, seconds);
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return ctx.getString(R.string.duration_minutes, minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return ctx.getString(R.string.duration_hours_minutes, hours, minutes % 60);
        }
        return ctx.getString(R.string.duration_days_hours, hours / 24, hours % 24);
    }
}
