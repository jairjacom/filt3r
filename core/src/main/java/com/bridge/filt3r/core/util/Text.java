package com.bridge.filt3r.core.util;

/** Small string helpers that stay linear on hostile input (regex like "x+$" does not). */
public final class Text {

    private Text() {
    }

    /** Removes every trailing {@code c}. */
    public static String trimTrailing(String s, char c) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == c) {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }
}
