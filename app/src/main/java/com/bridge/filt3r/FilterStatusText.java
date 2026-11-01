package com.bridge.filt3r;

import com.bridge.filt3r.core.filter.FilterStatus;

import java.util.Locale;

/** Turns the filter-list status into the lines the screens show. */
final class FilterStatusText {

    private FilterStatusText() {
    }

    /** One or two lines; the second (only after a failure) says what is still in use. */
    static String describe(FilterStatus.Snapshot s, boolean running, long now) {
        switch (s.state) {
            case DOWNLOADING:
                return "Downloading filter lists (" + Math.min(s.done + 1, s.total) + " of " + s.total + ")...";
            case OK:
                return String.format(Locale.US, "%,d blocked names  ·  updated %s", s.entries, ago(s.updatedMs, now));
            case FAILED:
                StringBuilder sb = new StringBuilder("Filter update failed: ").append(s.error);
                if (s.retryAtMs > now) {
                    sb.append("  ·  retry in ").append(Math.max(1, (s.retryAtMs - now + 59_999) / 60_000)).append(" min");
                }
                if (s.updatedMs > 0) {
                    sb.append(String.format(Locale.US, "\nStill using the lists from %s (%,d names)",
                            ago(s.updatedMs, now), s.entries));
                }
                return sb.toString();
            default:
                return running ? "Loading filter lists..." : "Filter lists are loaded when the VPN starts.";
        }
    }

    static String ago(long thenMs, long nowMs) {
        long min = Math.max(0, (nowMs - thenMs) / 60_000);
        if (min < 1) {
            return "just now";
        }
        if (min < 60) {
            return min + " min ago";
        }
        long hours = min / 60;
        if (hours < 48) {
            return hours + " h ago";
        }
        return hours / 24 + " days ago";
    }
}
