package com.bridge.filt3r;

import android.content.Context;

import com.bridge.filt3r.core.filter.FilterStatus;

/** Turns the filter-list status into the lines the screens show. */
final class FilterStatusText {

    private FilterStatusText() {
    }

    /** A short status for the Status card: a summary line, and a second line after a failure. */
    static String describe(Context ctx, FilterStatus.Snapshot s, boolean running, long now) {
        return describe(ctx, s, running, now, false);
    }

    /** As {@link #describe(Context, FilterStatus.Snapshot, boolean, long)}; {@code detail} adds one line per list. */
    static String describe(Context ctx, FilterStatus.Snapshot s, boolean running, long now, boolean detail) {
        StringBuilder sb = new StringBuilder();
        switch (s.state) {
            case DOWNLOADING:
                sb.append(ctx.getString(R.string.filter_status_downloading,
                        Math.min(s.done + 1, s.total), s.total));
                break;
            case OK:
                sb.append(ctx.getString(R.string.filter_status_ok,
                        s.unique >= 0 ? s.unique : s.entries, ago(ctx, s.updatedMs, now)));
                if (s.unique >= 0 && s.entries > s.unique) {
                    sb.append("\n").append(ctx.getString(R.string.filter_status_duplicates, s.entries - s.unique));
                }
                if (s.failedLists > 0) {
                    sb.append("\n").append(ctx.getResources().getQuantityString(R.plurals.filter_status_lists_failed,
                            s.failedLists, s.failedLists, s.error)).append(retryIn(ctx, s, now));
                }
                break;
            case FAILED:
                sb.append(ctx.getString(R.string.filter_status_failed, s.error)).append(retryIn(ctx, s, now));
                if (s.updatedMs > 0) {
                    sb.append("\n").append(ctx.getString(R.string.filter_status_still_using,
                            ago(ctx, s.updatedMs, now), s.unique >= 0 ? s.unique : s.entries));
                }
                break;
            default:
                sb.append(ctx.getString(running ? R.string.filter_status_loading : R.string.filter_status_idle));
        }
        if (detail && s.state == FilterStatus.State.OK) {
            for (String line : s.listLines) {
                sb.append("\n·  ").append(line);
            }
        }
        return sb.toString();
    }

    private static String retryIn(Context ctx, FilterStatus.Snapshot s, long now) {
        if (s.retryAtMs <= now) {
            return "";
        }
        long min = Math.max(1, (s.retryAtMs - now + 59_999) / 60_000);
        return ctx.getString(R.string.filter_status_retry_in, min);
    }

    static String ago(Context ctx, long thenMs, long nowMs) {
        long min = Math.max(0, (nowMs - thenMs) / 60_000);
        if (min < 1) {
            return ctx.getString(R.string.filter_status_ago_now);
        }
        if (min < 60) {
            return ctx.getString(R.string.filter_status_ago_min, min);
        }
        long hours = min / 60;
        if (hours < 48) {
            return ctx.getString(R.string.filter_status_ago_hours, hours);
        }
        return ctx.getString(R.string.filter_status_ago_days, hours / 24);
    }
}
