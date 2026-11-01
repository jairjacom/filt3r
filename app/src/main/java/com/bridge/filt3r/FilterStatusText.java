package com.bridge.filt3r;

import com.bridge.filt3r.core.filter.FilterStatus;

import java.util.Locale;

/** Turns the filter-list status into the lines the screens show. */
final class FilterStatusText {

    private FilterStatusText() {
    }

    /** A short status for the Status card: a summary line, and a second line after a failure. */
    static String describe(FilterStatus.Snapshot s, boolean running, long now) {
        return describe(s, running, now, false);
    }

    /** As {@link #describe(FilterStatus.Snapshot, boolean, long)}; {@code detail} adds one line per list. */
    static String describe(FilterStatus.Snapshot s, boolean running, long now, boolean detail) {
        StringBuilder sb = new StringBuilder();
        switch (s.state) {
            case DOWNLOADING:
                sb.append("Downloading filter lists (").append(Math.min(s.done + 1, s.total)).append(" of ")
                        .append(s.total).append(")...");
                break;
            case OK:
                sb.append(String.format(Locale.US, "%,d blocked names  ·  updated %s",
                        s.unique >= 0 ? s.unique : s.entries, ago(s.updatedMs, now)));
                if (s.unique >= 0 && s.entries > s.unique) {
                    sb.append(String.format(Locale.US, "\n%,d duplicates removed", s.entries - s.unique));
                }
                if (s.failedLists > 0) {
                    sb.append("\n").append(s.failedLists).append(s.failedLists == 1 ? " list" : " lists")
                            .append(" failed: ").append(s.error).append(retryIn(s, now));
                }
                break;
            case FAILED:
                sb.append("Filter update failed: ").append(s.error).append(retryIn(s, now));
                if (s.updatedMs > 0) {
                    sb.append(String.format(Locale.US, "\nStill using the lists from %s (%,d names)",
                            ago(s.updatedMs, now), s.unique >= 0 ? s.unique : s.entries));
                }
                break;
            default:
                sb.append(running ? "Loading filter lists..." : "Filter lists are loaded when the VPN starts.");
        }
        if (detail && s.state == FilterStatus.State.OK) {
            for (String line : s.listLines) {
                sb.append("\n·  ").append(line);
            }
        }
        return sb.toString();
    }

    private static String retryIn(FilterStatus.Snapshot s, long now) {
        if (s.retryAtMs <= now) {
            return "";
        }
        long min = Math.max(1, (s.retryAtMs - now + 59_999) / 60_000);
        return "  ·  retry in " + min + " min";
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
