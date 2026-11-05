package com.bridge.filt3r;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import java.util.ArrayList;
import java.util.List;

/** Launcher shortcuts (long-press the app icon). Built at run time so they work for any applicationId. */
final class Shortcuts {

    private Shortcuts() {}

    static void publish(Context ctx) {
        ShortcutManager manager = ctx.getSystemService(ShortcutManager.class);
        if (manager == null) {
            return;
        }
        List<ShortcutInfo> list = new ArrayList<>();
        list.add(build(ctx, "connect", "Connect", R.drawable.ic_shield, MainActivity.ACTION_CONNECT));
        list.add(build(ctx, "disconnect", "Disconnect", R.drawable.ic_shield, MainActivity.ACTION_DISCONNECT));
        list.add(build(ctx, "nodes", "Nodes", R.drawable.ic_nodes, MainActivity.ACTION_OPEN_NODES));
        list.add(build(ctx, "log", "Query log", R.drawable.ic_log, MainActivity.ACTION_OPEN_LOG));
        manager.setDynamicShortcuts(list);
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
