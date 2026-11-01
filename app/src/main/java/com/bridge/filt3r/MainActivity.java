package com.bridge.filt3r;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Toast;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.EditText;

import com.bridge.filt3r.core.nodes.NodeInfo;
import com.bridge.filt3r.core.filter.FilterStatus;
import com.bridge.filt3r.core.filter.UserOverrides;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.socks.FailureMode;
import com.bridge.filt3r.ui.DockedToolbar;
import com.bridge.filt3r.ui.ExemptAppsPanel;
import com.bridge.filt3r.ui.FloatingToolbar;
import com.bridge.filt3r.ui.NodesPanel;
import com.bridge.filt3r.ui.QueryLogPanel;
import com.bridge.filt3r.ui.SettingsPanel;
import com.bridge.filt3r.ui.StatusView;
import com.bridge.filt3r.ui.SwipeRule;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class MainActivity extends Activity
        implements TunnelState.Listener, StatusView.Actions, NodesPanel.Backend, SettingsPanel.Backend,
        QueryLogPanel.Backend, ExemptAppsPanel.Backend {

    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;
    private static final String KEY_SECTION = "section";
    private static final int SECTION_COUNT = 5;
    private static final long SLIDE_MS = 200;
    private static final long STATS_INTERVAL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private StatusView status;
    private FloatingToolbar toolbar;
    private DockedToolbar dock;
    private boolean docked;
    private int section;
    private VelocityTracker velocity;
    private float downX;
    private float downY;
    private boolean swipeAllowed;
    private FrameLayout frame;
    private View panel;
    private Settings settings;
    private UserOverrides overrides;
    private FilterStatus.State lastFilterState = FilterStatus.State.NONE;
    private String lastFilterError = "";
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
        if (docked) {
            trackSwipe(ev);
        } else {
            toolbar.poke();
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);
        overrides = new UserOverrides(new File(getFilesDir(), "filter/additionalHosts.txt"));
        Appearance.apply(this, settings);
        frame = new FrameLayout(this);
        status = new StatusView(this, this);
        frame.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        docked = Settings.TOOLBAR_DOCKED.equals(settings.toolbarStyle());
        View root = frame;
        if (docked) {
            dock = new DockedToolbar(this, this::selectSection);
            dock.addItem(R.drawable.ic_status, "Status");
            dock.addItem(R.drawable.ic_nodes, "Nodes");
            dock.addItem(R.drawable.ic_apps, "Exempt apps");
            dock.addItem(R.drawable.ic_log, "Query log");
            dock.addItem(R.drawable.ic_settings, "Settings");
            dock.select(0);
            FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            dockParams.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
            frame.addView(dock, dockParams);
        } else {
            toolbar = new FloatingToolbar(this);
            frame.addView(toolbar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM));
            toolbar.addItem(R.drawable.ic_nodes, "Nodes",
                    () -> openPanel(() -> new NodesPanel(this, this, this::closePanel)));
            toolbar.addItem(R.drawable.ic_apps, "Exempt apps",
                    () -> openPanel(() -> new ExemptAppsPanel(this, this, this::closePanel)));
            toolbar.addItem(R.drawable.ic_log, "Query log",
                    () -> openPanel(() -> new QueryLogPanel(this, this, this::closePanel)));
            toolbar.addItem(R.drawable.ic_settings, "Settings",
                    () -> openPanel(() -> new SettingsPanel(this, this, this::closePanel)));
            toolbar.setAutoHide(!settings.toolbarPinned());
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        if (savedInstanceState != null) {
            if (docked) {
                showSection(savedInstanceState.getInt(KEY_SECTION), 0);
            } else if (savedInstanceState.getInt(KEY_SECTION) > 0) {
                int index = savedInstanceState.getInt(KEY_SECTION);
                openPanel(() -> sectionPanel(index));
            }
        }

        // Ask on a fresh launch only, and only while the permission is missing (not again after every rotation).
        if (savedInstanceState == null
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (docked) {
            outState.putInt(KEY_SECTION, section);
        } else {
            outState.putInt(KEY_SECTION, panel instanceof NodesPanel ? 1
                    : panel instanceof ExemptAppsPanel ? 2
                    : panel instanceof QueryLogPanel ? 3
                    : panel instanceof SettingsPanel ? 4 : 0);
        }
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
        if (toolbar != null) toolbar.pause();
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
        FilterStatus.Snapshot filter = FilterStatus.get();
        if (lastFilterState == FilterStatus.State.DOWNLOADING && filter.state != lastFilterState) {
            if (filter.state == FilterStatus.State.OK) {
                Toast.makeText(this, String.format(java.util.Locale.US, "Filter lists updated: %,d blocked names",
                        filter.unique >= 0 ? filter.unique : filter.entries)
                        + (filter.failedLists > 0 ? "; " + filter.failedLists + " list(s) failed: " + filter.error : ""),
                        Toast.LENGTH_LONG).show();
            } else if (filter.state == FilterStatus.State.FAILED && !filter.error.equals(lastFilterError)) {
                lastFilterError = filter.error;
                Toast.makeText(this, "Filter update failed: " + filter.error, Toast.LENGTH_LONG).show();
            }
        }
        lastFilterState = filter.state;
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
        s.filterText = FilterStatusText.describe(filter, true, System.currentTimeMillis());
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

    private void openPanel(Supplier<View> factory) {
        if (panel != null) {
            return;
        }
        panel = factory.get();
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
        if (docked) {
            section = 0;
            dock.select(0);
        }
    }

    private void selectSection(int index) {
        if (index != section) {
            dock.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK);
            showSection(index, Integer.signum(index - section));
        }
    }

    /** Shows section {@code index} (0 = status, no panel). {@code dir} is +1, -1 or 0 for no slide. */
    private void showSection(int index, int dir) {
        closePanel();
        section = index;
        dock.select(index);
        if (index == 0) {
            return;
        }
        panel = sectionPanel(index);
        frame.addView(panel, frame.indexOfChild(dock), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, closePanelOnBack);
        if (dir != 0) {
            panel.setTranslationX(dir * frame.getWidth());
            panel.animate().translationX(0).setDuration(SLIDE_MS).start();
        }
    }

    private View sectionPanel(int index) {
        switch (index) {
            case 1:
                return new NodesPanel(this, this, this::closePanel);
            case 2:
                return new ExemptAppsPanel(this, this, this::closePanel);
            case 3:
                return new QueryLogPanel(this, this, this::closePanel);
            default:
                return new SettingsPanel(this, this, this::closePanel);
        }
    }

    private void changeSection(int target) {
        if (target < 0 || target >= SECTION_COUNT || target == section) {
            return;
        }
        frame.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK);
        showSection(target, Integer.signum(target - section));
    }

    /** Observes touches for horizontal swipes between sections. Never consumes the event. */
    private void trackSwipe(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swipeAllowed = !(getCurrentFocus() instanceof EditText);
                if (velocity == null) {
                    velocity = VelocityTracker.obtain();
                } else {
                    velocity.clear();
                }
                velocity.addMovement(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                if (velocity != null) {
                    velocity.addMovement(ev);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (velocity != null && swipeAllowed) {
                    velocity.addMovement(ev);
                    velocity.computeCurrentVelocity(1000);
                    int dir = SwipeRule.direction(ev.getX() - downX, ev.getY() - downY, velocity.getXVelocity());
                    if (dir != 0) {
                        changeSection(section + dir);
                    }
                }
                releaseVelocity();
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseVelocity();
                break;
            default:
                break;
        }
    }

    private void releaseVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
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

    // ---- SettingsPanel.Backend ----

    @Override
    public boolean running() {
        TunnelState.State s = TunnelState.get();
        return s == TunnelState.State.RUNNING || s == TunnelState.State.STARTING;
    }

    @Override
    public int subscriptionSlots() {
        return Settings.MAX_SUBSCRIPTIONS;
    }

    @Override
    public String subscriptionUrl(int slot) {
        for (Settings.Subscription s : settings.subscriptions()) {
            if (s.slot == slot) {
                return s.url;
            }
        }
        return "";
    }

    @Override
    public int subscriptionHours(int slot) {
        for (Settings.Subscription s : settings.subscriptions()) {
            if (s.slot == slot) {
                return s.intervalHours;
            }
        }
        return Settings.DEFAULT_INTERVAL_HOURS;
    }

    @Override
    public void setSubscription(int slot, String url, int hours) {
        String old = null;
        for (Settings.Subscription s : settings.subscriptions()) {
            if (s.slot == slot) {
                old = s.url;
            }
        }
        settings.setSubscription(slot, url, hours);
        if (old != null && !old.equals(url.trim())) {
            // The old subscription's node list holds its URL (often a token) and every node password.
            new File(getFilesDir(), "nodes_" + slot + ".txt").delete();
            new File(getFilesDir(), "nodes_" + slot + ".tmp").delete();
        }
    }

    @Override
    public boolean failClosed() {
        return settings.failureMode() == FailureMode.FAIL_CLOSED;
    }

    @Override
    public void setFailClosed(boolean closed) {
        settings.setFailureMode(closed ? FailureMode.FAIL_CLOSED : FailureMode.FAIL_OPEN);
    }

    @Override
    public String resolverName() {
        return settings.resolverName();
    }

    @Override
    public List<SettingsPanel.Resolver> resolvers() {
        List<SettingsPanel.Resolver> out = new ArrayList<>();
        out.add(new SettingsPanel.Resolver(Settings.DEFAULT_RESOLVER_NAME, Settings.DEFAULT_RESOLVER_STAMP, -1));
        for (ResolverStore.Info r : new ResolverStore(getFilesDir()).load()) {
            out.add(new SettingsPanel.Resolver(r.name, r.stamp, r.millis));
        }
        return out;
    }

    @Override
    public void setResolver(String name, String stamp) {
        settings.setResolver(name, stamp);
    }

    @Override
    public String checkResolver(String stamp) throws IOException {
        return Filt3rVpnService.checkResolver(stamp);
    }

    @Override
    public String filterListUrl() {
        return settings.filterListUrl();
    }

    @Override
    public String setFilterListUrl(String text) {
        try {
            settings.setFilterListUrl(Settings.normalizeFilterUrls(text));
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @Override
    public boolean queryLogEnabled() {
        return settings.queryLogEnabled();
    }

    @Override
    public void setQueryLogEnabled(boolean on) {
        settings.setQueryLogEnabled(on);
    }

    @Override
    public List<String> queryLines() {
        return QueryLogs.get(this).lines();
    }

    @Override
    public void clearQueryLog() {
        QueryLogs.get(this).clear();
    }

    @Override
    public Set<String> exemptApps() {
        return settings.exemptApps();
    }

    @Override
    public void setExemptApps(Set<String> packages) {
        settings.setExemptApps(packages);
    }

    // ---- allow/block rules (OverridesDialog.Backend) ----

    @Override
    public List<UserOverrides.Entry> rules() throws IOException {
        return overrides.list();
    }

    @Override
    public Boolean ruleFor(String host) throws IOException {
        return overrides.find(host);
    }

    @Override
    public String setRule(String host, boolean allow) throws IOException {
        return overrides.set(host, allow);
    }

    @Override
    public void removeRule(String host) throws IOException {
        overrides.remove(host);
    }

    @Override
    public boolean toolbarPinned() {
        return settings.toolbarPinned();
    }

    @Override
    public void setToolbarPinned(boolean pinned) {
        settings.setToolbarPinned(pinned);
        if (toolbar != null) toolbar.setAutoHide(!pinned);
    }

    @Override
    public String toolbarStyle() {
        return settings.toolbarStyle();
    }

    @Override
    public void setToolbarStyle(String style) {
        settings.setToolbarStyle(style);
        recreate();
    }

    @Override
    public String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    @Override
    public String filterStatus() {
        return FilterStatusText.describe(FilterStatus.get(), running(), System.currentTimeMillis(), true);
    }

    @Override
    public boolean updateFilters() {
        return Filt3rVpnService.updateFilters();
    }

    @Override
    public int source(ShadowsocksNode node) {
        return NodeStore.sourceOf(node);
    }

    @Override
    public int sourceCount() {
        return NodeStore.sourceCount();
    }

    // ---- Appearance (SettingsPanel.Backend). Colour changes restart the activity; the panel reopens. ----

    @Override
    public int themeMode() {
        return settings.themeMode();
    }

    @Override
    public void setThemeMode(int mode) {
        settings.setThemeMode(mode);
        // The system restarts the activity itself when the night mode really changes.
        getSystemService(android.app.UiModeManager.class).setApplicationNightMode(mode);
    }

    @Override
    public boolean pureBlack() {
        return settings.pureBlack();
    }

    @Override
    public void setPureBlack(boolean on) {
        settings.setPureBlack(on);
        recreate();
    }

    @Override
    public String colorScheme() {
        return settings.colorScheme();
    }

    @Override
    public void setColorScheme(String scheme) {
        settings.setColorScheme(scheme);
        recreate();
    }

    @Override
    public int seedColor() {
        return settings.seedColor();
    }

    @Override
    public void setSeedColor(int argb) {
        settings.setSeedColor(argb);
        recreate();
    }
}
