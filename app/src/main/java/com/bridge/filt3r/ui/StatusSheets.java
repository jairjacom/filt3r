package com.bridge.filt3r.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.bridge.filt3r.R;
import com.bridge.filt3r.core.http.IpLookup;
import com.google.android.material.color.MaterialColors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The windows the Status cards open: the node's IP lookup, the expanded traffic graph and the most
 * blocked names. All open as dialogs, so the screen behind them is blurred like everywhere else.
 */
public final class StatusSheets {

    /** Looks an IP up through the node ({@code null} asks for the address the world sees). Blocks. */
    public interface Lookup {
        IpLookup.Result lookup(String ip) throws IOException;
    }

    private static final long CACHE_MS = 5 * 60 * 1000L;
    private static final Map<String, Object[]> CACHE = new HashMap<>(); // server IP -> {exit, server, time}

    private StatusSheets() {
    }

    // ---- node lookup ----

    /**
     * Shows where the node is. Looks up the address websites see (through the node) and the server
     * address from the list; the answers are kept for a few minutes.
     */
    public static void showLookup(Context context, String serverIp, Lookup lookup, Runnable openNodes) {
        TextView body = text(context, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        body.setText(R.string.lookup_loading);
        body.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(body);
        String[] copyIp = {serverIp};
        AlertDialog dialog = Dialogs.builder(context)
                .setTitle(R.string.lookup_title)
                .setView(scroll)
                .setNegativeButton(R.string.lookup_close, null)
                .setNeutralButton(R.string.lookup_open_nodes, (d, w) -> openNodes.run())
                .setPositiveButton(R.string.lookup_copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("filt3r", copyIp[0]));
                    Messages.show(body, context.getString(R.string.lookup_copied), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT);
                })
                .show();
        padBody(body, context);

        Object[] cached;
        synchronized (CACHE) {
            cached = CACHE.get(serverIp);
        }
        if (cached != null && System.currentTimeMillis() - (Long) cached[2] < CACHE_MS) {
            IpLookup.Result exit = (IpLookup.Result) cached[0];
            IpLookup.Result server = (IpLookup.Result) cached[1];
            if (exit != null) {
                copyIp[0] = exit.ip;
            }
            body.setText(summary(context, serverIp, exit, server));
            return;
        }
        Handler ui = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            IpLookup.Result exit = null;
            IpLookup.Result server = null;
            try {
                exit = lookup.lookup(null);
            } catch (IOException | RuntimeException e) {
                // shown as "could not look it up" below if nothing else worked either
            }
            if (exit != null && exit.ip.equals(serverIp)) {
                server = exit;
            } else {
                try {
                    server = lookup.lookup(serverIp);
                } catch (IOException | RuntimeException e) {
                    // the exit address alone is still worth showing
                }
            }
            IpLookup.Result finalExit = exit;
            IpLookup.Result finalServer = server;
            if (exit != null || server != null) {
                synchronized (CACHE) {
                    CACHE.put(serverIp, new Object[] {exit, server, System.currentTimeMillis()});
                }
            }
            ui.post(() -> {
                if (!dialog.isShowing()) {
                    return;
                }
                if (finalExit == null && finalServer == null) {
                    body.setText(R.string.lookup_failed);
                    return;
                }
                if (finalExit != null) {
                    copyIp[0] = finalExit.ip;
                }
                body.setText(summary(context, serverIp, finalExit, finalServer));
            });
        }, "ip-lookup");
        t.setDaemon(true);
        t.start();
    }

    private static String summary(Context ctx, String serverIp, IpLookup.Result exit, IpLookup.Result server) {
        StringBuilder sb = new StringBuilder();
        if (exit != null) {
            describe(ctx, sb, ctx.getString(R.string.lookup_exit_label), exit);
        } else {
            sb.append(ctx.getString(R.string.lookup_exit_label)).append('\n')
                    .append(ctx.getString(R.string.lookup_unavailable)).append('\n');
        }
        sb.append('\n');
        if (server != null) {
            describe(ctx, sb, ctx.getString(R.string.lookup_server_label), server);
        } else {
            sb.append(ctx.getString(R.string.lookup_server_label)).append('\n').append(serverIp).append('\n')
                    .append(ctx.getString(R.string.lookup_unavailable)).append('\n');
        }
        if (exit != null && server != null) {
            sb.append('\n').append(ctx.getString(exit.ip.equals(server.ip)
                    ? R.string.lookup_same_ip : R.string.lookup_different_ip)).append('\n');
        }
        String provider = exit != null ? exit.provider : server != null ? server.provider : "";
        if (!provider.isEmpty()) {
            sb.append('\n').append(ctx.getString(R.string.lookup_source, provider));
        }
        return sb.toString();
    }

    private static void describe(Context ctx, StringBuilder sb, String label, IpLookup.Result r) {
        sb.append(label).append('\n');
        sb.append(r.ip).append("  ·  ").append(ctx.getString(r.isIpv6() ? R.string.lookup_family_6 : R.string.lookup_family_4))
                .append('\n');
        String place = join(", ", r.city, r.country);
        if (!place.isEmpty()) {
            sb.append(place).append('\n');
        }
        String network = join("  ·  ", r.isp, r.asn);
        if (!network.isEmpty()) {
            sb.append(network).append('\n');
        }
        if (r.hosting != null) {
            sb.append(ctx.getString(r.hosting ? R.string.lookup_hosting_yes : R.string.lookup_hosting_no)).append('\n');
        }
    }

    private static String join(String separator, String a, String b) {
        if (a.isEmpty()) {
            return b;
        }
        return b.isEmpty() ? a : a + separator + b;
    }

    // ---- traffic ----

    /** The expanded traffic graph with the peak and the session numbers; refreshes every two seconds. */
    public static void showTraffic(Context context, Supplier<StatusView.Snapshot> source) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        TrafficGraph graph = new TrafficGraph(context, true);
        TextView facts = text(context, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        TextView hint = text(context, com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        dim(hint);
        hint.setText(R.string.traffic_hint);
        box.addView(graph, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(facts);
        box.addView(hint);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        facts.setPadding(0, pad / 2, 0, 0);
        hint.setPadding(0, pad / 2, 0, 0);

        Handler handler = new Handler(Looper.getMainLooper());
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            StatusView.Snapshot s = source.get();
            if (s != null && s.graphDown != null && s.graphUp != null) {
                graph.setData(s.graphDown, s.graphUp);
            }
            facts.setText(trafficFacts(context, s));
            handler.postDelayed(tick[0], 2000);
        };
        AlertDialog dialog = Dialogs.builder(context)
                .setTitle(R.string.traffic_title)
                .setView(box)
                .setPositiveButton(R.string.lookup_close, null)
                .show();
        dialog.setOnDismissListener(d -> handler.removeCallbacksAndMessages(null));
        tick[0].run();
    }

    private static String trafficFacts(Context ctx, StatusView.Snapshot s) {
        if (s == null || s.trafficBytes == null) {
            return ctx.getString(R.string.traffic_not_running);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.getString(R.string.traffic_session_totals, Format.bytes(s.trafficBytes[1]),
                Format.bytes(s.trafficBytes[0])));
        sb.append('\n').append(ctx.getString(R.string.traffic_peak, Format.bytes(s.peakBps)));
        if (s.sessionStartMs > 0) {
            sb.append('\n').append(ctx.getString(R.string.traffic_running_for,
                    Format.duration(ctx, Math.max(0, System.currentTimeMillis() - s.sessionStartMs))));
        }
        return sb.toString();
    }

    // ---- DNS ----

    /**
     * The most blocked names from the query log. If the log is off the window says so and offers the
     * switch; it is never turned on without a tap.
     */
    public static void showDns(Context context, boolean logOn, List<String> lines, Runnable turnOnLog,
                               Runnable viewBlocked) {
        TextView body = text(context, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = Dialogs.builder(context)
                .setTitle(R.string.dns_sheet_title)
                .setView(body)
                .setNegativeButton(R.string.lookup_close, null);
        if (!logOn) {
            body.setText(R.string.dns_sheet_log_off);
            builder.setPositiveButton(R.string.dns_sheet_turn_on, (d, w) -> turnOnLog.run());
        } else {
            List<String> top = topBlocked(lines, 5);
            if (top.isEmpty()) {
                body.setText(R.string.dns_sheet_none);
            } else {
                StringBuilder sb = new StringBuilder(context.getString(R.string.dns_sheet_top_heading)).append('\n');
                for (int i = 0; i < top.size(); i++) {
                    sb.append('\n').append(i + 1).append(".  ").append(top.get(i));
                }
                body.setText(sb.toString());
            }
            builder.setPositiveButton(R.string.dns_sheet_view_blocked, (d, w) -> viewBlocked.run());
        }
        builder.show();
        padBody(body, context);
    }

    /** "name  ×count" for the most blocked names, most first. Lines look like "MM-dd HH:mm:ss BLOCKED name". */
    static List<String> topBlocked(List<String> lines, int limit) {
        int timeLength = "MM-dd HH:mm:ss".length();
        Map<String, Integer> counts = new HashMap<>();
        for (String line : lines) {
            if (line.length() <= timeLength + 1) {
                continue;
            }
            String rest = line.substring(timeLength + 1);
            int space = rest.indexOf(' ');
            if (space < 0 || !rest.substring(0, space).equals("BLOCKED")) {
                continue;
            }
            counts.merge(rest.substring(space + 1), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < limit; i++) {
            out.add(sorted.get(i).getKey() + "  ×" + sorted.get(i).getValue());
        }
        return out;
    }

    // ---- helpers ----

    private static TextView text(Context context, int appearance) {
        TextView t = new TextView(context);
        t.setTextAppearance(appearance);
        return t;
    }

    private static void padBody(TextView body, Context context) {
        int pad = (int) (24 * context.getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad / 2, pad, pad / 2);
    }

    private static void dim(TextView t) {
        t.setTextColor(MaterialColors.getColor(t.getContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0));
    }
}
