package com.bridge.filt3r;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements TunnelState.Listener {

    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;
    private static final long STATS_INTERVAL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView stats;
    private Button toggle;
    private Button restart;

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            stats.setText(Filt3rVpnService.statsText());
            handler.postDelayed(this, STATS_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 3, pad, pad);

        status = new TextView(this);
        status.setTextSize(20);
        toggle = new Button(this);
        restart = new Button(this);
        restart.setText("Restart");
        stats = new TextView(this);
        stats.setTextSize(12);

        toggle.setOnClickListener(v -> {
            TunnelState.State s = TunnelState.get();
            if (s == TunnelState.State.RUNNING || s == TunnelState.State.STARTING) {
                startService(new Intent(this, Filt3rVpnService.class)
                        .setAction(Filt3rVpnService.ACTION_STOP));
            } else {
                requestStart();
            }
        });
        restart.setOnClickListener(v -> startForegroundService(new Intent(this, Filt3rVpnService.class)
                .setAction(Filt3rVpnService.ACTION_RESTART)));

        root.addView(status);
        root.addView(toggle);
        root.addView(restart);
        root.addView(stats);
        setContentView(root);

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
        TunnelState.unregister(this);
        super.onStop();
    }

    @Override
    public void onTunnelState(TunnelState.State state) {
        status.setText(state.name());
        boolean on = state == TunnelState.State.RUNNING || state == TunnelState.State.STARTING;
        toggle.setText(on ? "Stop" : "Start");
        restart.setEnabled(state == TunnelState.State.RUNNING);
        if (!on) {
            stats.setText("");
        }
    }

    private void requestStart() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN);
        } else {
            startVpn();
        }
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
