package com.bridge.filt3r;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Toast;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import com.bridge.filt3r.core.nodes.NodeInfo;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.ui.FloatingToolbar;
import com.bridge.filt3r.ui.NodesPanel;
import com.bridge.filt3r.ui.StatusView;

import java.util.List;

public class MainActivity extends Activity
        implements TunnelState.Listener, StatusView.Actions, NodesPanel.Backend {

    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;
    private static final long STATS_INTERVAL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private StatusView status;
    private FloatingToolbar toolbar;
    private FrameLayout frame;
    private View panel;
    private Settings settings;
    private final OnBackInvokedCallback closePanelOnBack = this::closePanel;

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
        settings = new Settings(this);
        frame = new FrameLayout(this);
        status = new StatusView(this, this);
        frame.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        toolbar = new FloatingToolbar(this);
        frame.addView(toolbar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        toolbar.addItem(R.drawable.ic_nodes, "Nodes", this::openNodes);
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
        String notice = Filt3rVpnService.takeNotice();
        if (notice != null) {
            Toast.makeText(this, notice, Toast.LENGTH_LONG).show();
        }
        if (TunnelState.get() != TunnelState.State.RUNNING) {
            return;
        }
        StatusView.Snapshot s = new StatusView.Snapshot();
        NodeInfo node = Filt3rVpnService.currentNode();
        if (node != null) {
            s.nodeName = node.node.name;
            s.nodeMs = node.medianMs;
            s.nodeIpv6 = node.ipv6;
            s.nodeIp = node.node.host;
        }
        s.trafficBytes = Filt3rVpnService.trafficBytes();
        s.dnsCounts = Filt3rVpnService.dnsCounts();
        String[] resolver = Filt3rVpnService.resolver();
        if (resolver != null) {
            s.resolverName = resolver[0];
            s.resolverIp = resolver[1];
        }
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

    private void openNodes() {
        if (panel != null) {
            return;
        }
        panel = new NodesPanel(this, this, this::closePanel);
        frame.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, closePanelOnBack);
    }

    private void closePanel() {
        if (panel == null) {
            return;
        }
        frame.removeView(panel);
        panel = null;
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closePanelOnBack);
    }

    @Override
    public List<NodeInfo> nodes() {
        return Filt3rVpnService.nodeStatus();
    }

    @Override
    public boolean retest(Runnable done) {
        return Filt3rVpnService.retestNodes(done);
    }

    @Override
    public boolean pin(ShadowsocksNode node, Runnable done) {
        return Filt3rVpnService.pinNode(node, done);
    }

    @Override
    public void unpin() {
        Filt3rVpnService.unpinNode();
    }

    @Override
    public boolean sortBySpeed() {
        return settings.nodeSortBySpeed();
    }

    @Override
    public void setSortBySpeed(boolean bySpeed) {
        settings.setNodeSortBySpeed(bySpeed);
    }
}
