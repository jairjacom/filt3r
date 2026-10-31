package com.bridge.filt3r.ui;

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
}
