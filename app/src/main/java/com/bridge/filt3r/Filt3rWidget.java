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
            status = ctx.getString(state == TunnelState.State.RUNNING ? R.string.widget_status_on : R.string.widget_status_connecting);
            hint = ctx.getString(state == TunnelState.State.RUNNING ? R.string.widget_hint_stop : R.string.widget_hint_cancel);
            Intent stop = new Intent(ctx, Filt3rVpnService.class)
                    .setAction(Filt3rVpnService.ACTION_STOP);
            tap = PendingIntent.getService(ctx, 7101, stop, flags);
        } else {
            status = ctx.getString(state == TunnelState.State.FAILED ? R.string.widget_status_failed : R.string.widget_status_off);
            hint = ctx.getString(R.string.widget_hint_connect);
            Intent connect = new Intent(ctx, MainActivity.class)
                    .setAction(MainActivity.ACTION_CONNECT);
            tap = PendingIntent.getActivity(ctx, 7102, connect, flags);
        }
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        views.setTextViewText(R.id.widget_status, status);
        views.setImageViewResource(R.id.widget_icon, icon);
        views.setContentDescription(R.id.widget_root,
                ctx.getString(R.string.widget_content_description, ctx.getString(R.string.main_brand_name), status, hint));
        views.setOnClickPendingIntent(R.id.widget_root, tap);
        manager.updateAppWidget(ids, views);
    }
}
