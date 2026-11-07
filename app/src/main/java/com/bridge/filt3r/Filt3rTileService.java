package com.bridge.filt3r;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: shows the tunnel state and turns it on or off. */
public class Filt3rTileService extends TileService implements TunnelState.Listener {

    @Override
    public void onStartListening() {
        super.onStartListening();
        TunnelState.register(this);
        update(TunnelState.get());
    }

    @Override
    public void onStopListening() {
        TunnelState.unregister(this);
        super.onStopListening();
    }

    @Override
    public void onTunnelState(TunnelState.State state) {
        update(state);
    }

    @Override
    public void onClick() {
        super.onClick();
        TunnelState.State state = TunnelState.get();
        if (state == TunnelState.State.RUNNING || state == TunnelState.State.STARTING) {
            startService(new Intent(this, Filt3rVpnService.class).setAction(Filt3rVpnService.ACTION_STOP));
        } else if (VpnService.prepare(this) == null) {
            try {
                startForegroundService(new Intent(this, Filt3rVpnService.class));
            } catch (ForegroundServiceStartNotAllowedException e) {
                openApp(); // the system did not allow a start from the tile: let the app do it
            }
        } else {
            openApp(); // VPN consent is missing: only an activity can ask for it
        }
    }

    private void openApp() {
        Intent open = new Intent(MainActivity.ACTION_CONNECT)
                .setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    private void update(TunnelState.State state) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setLabel("filt3r");
        switch (state) {
            case RUNNING:
                tile.setState(Tile.STATE_ACTIVE);
                tile.setSubtitle("On");
                break;
            case STARTING:
                tile.setState(Tile.STATE_INACTIVE);
                tile.setSubtitle("Connecting. Tap to cancel.");
                break;
            case FAILED:
                tile.setState(Tile.STATE_INACTIVE);
                tile.setSubtitle("Failed");
                break;
            default:
                tile.setState(Tile.STATE_INACTIVE);
                tile.setSubtitle("Off");
                break;
        }
        tile.updateTile();
    }
}
