package com.bridge.filt3r;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Launcher shortcuts (long-press the app icon). Built at run time so they work for any applicationId. */
final class Shortcuts {

    private Shortcuts() {}

    /** Publishes Connect while the tunnel is down and Disconnect while it is up, plus Nodes and Query log. */
    static void publish(Context ctx) {
        ShortcutManager manager = ctx.getSystemService(ShortcutManager.class);
        if (manager == null) {
            return;
        }
        TunnelState.State state = TunnelState.get();
        boolean up = state == TunnelState.State.RUNNING || state == TunnelState.State.STARTING;
        List<ShortcutInfo> list = new ArrayList<>();
        if (up) {
            list.add(build(ctx, "disconnect", ctx.getString(R.string.shortcut_disconnect), R.drawable.ic_power, MainActivity.ACTION_DISCONNECT));
        } else {
            list.add(build(ctx, "connect", ctx.getString(R.string.shortcut_connect), R.drawable.ic_shield, MainActivity.ACTION_CONNECT));
        }
        list.add(build(ctx, "nodes", ctx.getString(R.string.shortcut_nodes), R.drawable.ic_nodes, MainActivity.ACTION_OPEN_NODES));
        list.add(build(ctx, "log", ctx.getString(R.string.shortcut_query_log), R.drawable.ic_log, MainActivity.ACTION_OPEN_LOG));
        try {
            if (!manager.setDynamicShortcuts(list)) {
                Log.w("Shortcuts", "setDynamicShortcuts was refused (rate limit?)");
            }
        } catch (RuntimeException e) {
            Log.w("Shortcuts", "could not publish shortcuts: " + e);
        }
    }

    private static ShortcutInfo build(Context ctx, String id, String label, int icon, String action) {
        Intent intent = new Intent(action).setClass(ctx, MainActivity.class);
        return new ShortcutInfo.Builder(ctx, id)
                .setShortLabel(label)
                .setIcon(Icon.createWithResource(ctx, icon))
                .setIntent(intent)
                .build();
    }
}
