package com.bridge.filt3r;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.VpnService;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedDispatcher;
import com.google.android.material.snackbar.Snackbar;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.widget.FrameLayout;
import android.widget.EditText;

import androidx.dynamicanimation.animation.DynamicAnimation;

import com.bridge.filt3r.core.filter.FilterCatalog;
import com.bridge.filt3r.core.nodes.NodeInfo;
import com.bridge.filt3r.core.filter.FilterStatus;
import com.bridge.filt3r.core.filter.UserOverrides;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.socks.FailureMode;
import com.bridge.filt3r.ui.BlurBehind;
import com.bridge.filt3r.ui.DockedToolbar;
import com.bridge.filt3r.ui.ExemptAppsPanel;
import com.bridge.filt3r.ui.FloatingToolbar;
import com.bridge.filt3r.ui.GlowBackground;
import com.bridge.filt3r.ui.Motion;
import com.bridge.filt3r.ui.NodesPanel;
import com.bridge.filt3r.ui.QueryLogPanel;
import com.bridge.filt3r.ui.SettingsPanel;
import com.bridge.filt3r.ui.Messages;
import com.bridge.filt3r.ui.StatusView;
import com.bridge.filt3r.ui.SwipeRule;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

public class MainActivity extends Activity
        implements TunnelState.Listener, StatusView.Actions, NodesPanel.Backend, SettingsPanel.Backend,
        QueryLogPanel.Backend, ExemptAppsPanel.Backend {

    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;
    private static final int REQUEST_EXPORT_LOG = 3;
    private boolean exportBlocked = true;
    private boolean exportAllowed = true;
    static final String ACTION_CONNECT = "com.bridge.filt3r.action.CONNECT";
    static final String ACTION_DISCONNECT = "com.bridge.filt3r.action.DISCONNECT";
    static final String ACTION_OPEN_NODES = "com.bridge.filt3r.action.OPEN_NODES";
    static final String ACTION_OPEN_LOG = "com.bridge.filt3r.action.OPEN_LOG";
    private static final String KEY_SECTION = "section";
    private static final String KEY_SETTINGS_PAGE = "settings_page";
    private static final int SECTION_COUNT = 5;
    private static final int SLIDE_MS = 300;
    private static final int HANDOVER_MS = 450;
    private static final int MAX_CONTENT_DP = 640;
    private static final long STATS_INTERVAL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private StatusView status;
    private FloatingToolbar toolbar;
    private DockedToolbar dock;
    private boolean restartPending;
    private BlurBehind blurStrip;
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
    private List<FilterCatalog.Entry> filterCatalog; // read from dnsfilter.conf on first use
    private FilterStatus.State lastFilterState = FilterStatus.State.NONE;
    private String lastFilterError = "";
    /** Closes the open panel; during the back gesture the panel shrinks, fades and follows the finger as a preview. */
    private final OnBackAnimationCallback closePanelOnBack = new OnBackAnimationCallback() {
        @Override
        public void onBackProgressed(BackEvent event) {
            if (panel != null && Effects.on(MainActivity.this, Effects.Effect.TRANSITIONS)) {
                float p = event.getProgress();
                float side = event.getSwipeEdge() == BackEvent.EDGE_LEFT ? 1f : -1f;
                float density = getResources().getDisplayMetrics().density;
                panel.setScaleX(1f - 0.15f * p);
                panel.setScaleY(1f - 0.15f * p);
                panel.setAlpha(1f - 0.5f * p);
                panel.setTranslationX(side * 32 * density * p);
            }
        }

        @Override
        public void onBackCancelled() {
            if (panel == null) {
                return;
            }
            if (Effects.on(MainActivity.this, Effects.Effect.TRANSITIONS)) {
                panel.animate().scaleX(1f).scaleY(1f).alpha(1f).translationX(0f).setDuration(150).start();
            } else {
                panel.setScaleX(1f);
                panel.setScaleY(1f);
                panel.setAlpha(1f);
                panel.setTranslationX(0f);
            }
        }

        @Override
        public void onBackInvoked() {
            if (panel instanceof NodesPanel && ((NodesPanel) panel).handleBack()) {
                onBackCancelled(); // the node details closed; the panel stays open
                return;
            }
            if (panel instanceof SettingsPanel && ((SettingsPanel) panel).handleBack()) {
                onBackCancelled(); // undo the shrink preview: the panel stays open
                return; // went back from the block-lists page to the main Settings page
            }
            closePanelAnimated();
        }
    };

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
        if (blurStrip != null) {
            blurStrip.pulse();
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
        // Keep the system splash until the shield animation has finished, then hand it over to the Status screen.
        getSplashScreen().setOnExitAnimationListener(splash -> {
            long wait = 0;
            if (splash.getIconAnimationStart() != null && splash.getIconAnimationDuration() != null) {
                wait = splash.getIconAnimationStart().plus(splash.getIconAnimationDuration()).toEpochMilli()
                        - System.currentTimeMillis();
            }
            wait = Math.max(0, Math.min(1000, wait));
            if (Effects.ms(this, Effects.Effect.TRANSITIONS, 1) == 0) {
                splash.remove();
                return;
            }
            if (!handOverSplash(splash, wait)) {
                splash.animate().setStartDelay(wait).alpha(0f).setDuration(300)
                        .withEndAction(splash::remove).start();
            }
        });
        settings = new Settings(this);
        overrides = new UserOverrides(new File(getFilesDir(), "filter/additionalHosts.txt"));
        Appearance.apply(this, settings);
        frame = new FrameLayout(this);
        glow = new GlowBackground(this);
        frame.addView(glow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        status = new StatusView(this, this);
        frame.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        docked = Settings.TOOLBAR_DOCKED.equals(settings.toolbarStyle());
        View root = frame;
        if (docked) {
            dock = new DockedToolbar(this, this::selectSection);
            dock.addItem(R.drawable.ic_status, getString(R.string.main_section_status));
            dock.addItem(R.drawable.ic_nodes, getString(R.string.main_section_nodes));
            dock.addItem(R.drawable.ic_apps, getString(R.string.main_section_exempt_apps));
            dock.addItem(R.drawable.ic_log, getString(R.string.main_section_query_log));
            dock.addItem(R.drawable.ic_settings, getString(R.string.main_section_settings));
            dock.select(0);
            // The screen and its panels sit in frame; the blur strip and the dock sit above it, so the
            // strip can copy the frame without drawing itself.
            FrameLayout outer = new FrameLayout(this);
            outer.addView(frame, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            if (Effects.on(this, Effects.Effect.BLUR)) {
                blurStrip = new BlurBehind(this, frame);
                outer.addView(blurStrip, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, (int) (112 * getResources().getDisplayMetrics().density),
                        Gravity.BOTTOM));
            }
            FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            dockParams.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
            outer.addView(dock, dockParams);
            root = outer;
        } else {
            toolbar = new FloatingToolbar(this);
            frame.addView(toolbar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM));
            toolbar.addItem(R.drawable.ic_nodes, getString(R.string.main_section_nodes),
                    () -> openPanel(() -> new NodesPanel(this, this, this::closePanelAnimated)));
            toolbar.addItem(R.drawable.ic_apps, getString(R.string.main_section_exempt_apps),
                    () -> openPanel(() -> new ExemptAppsPanel(this, this, this::closePanelAnimated)));
            toolbar.addItem(R.drawable.ic_log, getString(R.string.main_section_query_log),
                    () -> openPanel(() -> new QueryLogPanel(this, this, this::closePanelAnimated)));
            toolbar.addItem(R.drawable.ic_settings, getString(R.string.main_section_settings),
                    () -> openPanel(() -> new SettingsPanel(this, this, this::closePanelAnimated)));
            toolbar.setAutoHide(!settings.toolbarPinned());
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(v, insets);
            // With the keyboard up the toolbar would sit on top of it and cover what is being typed.
            if (dock != null) {
                dock.setVisibility(insets.isVisible(WindowInsets.Type.ime()) ? View.GONE : View.VISIBLE);
                if (blurStrip != null) {
                    blurStrip.setVisibility(dock.getVisibility());
                }
            }
            return WindowInsets.CONSUMED;
        });
        final View screen = root;
        // The content follows the keyboard while it slides in or out, instead of jumping at the end.
        root.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(
                WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP) {
            @Override
            public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> running) {
                applyInsets(screen, insets);
                return insets;
            }
        });
        setContentView(root);
        if (savedInstanceState != null) {
            if (docked) {
                showSection(savedInstanceState.getInt(KEY_SECTION), 0);
            } else if (savedInstanceState.getInt(KEY_SECTION) > 0) {
                int index = savedInstanceState.getInt(KEY_SECTION);
                openPanel(() -> sectionPanel(index));
            }
            if (panel instanceof SettingsPanel) {
                ((SettingsPanel) panel).openPage(savedInstanceState.getInt(KEY_SETTINGS_PAGE));
            }
        }

        // Ask on a fresh launch only, and only while the permission is missing (not again after every rotation).
        if (savedInstanceState == null
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
        if (savedInstanceState == null) {
            handleIntent(getIntent());
        }
        try {
            Shortcuts.publish(this);
        } catch (RuntimeException e) {
            // Launcher shortcuts are a convenience: never fail startup over them.
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Runs a quick-settings tile or launcher shortcut action, once. */
    private void handleIntent(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null || (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return; // nothing to do, or the app was reopened from Recents: do not repeat an old action
        }
        setIntent(new Intent()); // so a rotation does not repeat the action
        if (ACTION_CONNECT.equals(action)) {
            connect();
        } else if (ACTION_DISCONNECT.equals(action)) {
            disconnect();
        } else if (ACTION_OPEN_NODES.equals(action)) {
            openSection(1);
        } else if (ACTION_OPEN_LOG.equals(action)) {
            openSection(3);
        }
    }

    /** Opens a panel by section number in either toolbar style. */
    private void openSection(int index) {
        if (docked) {
            if (index != section) {
                showSection(index, Integer.signum(index - section));
            }
        } else {
            closePanel();
            openPanel(() -> sectionPanel(index));
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
        outState.putInt(KEY_SETTINGS_PAGE, panel instanceof SettingsPanel ? ((SettingsPanel) panel).page() : 0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        TunnelState.register(this);
        onTunnelState(TunnelState.get());
        updateGlow();
        handler.post(statsTick);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(statsTick);
        glow.setRunning(false); // onStart starts it again if it should run
        if (toolbar != null) toolbar.pause();
        TunnelState.unregister(this);
        super.onStop();
    }

    /** Pads the screen by the system bars and, while the keyboard is up, by the keyboard. */
    private void applyInsets(View v, WindowInsets insets) {
        Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Insets ime = insets.getInsets(WindowInsets.Type.ime());
        // On wide screens (tablet, landscape fold) keep the content in a centred column.
        float density = getResources().getDisplayMetrics().density;
        int extra = (int) (Math.max(0, getResources().getConfiguration().screenWidthDp - MAX_CONTENT_DP)
                / 2f * density);
        v.setPadding(bars.left + extra, bars.top, bars.right + extra, Math.max(bars.bottom, ime.bottom));
    }

    /**
     * Lets the splash icon travel to the Status shield while the splash background fades away.
     * Returns false (and does nothing) when the Status screen is not what is showing.
     */
    private boolean handOverSplash(android.window.SplashScreenView splash, long wait) {
        View icon = splash.getIconView();
        android.graphics.drawable.Drawable background = splash.getBackground();
        int[] shieldAt = new int[2];
        if (icon == null || background == null || icon.getWidth() == 0 || panel != null || section != 0
                || !status.heroShieldCenter(shieldAt) || status.heroShieldSize() == 0) {
            return false;
        }
        int[] iconAt = new int[2];
        icon.getLocationOnScreen(iconAt);
        float dx = shieldAt[0] - (iconAt[0] + icon.getWidth() / 2f);
        float dy = shieldAt[1] - (iconAt[1] + icon.getHeight() / 2f);
        // The splash icon fills about two thirds of its view; the Status shield is a picture of heroShieldSize.
        float scale = status.heroShieldSize() / (icon.getWidth() * 2f / 3f);
        background.mutate();
        android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofInt(255, 0);
        fade.setStartDelay(wait);
        fade.setDuration(HANDOVER_MS);
        fade.addUpdateListener(a -> background.setAlpha((int) a.getAnimatedValue()));
        fade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                splash.remove();
            }
        });
        icon.animate().setStartDelay(wait).setDuration(HANDOVER_MS)
                .translationX(dx).translationY(dy).scaleX(scale).scaleY(scale).alpha(0f)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        fade.start();
        return true;
    }

    @Override
    public void onTunnelState(TunnelState.State state) {
        status.setState(StatusView.State.valueOf(state.name()));
        if (state != TunnelState.State.RUNNING && state != TunnelState.State.STARTING) {
            setRestartPending(false); // a fresh start applies every change
        }
    }

    private void refresh() {
        String notice = Filt3rVpnService.takeNotice();
        if (notice != null) {
            Messages.show(frame, notice);
        }
        FilterStatus.Snapshot filter = FilterStatus.get();
        if (lastFilterState == FilterStatus.State.DOWNLOADING && filter.state != lastFilterState) {
            if (filter.state == FilterStatus.State.OK) {
                long names = filter.unique >= 0 ? filter.unique : filter.entries;
                String updated = getString(R.string.main_filter_updated, names);
                if (filter.failedLists > 0) {
                    updated += getResources().getQuantityString(R.plurals.main_filter_updated_lists_failed,
                            filter.failedLists, filter.failedLists, filter.error);
                }
                Messages.show(frame, updated);
            } else if (filter.state == FilterStatus.State.FAILED && !filter.error.equals(lastFilterError)) {
                lastFilterError = filter.error;
                Messages.show(frame, getString(R.string.main_filter_update_failed, filter.error));
            }
        }
        lastFilterState = filter.state;
        if (dock != null) {
            int working = 0;
            if (TunnelState.get() == TunnelState.State.RUNNING) {
                for (NodeInfo n : Filt3rVpnService.nodeStatus()) {
                    if (n.state == NodeInfo.State.WORKING) {
                        working++;
                    }
                }
            }
            dock.setBadge(1, working, getString(R.string.main_badge_working));
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
        s.filterText = FilterStatusText.describe(this, filter, true, System.currentTimeMillis());
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
    public boolean restartPending() {
        return restartPending;
    }

    @Override
    public void setRestartPending(boolean pending) {
        restartPending = pending;
        if (dock != null) {
            dock.setDot(4, pending, getString(R.string.main_dot_restart_waiting));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            startVpn();
        } else if (requestCode == REQUEST_EXPORT_LOG && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            writeQueryLog(data.getData());
        }
    }

    /** The kept query lines of the kinds the user chose to save, oldest first. */
    private List<String> exportLines(boolean blocked, boolean allowed) {
        List<String> out = new ArrayList<>();
        for (String line : queryLines()) {
            // "MM-dd HH:mm:ss ALLOWED|BLOCKED name": the verdict starts after the 14-character time and a space
            boolean isBlocked = line.startsWith("BLOCKED", 15);
            if (isBlocked ? blocked : allowed) {
                out.add(line);
            }
        }
        return out;
    }

    /** Saves the chosen query lines to the file the user picked. Runs off the main thread: the target may be slow. */
    private void writeQueryLog(Uri uri) {
        List<String> lines = exportLines(exportBlocked, exportAllowed);
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        int count = lines.size();
        Thread t = new Thread(() -> {
            String result;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                out.write(bytes);
                result = getResources().getQuantityString(R.plurals.main_export_saved, count, count);
            } catch (IOException | RuntimeException e) {
                result = getString(R.string.main_export_save_failed);
            }
            String shown = result;
            runOnUiThread(() -> Messages.show(frame, shown));
        }, "export-log");
        t.setDaemon(true);
        t.start();
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
        enterPanel(panel, 0);
        updateGlow();
    }

    private void closePanel() {
        if (panel == null) {
            return;
        }
        frame.removeView(panel);
        panel = null;
        updateGlow();
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closePanelOnBack);
        if (docked) {
            section = 0;
            dock.select(0);
        }
    }

    private GlowBackground glow;

    /** The animated background runs only on the Status screen, and only with the Animated background effect. */
    private void updateGlow() {
        glow.setRunning(panel == null && Effects.on(this, Effects.Effect.SHADER));
    }

    private void selectSection(int index) {
        if (index != section) {
            Effects.haptic(dock, HapticFeedbackConstants.SEGMENT_TICK);
            showSection(index, Integer.signum(index - section));
        }
    }

    /**
     * Shows section {@code index} (0 = status, no panel). {@code dir} is +1, -1 or 0 for no slide.
     * The panel being left stays under the new one until the transition ends, so the Status screen
     * never shows through between two panels.
     */
    private void showSection(int index, int dir) {
        View old = panel;
        panel = null;
        if (old != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closePanelOnBack);
        }
        section = index;
        dock.select(index);
        if (index == 0) {
            retirePanel(old, true);
            updateGlow();
            return;
        }
        panel = sectionPanel(index);
        if (blurStrip != null) {
            blurStrip.pulse();
        }
        frame.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, closePanelOnBack);
        enterPanel(panel, dir);
        retirePanel(old, false);
        updateGlow();
    }

    /** Removes a panel that is no longer current: at once, or after the new panel has come in (or a fade-out). */
    private void retirePanel(View old, boolean fadeOut) {
        if (old == null) {
            return;
        }
        int ms = Effects.ms(this, Effects.Effect.TRANSITIONS, SLIDE_MS);
        if (ms == 0) {
            frame.removeView(old);
        } else if (fadeOut) {
            leavePanel(old);
        } else {
            old.postDelayed(() -> frame.removeView(old), ms + 50);
        }
    }

    /** Brings a panel in with the chosen transition style (slide, fade or pop), if effects allow. */
    private void enterPanel(View p, int dir) {
        int ms = Effects.ms(this, Effects.Effect.TRANSITIONS, SLIDE_MS);
        if (ms == 0) {
            return;
        }
        int style = Effects.transitionStyle(this);
        if (style == Effects.STYLE_SLIDE && dir != 0) {
            p.setTranslationX(dir * frame.getWidth());
            if (Motion.spring(p, DynamicAnimation.TRANSLATION_X, dir * frame.getWidth(), 0f, false, null) == null) {
                p.animate().translationX(0).setDuration(ms).start();
            }
        } else if (style == Effects.STYLE_SLIDE) { // no direction (floating toolbar): rise from the bottom
            p.setTranslationY(frame.getHeight() * 0.25f);
            p.setAlpha(0f);
            p.animate().alpha(1f).setDuration(ms).start();
            if (Motion.spring(p, DynamicAnimation.TRANSLATION_Y, frame.getHeight() * 0.25f, 0f, false, null) == null) {
                p.animate().translationY(0).setDuration(ms).start();
            }
        } else if (style == Effects.STYLE_POP) {
            p.setAlpha(0f);
            p.setScaleX(0.8f);
            p.setScaleY(0.8f);
            p.animate().alpha(1f).setDuration(ms).start();
            if (Motion.spring(p, DynamicAnimation.SCALE_X, 0.8f, 1f, true, null) != null) {
                Motion.spring(p, DynamicAnimation.SCALE_Y, 0.8f, 1f, true, null);
            } else {
                p.animate().scaleX(1f).scaleY(1f).setInterpolator(new android.view.animation.OvershootInterpolator())
                        .setDuration(ms).start();
            }
        } else {
            p.setAlpha(0f);
            p.animate().alpha(1f).setDuration(ms).start();
        }
    }

    /** Closes the open panel with the chosen style (the back button, the back gesture, or a panel's own back). */
    private void closePanelAnimated() {
        View old = panel;
        if (old == null) {
            return;
        }
        panel = null;
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closePanelOnBack);
        if (docked) {
            section = 0;
            dock.select(0);
        }
        leavePanel(old);
        updateGlow();
    }

    /** Takes a panel out of the window with the chosen style, then removes it. */
    private void leavePanel(View old) {
        int ms = Effects.ms(this, Effects.Effect.TRANSITIONS, SLIDE_MS);
        if (ms == 0) {
            frame.removeView(old);
            return;
        }
        int style = Effects.transitionStyle(this);
        android.view.ViewPropertyAnimator a = old.animate().setDuration(ms);
        if (style == Effects.STYLE_POP) {
            // shrinks away while fading
            a.alpha(0f).scaleX(0.7f).scaleY(0.7f)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator());
        } else if (style == Effects.STYLE_SLIDE) {
            // drops off the bottom of the screen without fading, the mirror of how it rose
            a.translationY(frame.getHeight())
                    .setInterpolator(new android.view.animation.AccelerateInterpolator());
        } else {
            a.alpha(0f);
        }
        a.withEndAction(() -> frame.removeView(old)).start();
    }

    private View sectionPanel(int index) {
        switch (index) {
            case 1:
                return new NodesPanel(this, this, this::closePanelAnimated);
            case 2:
                return new ExemptAppsPanel(this, this, this::closePanelAnimated);
            case 3:
                return new QueryLogPanel(this, this, this::closePanelAnimated);
            default:
                return new SettingsPanel(this, this, this::closePanelAnimated);
        }
    }

    private void changeSection(int target) {
        if (target < 0 || target >= SECTION_COUNT || target == section) {
            return;
        }
        Effects.haptic(frame, HapticFeedbackConstants.SEGMENT_TICK);
        showSection(target, Integer.signum(target - section));
    }

    /** Observes touches for horizontal swipes between sections. Never consumes the event. */
    private void trackSwipe(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                // Not on a Settings sub-page: there the gesture belongs to the page, and Back goes up.
                swipeAllowed = !(getCurrentFocus() instanceof EditText)
                        && !(panel instanceof SettingsPanel
                        && ((SettingsPanel) panel).page() != SettingsPanel.PAGE_HOME);
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
    public boolean retestNode(ShadowsocksNode node, Runnable done) {
        return Filt3rVpnService.retestNode(node, done);
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

    @Override
    public String lastTestText() {
        long t = Filt3rVpnService.lastNodeTestMs();
        return t <= 0 ? getString(R.string.main_nodes_not_tested)
                : getString(R.string.main_nodes_tested, FilterStatusText.ago(this, t, System.currentTimeMillis()));
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
    public boolean subscriptionEnabled(int slot) {
        for (Settings.Subscription s : settings.subscriptions()) {
            if (s.slot == slot) {
                return s.enabled;
            }
        }
        return true;
    }

    @Override
    public void setSubscriptionEnabled(int slot, boolean on) {
        settings.setSubscriptionEnabled(slot, on);
    }

    @Override
    public String subscriptionUpdated(int slot) {
        long t = NodeStore.updatedAt(getFilesDir(), slot);
        return t <= 0 ? getString(R.string.main_subscription_not_downloaded)
                : getString(R.string.main_subscription_updated, FilterStatusText.ago(this, t, System.currentTimeMillis()));
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
        return Filt3rVpnService.checkResolver(this, stamp);
    }

    @Override
    public String filterListUrl() {
        return settings.filterListUrl();
    }

    @Override
    public String setFilterListUrl(String text) {
        try {
            settings.setFilterListUrl(Settings.normalizeFilterUrls(this, text));
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @Override
    public List<FilterCatalog.Entry> filterCatalog() {
        if (filterCatalog == null) {
            try (InputStream in = getAssets().open("dnsfilter.conf")) {
                filterCatalog = FilterCatalog.fromConf(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e); // the file ships in the APK: this is a broken build
            }
        }
        return filterCatalog;
    }

    @Override
    public boolean filterListEnabled(String id) {
        return enabledFilterLists().contains(id);
    }

    @Override
    public void setFilterListEnabled(String id, boolean on) {
        Set<String> ids = enabledFilterLists();
        if (on) {
            ids.add(id);
        } else {
            ids.remove(id);
        }
        settings.setEnabledFilterLists(ids);
    }

    /** The user's choice, or the lists dnsfilter.conf switches on if none was made. */
    private Set<String> enabledFilterLists() {
        Set<String> chosen = settings.enabledFilterLists();
        if (chosen != null) {
            return chosen;
        }
        Set<String> defaults = new HashSet<>();
        for (FilterCatalog.Entry entry : filterCatalog()) {
            if (entry.onByDefault) {
                defaults.add(entry.id);
            }
        }
        return defaults;
    }

    @Override
    public boolean ipBlockEnabled() {
        return settings.ipBlockEnabled();
    }

    @Override
    public void setIpBlockEnabled(boolean on) {
        settings.setIpBlockEnabled(on);
    }

    @Override
    public int listRefreshDays() {
        return settings.listRefreshDays();
    }

    @Override
    public void setListRefreshDays(int days) {
        settings.setListRefreshDays(days);
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
    public void exportQueryLog(boolean blocked, boolean allowed) {
        String kind = blocked && allowed ? "" : blocked ? "blocked-" : "allowed-";
        if (exportLines(blocked, allowed).isEmpty()) {
            Messages.show(frame, getString(blocked && allowed ? R.string.main_export_nothing_to_save
                    : blocked ? R.string.main_export_no_blocked : R.string.main_export_no_allowed),
                    Snackbar.LENGTH_SHORT);
            return;
        }
        exportBlocked = blocked;
        exportAllowed = allowed;
        // The system picker lets the user choose the folder and name; no storage permission is needed.
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "filt3r-query-log-" + kind + stamp + ".txt");
        startActivityForResult(pick, REQUEST_EXPORT_LOG);
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
    public void recreateScreen() {
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
        return FilterStatusText.describe(this, FilterStatus.get(), running(), System.currentTimeMillis(), true);
    }

    @Override
    public String ipBlockStatus() {
        return Filt3rVpnService.ipBlockStatus();
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
