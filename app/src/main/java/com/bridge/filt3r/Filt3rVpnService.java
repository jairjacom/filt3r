package com.bridge.filt3r;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.bridge.filt3r.core.dns.DohBootstrapResolver;
import com.bridge.filt3r.core.dns.UpstreamNameResolver;
import com.bridge.filt3r.core.dnscrypt.DnsCryptStamp;
import com.bridge.filt3r.core.filter.DNSFilterManager;
import com.bridge.filt3r.core.filter.DNSResolver;
import com.bridge.filt3r.core.http.HttpsFetcher;
import com.bridge.filt3r.core.nodes.FailoverDialer;
import com.bridge.filt3r.core.nodes.FailoverUpstream;
import com.bridge.filt3r.core.nodes.NodeManager;
import com.bridge.filt3r.core.nodes.NodeTester;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.socks.DirectDialer;
import com.bridge.filt3r.core.socks.FailureMode;
import com.bridge.filt3r.core.socks.Socks5Bridge;
import com.bridge.filt3r.core.socks.SocketProtector;
import com.bridge.filt3r.core.util.ExecutionEnvironment;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import hev.htproxy.TProxyService;

public class Filt3rVpnService extends VpnService {

    private static final String TAG = "filt3r";
    private static final String ADDRESS_V4 = "10.0.2.15";
    private static final String ADDRESS_V6 = "fdc8:1095:91e1:aaaa:aaaa:aaaa:aaaa:aaa2";
    private static final byte[] DNS_V4_BYTES = {10, 10, 10, 10};
    private static final String DNS_V4 = "10.10.10.10";
    // The one built-in resolver (dnscry.pt, Gigahost Norway, no query logs, no filtering). No picker in v1.
    private static final String DNSCRYPT_STAMP =
            "sdns://AQcAAAAAAAAADjE5OC4xNDAuMTQxLjQ2IFqbafOxgXuKwOgYxQ6XUqHWkMUt_5LI2nDkdVFU5hm7GTIuZG5zY3J5cHQtY2VydC5kbnNjcnkucHQ";
    private static final int MTU = 1500;
    private static final long CONFIG_BACKSTOP_MS = 5000;
    static final String ACTION_STOP = "com.bridge.filt3r.STOP";
    static final String ACTION_RESTART = "com.bridge.filt3r.RESTART";
    static final String PREFS = "filt3r";
    static final String PREF_FAILURE_MODE = "failure_mode";
    // Off until a node is known to have IPv6 egress (node tests, Step 5).
    static final String PREF_ALLOW_IPV6 = "allow_ipv6";
    private static final String CHANNEL_ID = "tunnel";
    private static final int NOTIFICATION_ID = 1;
    private static final String BOOTSTRAP_DOH = "https://1.1.1.1/dns-query";
    private static final int START_ROUNDS = 2;          // test rounds before giving up on a start
    private static final int START_ROUND_NODES = 40;    // random nodes tested per start round
    private static final int TEST_THREADS = 16;
    private static final int ROUND_TOP = 5;             // hourly: best-ranked nodes re-tested
    private static final int ROUND_RANDOM = 5;          // hourly: random others tested
    private static final long MAINTENANCE_MIN = 60;
    private static final Random RANDOM = new Random();
    private static final long FILTER_WAIT_S = 30;

    private final SocketProtector protector = new SocketProtector() {
        @Override
        public boolean protect(Socket socket) {
            return Filt3rVpnService.this.protect(socket);
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ParcelFileDescriptor tun;
    private Socks5Bridge bridge;
    private File config;
    private FileObserver configObserver;
    private volatile CountDownLatch filterReady = new CountDownLatch(0);
    private volatile boolean filterOk;
    private static volatile Filt3rVpnService instance;

    private final AtomicInteger generation = new AtomicInteger(); // bumped by every stop
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean starting;
    private volatile ScheduledExecutorService maintenance;
    private volatile NodeManager nodes;
    private volatile NodeRuntime runtime;

    @Override
    public void onCreate() {
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Tunnel status", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown(TunnelState.State.STOPPED);
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            stopEverything();
        }
        // Touching the class loads libhev-socks5-tunnel.so; JNI_OnLoad then
        // registers the natives, so this call proves the library is usable.
        Log.i(TAG, "hev loaded, running=" + TProxyService.TProxyIsRunning());
        if (tun != null || starting) {
            return START_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        TunnelState.set(TunnelState.State.STARTING);
        starting = true;
        int gen = generation.get();
        worker.execute(() -> startTunnel(gen));
        return START_STICKY;
    }

    /**
     * Runs on the worker thread: finding a working node can take many seconds (a subscription
     * download and a test round), so it must not block the main thread.
     */
    private void startTunnel(int gen) {
        try {
            ExecutionEnvironment.setEnvironment(new FilterEnvironment(this));
            startFilter();
            DirectDialer direct = new DirectDialer(protector);
            DnsCryptStamp stamp = DnsCryptStamp.parse(DNSCRYPT_STAMP);
            NodeStore store = new NodeStore(getFilesDir());
            NodeManager manager = new NodeManager(node -> onCurrentNodeChanged(node));
            nodes = manager;

            List<ShadowsocksNode> list = store.load();
            if (list.isEmpty() && store.hasSubscriptions()) {
                // First run: no node is up, so this download goes out directly (the bootstrap cost).
                DohBootstrapResolver doh = new DohBootstrapResolver(BOOTSTRAP_DOH);
                store.update(new HttpsFetcher(direct, doh), doh);
                list = store.load();
            }
            if (list.isEmpty()) {
                throw new IOException("no nodes: add subscriptions.txt or node.txt");
            }
            manager.setNodes(list);
            NodeTester tester = new NodeTester(direct, stamp);
            for (int round = 0; round < START_ROUNDS && manager.current() == null; round++) {
                tester.testAndRecord(manager, manager.pickForTest(0, START_ROUND_NODES, RANDOM), TEST_THREADS);
                manager.selectBest();
                if (gen != generation.get()) {
                    return;
                }
            }
            if (manager.current() == null) {
                throw new IOException("no working node found");
            }
            Log.i(TAG, "start round done: " + manager.summary());

            FailoverDialer viaNode = new FailoverDialer(manager, direct);
            FailoverUpstream upstream = new FailoverUpstream(manager, stamp, viaNode);
            // DNS always goes through the node: it is never sent direct, whatever the failure mode.
            DNSResolver resolver = new DNSResolver(upstream);
            bridge = new Socks5Bridge(DNS_V4_BYTES, (client, query) -> {
                awaitFilter();
                return resolver.resolve(client, query);
            }, viaNode, direct);
            bridge.setFailureMode(readFailureMode());
            boolean allowIPv6 = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ALLOW_IPV6, false);
            bridge.setAllowIPv6(allowIPv6);
            resolver.setAllowIPv6(allowIPv6);
            bridge.start();

            tun = new Builder()
                    .setSession("filt3r")
                    .setMtu(MTU)
                    .setBlocking(true)
                    .addAddress(ADDRESS_V4, 24)
                    .addAddress(ADDRESS_V6, 64)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer(DNS_V4)
                    .addDisallowedApplication(getPackageName())
                    .establish();
            if (tun == null) {
                throw new IOException("establish() returned null: VPN consent revoked");
            }
            Log.i(TAG, "tunnel established, fd=" + tun.getFd());

            writeConfig();
            if (!TProxyService.TProxyStartService(config.getAbsolutePath(), tun.getFd())) {
                throw new IOException("TProxyStartService failed");
            }
            Log.i(TAG, "hev started, bridge on loopback");
            if (gen != generation.get()) {
                return; // stopped while starting: stopEverything has already cleaned up
            }
            startMaintenance(manager, tester, store, viaNode, upstream);
            starting = false;
            TunnelState.set(TunnelState.State.RUNNING);
        } catch (IOException | android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
            if (gen == generation.get()) {
                Log.e(TAG, "start failed: " + e.getMessage());
                handler.post(() -> shutdown(TunnelState.State.FAILED));
            }
        }
    }

    private void onCurrentNodeChanged(ShadowsocksNode node) {
        NodeManager m = nodes;
        Log.i(TAG, "current node changed: " + (node == null ? "none" : "ok") + (m == null ? "" : ", " + m.summary()));
        NodeRuntime r = runtime;
        ScheduledExecutorService maint = maintenance;
        if (node == null && r != null && maint != null) {
            maint.execute(() -> r.testRound(0, START_ROUND_NODES)); // nothing usable: look for a node now
        }
    }

    /** What the hourly maintenance needs; created once the tunnel is up. */
    private final class NodeRuntime {
        final NodeManager manager;
        final NodeTester tester;
        final NodeStore store;
        final FailoverDialer viaNode;
        final FailoverUpstream upstream;

        NodeRuntime(NodeManager manager, NodeTester tester, NodeStore store,
                    FailoverDialer viaNode, FailoverUpstream upstream) {
            this.manager = manager;
            this.tester = tester;
            this.store = store;
            this.viaNode = viaNode;
            this.upstream = upstream;
        }

        /** Re-reads the subscriptions through the node when the cache is a day old. */
        void updateSubscriptions() {
            if (!store.needsUpdate()) {
                return;
            }
            try {
                UpstreamNameResolver names = new UpstreamNameResolver(upstream);
                store.update(new HttpsFetcher(viaNode, names), names);
                manager.setNodes(store.load());
                Log.i(TAG, "subscriptions updated: " + manager.summary());
            } catch (IOException e) {
                Log.w(TAG, "subscription update failed: " + e.getMessage());
            }
        }

        /** Tests the best-ranked {@code top} nodes and {@code random} others. The current node stays unless it died. */
        void testRound(int top, int random) {
            tester.testAndRecord(manager, manager.pickForTest(top, random, RANDOM), TEST_THREADS);
            Log.i(TAG, "node round: " + manager.summary());
        }
    }

    private void startMaintenance(NodeManager manager, NodeTester tester, NodeStore store,
                                  FailoverDialer viaNode, FailoverUpstream upstream) {
        NodeRuntime r = new NodeRuntime(manager, tester, store, viaNode, upstream);
        runtime = r;
        ScheduledExecutorService maint = Executors.newSingleThreadScheduledExecutor();
        maintenance = maint;
        maint.scheduleWithFixedDelay(() -> {
            try {
                r.updateSubscriptions();
                r.testRound(ROUND_TOP, ROUND_RANDOM);
            } catch (RuntimeException e) {
                Log.w(TAG, "maintenance failed: " + e);
            }
        }, MAINTENANCE_MIN, MAINTENANCE_MIN, TimeUnit.MINUTES);
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        stopEverything();
        if (TunnelState.get() != TunnelState.State.FAILED) {
            TunnelState.set(TunnelState.State.STOPPED);
        }
        instance = null;
    }

    @Override
    public void onRevoke() {
        shutdown(TunnelState.State.STOPPED);
    }

    private void shutdown(TunnelState.State finalState) {
        stopEverything();
        TunnelState.set(finalState);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private FailureMode readFailureMode() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_FAILURE_MODE, FailureMode.FAIL_CLOSED.name());
        try {
            return FailureMode.valueOf(saved);
        } catch (IllegalArgumentException e) {
            return FailureMode.FAIL_CLOSED; // unknown value: take the safe default
        }
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, Filt3rVpnService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("filt3r is running")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Stop", stop).build())
                .build();
    }

    /** Counters for the status line: hev packets and bytes, then the bridge counters. */
    static String statsText() {
        Filt3rVpnService s = instance;
        Socks5Bridge b = s == null ? null : s.bridge;
        if (b == null) {
            return "";
        }
        long[] hev = TProxyService.TProxyGetStats();
        String traffic = hev != null && hev.length >= 4
                ? "up " + hev[0] + " pkts / " + hev[1] + " B, down " + hev[2] + " pkts / " + hev[3] + " B\n"
                : "";
        return traffic + b.stats() + "\n" + (s.nodes == null ? "" : s.nodes.summary());
    }

    /**
     * Writes hev's config (it holds the bridge credentials) to an owner-only file and deletes
     * it as soon as hev has read it. hev loads it on its own thread, so a FileObserver watches
     * for the close after reading; a timer is the backstop.
     */
    private void writeConfig() throws IOException {
        config = new File(getFilesDir(), "hev.yml");
        Files.deleteIfExists(config.toPath());
        Files.createFile(config.toPath(),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        String yaml = "tunnel:\n"
                + "  mtu: " + MTU + "\n"
                + "  ipv4: 198.18.0.1\n"
                + "  ipv6: 'fc00::1'\n"
                + "socks5:\n"
                + "  port: " + bridge.getPort() + "\n"
                + "  address: 127.0.0.1\n"
                + "  udp: 'udp'\n"
                + "  username: '" + bridge.getUsername() + "'\n"
                + "  password: '" + bridge.getPassword() + "'\n"
                + "misc:\n"
                + "  log-level: warn\n";
        Files.write(config.toPath(), yaml.getBytes(StandardCharsets.UTF_8));

        final File file = config;
        final AtomicInteger opens = new AtomicInteger();
        configObserver = new FileObserver(file, FileObserver.OPEN | FileObserver.CLOSE_NOWRITE) {
            @Override
            public void onEvent(int event, String path) {
                if ((event & FileObserver.OPEN) != 0) {
                    opens.incrementAndGet();
                } else if ((event & FileObserver.CLOSE_NOWRITE) != 0) {
                    boolean deleted = file.delete();
                    Log.i(TAG, "config read by hev (opens=" + opens.get() + "), deleted=" + deleted);
                    stopWatching();
                }
            }
        };
        configObserver.startWatching();
        handler.postDelayed(this::deleteConfig, CONFIG_BACKSTOP_MS);
    }

    private void deleteConfig() {
        if (configObserver != null) {
            configObserver.stopWatching();
            configObserver = null;
        }
        if (config != null && config.exists()) {
            Log.w(TAG, "config still present, deleting (backstop), deleted=" + config.delete());
        }
    }

    private void startFilter() {
        CountDownLatch ready = new CountDownLatch(1);
        filterReady = ready;
        filterOk = false;
        Thread t = new Thread(() -> {
            try {
                DNSFilterManager.getInstance().init();
                filterOk = true;
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "filter init failed: " + e);
            } finally {
                ready.countDown();
            }
        }, "filter-init");
        t.setDaemon(true);
        t.start();
    }

    /** DNS waits here until the filter lists are loaded. A failed filter blocks DNS (fail closed). */
    private void awaitFilter() throws IOException {
        try {
            if (!filterReady.await(FILTER_WAIT_S, TimeUnit.SECONDS) || !filterOk) {
                throw new IOException("filter not ready");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private void stopEverything() {
        generation.incrementAndGet(); // a start still running in the worker will notice and stop
        starting = false;
        ScheduledExecutorService m = maintenance;
        maintenance = null;
        if (m != null) {
            m.shutdownNow();
        }
        nodes = null;
        runtime = null;
        handler.removeCallbacksAndMessages(null);
        TProxyService.TProxyStopService();
        deleteConfig();
        if (bridge != null) {
            Log.i(TAG, "bridge stats: " + bridge.stats());
            bridge.stop();
            bridge = null;
        }
        try {
            DNSFilterManager.getInstance().stop();
        } catch (IOException e) {
            Log.w(TAG, "filter stop failed: " + e.getMessage());
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException e) {
                Log.w(TAG, "close failed", e);
            }
            tun = null;
        }
    }
}
