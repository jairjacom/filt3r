package com.bridge.filt3r;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Home-screen widget: shows the tunnel status and toggles it with a tap. */
public class Filt3rWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        refresh(context);
    }

    static void refresh(Context ctx) {
        AppWidgetManager manager = AppWidgetManager.getInstance(ctx);
        int[] ids = manager.getAppWidgetIds(new ComponentName(ctx, Filt3rWidget.class));
        if (ids.length == 0) {
            return;
        }
        TunnelState.State state = TunnelState.get();
        String status;
        String hint;
        int icon = state == TunnelState.State.RUNNING ? R.drawable.ic_shield_check : R.drawable.ic_shield_funnel;
        PendingIntent tap;
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        if (state == TunnelState.State.RUNNING || state == TunnelState.State.STARTING) {
            status = state == TunnelState.State.RUNNING ? "On" : "Connecting";
            hint = state == TunnelState.State.RUNNING ? "Tap to stop." : "Tap to cancel.";
            Intent stop = new Intent(ctx, Filt3rVpnService.class)
                    .setAction(Filt3rVpnService.ACTION_STOP);
            tap = PendingIntent.getService(ctx, 7101, stop, flags);
        } else {
            status = state == TunnelState.State.FAILED ? "Failed" : "Off";
            hint = "Tap to connect.";
            Intent connect = new Intent(ctx, MainActivity.class)
                    .setAction(MainActivity.ACTION_CONNECT);
            tap = PendingIntent.getActivity(ctx, 7102, connect, flags);
        }
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        views.setTextViewText(R.id.widget_status, status);
        views.setImageViewResource(R.id.widget_icon, icon);
        views.setContentDescription(R.id.widget_root, "filt3r, " + status + ". " + hint);
        views.setOnClickPendingIntent(R.id.widget_root, tap);
        manager.updateAppWidget(ids, views);
    }
}
