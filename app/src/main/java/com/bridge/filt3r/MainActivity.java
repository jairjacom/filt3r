package com.bridge.filt3r;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import com.bridge.filt3r.core.nodes.NodeInfo;
import com.bridge.filt3r.ui.FloatingToolbar;
import com.bridge.filt3r.ui.StatusView;

public class MainActivity extends Activity implements TunnelState.Listener, StatusView.Actions {

    // The toolbar has no items yet and stays hidden. Panels add their own in later PRs.

    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;
    private static final long STATS_INTERVAL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private StatusView status;
    private FloatingToolbar toolbar;

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, STATS_INTERVAL_MS);
        }
    };

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        toolbar.poke();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout frame = new FrameLayout(this);
        status = new StatusView(this, this);
        frame.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        toolbar = new FloatingToolbar(this);
        frame.addView(toolbar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(frame);

        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }

    @Override
    protected void onStart() {
        super.onStart();
        TunnelState.register(this);
        onTunnelState(TunnelState.get());
        handler.post(statsTick);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(statsTick);
        toolbar.pause();
        TunnelState.unregister(this);
        super.onStop();
    }

    @Override
    public void onTunnelState(TunnelState.State state) {
        status.setState(StatusView.State.valueOf(state.name()));
    }

    private void refresh() {
        if (TunnelState.get() != TunnelState.State.RUNNING) {
            return;
        }
        StatusView.Snapshot s = new StatusView.Snapshot();
        NodeInfo node = Filt3rVpnService.currentNode();
        if (node != null) {
            s.nodeName = node.node.name;
            s.nodeMs = node.medianMs;
            s.nodeIpv6 = node.ipv6;
        }
        s.trafficBytes = Filt3rVpnService.trafficBytes();
        s.dnsCounts = Filt3rVpnService.dnsCounts();
        status.update(s);
    }

    @Override
    public void connect() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN);
        } else {
            startVpn();
        }
    }

    @Override
    public void disconnect() {
        startService(new Intent(this, Filt3rVpnService.class).setAction(Filt3rVpnService.ACTION_STOP));
    }

    @Override
    public void restart() {
        startForegroundService(new Intent(this, Filt3rVpnService.class)
                .setAction(Filt3rVpnService.ACTION_RESTART));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            startVpn();
        }
    }

    private void startVpn() {
        startForegroundService(new Intent(this, Filt3rVpnService.class));
    }
}
