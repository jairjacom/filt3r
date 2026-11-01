package com.bridge.filt3r;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.VpnService;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.bridge.filt3r.core.dns.DohBootstrapResolver;
import com.bridge.filt3r.core.dns.UpstreamNameResolver;
import com.bridge.filt3r.core.dnscrypt.DnsCryptStamp;
import com.bridge.filt3r.core.dnscrypt.DnsCryptUpstream;
import com.bridge.filt3r.core.dnscrypt.MinisignVerifier;
import com.bridge.filt3r.core.dnscrypt.ResolverList;
import com.bridge.filt3r.core.dnscrypt.ResolverTester;
import com.bridge.filt3r.core.filter.DNSFilterManager;
import com.bridge.filt3r.core.filter.DNSResolver;
import com.bridge.filt3r.core.filter.DNSResponsePatcher;
import com.bridge.filt3r.core.http.HttpsFetcher;
import com.bridge.filt3r.core.nodes.FailoverDialer;
import com.bridge.filt3r.core.nodes.FailoverUpstream;
import com.bridge.filt3r.core.nodes.NodeInfo;
import com.bridge.filt3r.core.nodes.NodeManager;
import com.bridge.filt3r.core.nodes.NodeTester;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.socks.DirectDialer;
import com.bridge.filt3r.core.socks.FailureMode;
import com.bridge.filt3r.core.socks.Socks5Bridge;
import com.bridge.filt3r.core.socks.SocketProtector;
import com.bridge.filt3r.core.util.ExecutionEnvironment;
import com.bridge.filt3r.core.util.QueryLog;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import hev.htproxy.TProxyService;

public class Filt3rVpnService extends VpnService {

    private static final String TAG = "filt3r";
    private static final String ADDRESS_V4 = "10.0.2.15";
    private static final String ADDRESS_V6 = "fdc8:1095:91e1:aaaa:aaaa:aaaa:aaaa:aaa2";
    private static final byte[] DNS_V4_BYTES = {10, 10, 10, 10};
    private static final String DNS_V4 = "10.10.10.10";
    static final String ACTION_REFRESH_RESOLVERS = "com.bridge.filt3r.REFRESH_RESOLVERS";
    // The official DNSCrypt list (what dnscrypt-proxy reads) and its minisign public key.
    private static final String RESOLVER_LIST_URL =
            "https://raw.githubusercontent.com/DNSCrypt/dnscrypt-resolvers/master/v3/public-resolvers.md";
    private static final String RESOLVER_LIST_KEY = "RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3";
    private static final long RESOLVER_LIST_MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final long RESOLVER_TEST_TIMEOUT_S = 90;
    private static final long FIRST_MAINTENANCE_MIN = 2;
    private static final int MTU = 1500;
    private static final long CONFIG_BACKSTOP_MS = 5000;
    static final String ACTION_STOP = "com.bridge.filt3r.STOP";
    static final String ACTION_RESTART = "com.bridge.filt3r.RESTART";
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
    private static final long RECOVERY_FIRST_S = 30;   // no node: wait before the second search
    private static final long RECOVERY_MAX_S = 600;

    private final SocketProtector protector = new SocketProtector() {
        @Override
        public boolean protect(Socket socket) {
            return Filt3rVpnService.this.protect(socket);
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile ParcelFileDescriptor tun;
    private volatile ParcelFileDescriptor heldTun; // the previous interface, kept (with nobody reading it, so traffic is dropped) until a restart has a new one
    private volatile Socks5Bridge bridge;
    private File config;
    private FileObserver configObserver;
    /** One filter start: DNS waits on {@code ready}, and {@code ok} is set only by that start's own init. */
    private static final class FilterGate {
        final CountDownLatch ready = new CountDownLatch(1);
        volatile boolean ok;
    }

    private volatile FilterGate filterGate = new FilterGate(); // never opened: DNS fails closed until a start opens one
    private static volatile Filt3rVpnService instance;
    private static final AtomicLong dnsBlocked = new AtomicLong();
    private static final AtomicLong dnsAllowed = new AtomicLong();
    private static final AtomicReference<String> notice = new AtomicReference<>();
    private static volatile String[] resolverInfo; // {name, ip} of the DNS resolver in use, null while down

    private final AtomicInteger generation = new AtomicInteger(); // bumped by every stop
    /** Guards the hand-over of every resource a start creates, against stopEverything. */
    private final Object lock = new Object();
    /** Filter init and stop, one at a time and in order, off the main thread; shared by every service instance. */
    private static final ExecutorService FILTER_LIFE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "filter-life");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile ScheduledExecutorService refresher; // subscription and resolver-list downloads
    private volatile HttpsFetcher bootstrapFetcher;      // first-run subscription download, direct
    private volatile HttpsFetcher filterFetcher;         // filter-list downloads through the node
    private volatile boolean starting;
    private volatile ScheduledExecutorService maintenance;
    private volatile NodeManager nodes;
    private volatile NodeTester nodeTester;
    private ConnectivityManager.NetworkCallback networkCallback; // guarded by this
    private volatile DNSResolver dnsResolver;
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
        if (intent == null && prepare(this) != null) {
            // Restarted by the system after the process died, but VPN consent has gone (another VPN
            // app took over): there is nothing to start, and trying would fail again and again.
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            shutdown(TunnelState.State.STOPPED);
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH_RESOLVERS.equals(action)) {
            NodeRuntime r = runtime;
            ScheduledExecutorService ref = refresher;
            if (r != null && ref != null) {
                try {
                    ref.execute(() -> r.refreshResolvers(true));
                } catch (RejectedExecutionException e) {
                    // stopped just now
                }
            }
            return tun != null ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            stopEverything(true); // keep the old interface so nothing leaks while the new one is prepared
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
            DirectDialer direct = new DirectDialer(protector);
            DnsCryptStamp testStamp = DnsCryptStamp.parse(Settings.DEFAULT_RESOLVER_STAMP);
            Settings settings = new Settings(this);
            QueryLog queryLog = QueryLogs.get(this);
            NodeStore store = new NodeStore(getFilesDir(), settings.subscriptions());
            NodeManager manager = new NodeManager(node -> onCurrentNodeChanged(node));
            manager.setPinListener((gone, dead) -> {
                settings.setPinnedNode(null);
                if (dead) {
                    notice.set("Pinned node " + gone.name + " stopped working; switched to the best node");
                }
            });
            manager.restorePin(settings.pinnedNode());
            // Everything a start creates is handed over under the lock, and only if no stop came
            // meanwhile; otherwise it would outlive the stop (see stopEverything).
            synchronized (lock) {
                if (gen != generation.get()) {
                    return;
                }
                dnsBlocked.set(0);
                dnsAllowed.set(0);
                DNSResponsePatcher.setObserver((host, blocked) -> {
                    (blocked ? dnsBlocked : dnsAllowed).incrementAndGet();
                    queryLog.add(host, blocked);
                });
                nodes = manager;
                watchNetwork(manager);
            }

            List<ShadowsocksNode> list = store.load();
            if (list.isEmpty() && store.hasSubscriptions()) {
                // First run: no node is up, so this download goes out directly (the bootstrap cost).
                DohBootstrapResolver doh = new DohBootstrapResolver(BOOTSTRAP_DOH);
                HttpsFetcher first = new HttpsFetcher(direct, doh);
                bootstrapFetcher = first; // stopEverything cancels it
                store.update(first, doh, false);
                list = store.load();
            }
            if (list.isEmpty()) {
                throw new IOException("no nodes: add a subscription URL in Settings");
            }
            manager.setNodes(list);
            settings.setPinnedNode(manager.pinnedKey()); // forget a pin whose node left the list
            NodeTester tester = new NodeTester(direct, testStamp, DnsCryptStamp.parse(Settings.DEFAULT_RESOLVER_STAMP_V6));
            synchronized (lock) {
                if (gen != generation.get()) {
                    return;
                }
                nodeTester = tester; // before the rounds: the first current node triggers an IPv6 probe
            }
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
            HttpsFetcher listFetcher = new HttpsFetcher(viaNode,
                    new UpstreamNameResolver(new DnsCryptUpstream(testStamp, viaNode)));
            DnsCryptStamp dnsStamp = parseStampOrDefault(settings.resolverStamp());
            FailoverUpstream upstream = new FailoverUpstream(manager, dnsStamp, viaNode, tester);
            // DNS always goes through the node: it is never sent direct, whatever the failure mode.
            DNSResolver resolver = new DNSResolver(upstream);
            Socks5Bridge b = new Socks5Bridge(DNS_V4_BYTES, (client, query) -> {
                awaitFilter();
                return resolver.resolve(client, query);
            }, viaNode, direct);
            b.setFailureMode(settings.failureMode());
            synchronized (lock) {
                if (gen != generation.get()) {
                    return;
                }
                // Filter lists are downloaded through the node, never directly. The filter starts only
                // now, because its first run downloads the lists and needs a working node.
                DNSFilterManager.setUrlOpener(listFetcher::stream);
                DNSFilterManager.setFilterListOverride(settings.filterListUrl());
                filterFetcher = listFetcher;
                startFilter();
                resolverInfo = new String[] {settings.resolverName(), dnsStamp.address.getHostAddress()};
                dnsResolver = resolver;
                bridge = b;
                applyIpv6();
                b.start();
            }

            Builder builder = new Builder()
                    .setSession("filt3r")
                    .setMtu(MTU)
                    .setBlocking(true)
                    .addAddress(ADDRESS_V4, 24)
                    .addAddress(ADDRESS_V6, 64)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer(DNS_V4)
                    .addDisallowedApplication(getPackageName());
            // Exempt apps skip the tunnel: they use the real IP and the real DNS.
            for (String pkg : settings.exemptApps()) {
                try {
                    builder.addDisallowedApplication(pkg);
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "exempt app is not installed, skipped");
                }
            }
            // On a restart the old interface is still open; establish() replaces it without a gap.
            ParcelFileDescriptor established = builder.establish();
            if (established == null) {
                throw new IOException("establish() returned null: VPN consent revoked");
            }
            synchronized (lock) {
                if (gen != generation.get()) {
                    closeQuietly(established);
                    return;
                }
                tun = established;
                closeQuietly(heldTun);
                heldTun = null;
                Log.i(TAG, "tunnel established, fd=" + established.getFd());

                writeConfig();
                if (!TProxyService.TProxyStartService(config.getAbsolutePath(), established.getFd())) {
                    throw new IOException("TProxyStartService failed");
                }
                Log.i(TAG, "hev started, bridge on loopback");
                startMaintenance(manager, tester, store, viaNode, testStamp);
                starting = false;
                TunnelState.set(TunnelState.State.RUNNING);
            }
        } catch (IOException | android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
            if (gen == generation.get()) {
                Log.e(TAG, "start failed: " + e.getMessage());
                handler.post(() -> {
                    if (gen != generation.get()) {
                        return; // a restart began meanwhile: this failure belongs to the start it replaced
                    }
                    if (heldTun != null && new Settings(this).failureMode() == FailureMode.FAIL_CLOSED) {
                        blockAfterFailedRestart();
                    } else {
                        shutdown(TunnelState.State.FAILED);
                    }
                });
            }
        }
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        if (fd != null) {
            try {
                fd.close();
            } catch (IOException e) {
                Log.w(TAG, "close failed", e);
            }
        }
    }

    /** The user's resolver, or the built-in one if the saved stamp cannot be read. */
    private static DnsCryptStamp parseStampOrDefault(String stamp) {
        try {
            return DnsCryptStamp.parse(stamp);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "saved resolver is not usable, using the default");
            return DnsCryptStamp.parse(Settings.DEFAULT_RESOLVER_STAMP);
        }
    }

    /**
     * Tells the manager when the device loses or regains its network, so an outage is not counted
     * as every node failing. Our own app is excluded from the VPN, so the default network seen
     * here is the real one.
     */
    private synchronized void watchNetwork(NodeManager manager) {
        unwatchNetwork();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return;
        }
        AtomicReference<Network> last = new AtomicReference<>();
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                last.set(n);
                manager.setOnline(true);
                NodeRuntime r = runtime;
                if (r != null && nodes == manager && manager.current() == null) {
                    r.recoverNodes();
                }
            }

            @Override
            public void onLost(Network n) {
                if (last.compareAndSet(n, null)) { // a replacement network has already reported itself
                    manager.setOnline(false);
                }
            }
        };
        cm.registerDefaultNetworkCallback(cb);
        networkCallback = cb;
    }

    private synchronized void unwatchNetwork() {
        if (networkCallback != null) {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                try {
                    cm.unregisterNetworkCallback(networkCallback);
                } catch (IllegalArgumentException e) {
                    // already unregistered
                }
            }
            networkCallback = null;
        }
    }

    private void onCurrentNodeChanged(ShadowsocksNode node) {
        NodeManager m = nodes;
        Log.i(TAG, "current node changed: " + (node == null ? "none" : "ok") + (m == null ? "" : ", " + m.summary()));
        applyIpv6();
        if (m != null && m.currentIpv6Unknown()) {
            probeIpv6();
        }
        NodeRuntime r = runtime;
        if (node == null && r != null) {
            r.recoverNodes(); // nothing usable: look for a node now, and keep looking
        }
    }

    /** IPv6 is allowed only while the current node is known to have IPv6 egress; otherwise apps get IPv4 only. */
    private void applyIpv6() {
        NodeManager m = nodes;
        boolean allow = m != null && m.currentHasIpv6();
        Socks5Bridge b = bridge;
        DNSResolver r = dnsResolver;
        if (b != null) {
            b.setAllowIPv6(allow);
        }
        if (r != null) {
            r.setAllowIPv6(allow);
        }
        Log.i(TAG, "ipv6 " + (allow ? "on" : "off"));
    }

    /**
     * Checks the current node for IPv6 egress on a thread of its own: a node without it makes the
     * probe wait for a connection to be closed or time out.
     */
    private void probeIpv6() {
        NodeManager m = nodes;
        NodeTester t = nodeTester;
        ShadowsocksNode node = m == null ? null : m.current();
        if (node == null || t == null) {
            return;
        }
        Thread th = new Thread(() -> {
            boolean has = t.hasIpv6(node);
            if (nodes == m) { // not stopped or restarted meanwhile
                m.setIpv6(node, has);
                applyIpv6();
            }
        }, "ipv6-probe");
        th.setDaemon(true);
        th.start();
    }

    /** Every node with its state, best first, for the Node list screen. Empty while the tunnel is down. */
    static List<NodeInfo> nodeStatus() {
        Filt3rVpnService s = instance;
        NodeManager m = s == null ? null : s.nodes;
        return m == null ? Collections.emptyList() : m.snapshot();
    }

    /**
     * Re-tests the best nodes and a random sample now, and re-checks the current node's IPv6. Runs
     * on the maintenance thread; {@code done} is called there when it ends (post to the main thread
     * before touching views).
     *
     * @return false if the tunnel is not running or a re-test is already under way
     */
    static boolean retestNodes(Runnable done) {
        Filt3rVpnService s = instance;
        NodeRuntime r = s == null ? null : s.runtime;
        ScheduledExecutorService maint = s == null ? null : s.maintenance;
        if (r == null || maint == null || !r.retesting.compareAndSet(false, true)) {
            return false;
        }
        try {
            maint.execute(() -> {
                try {
                    r.testRound(ROUND_TOP, START_ROUND_NODES);
                    r.speedRound(ROUND_TOP);
                    r.ipv6Round();
                    s.applyIpv6();
                    s.probeIpv6();
                } finally {
                    r.retesting.set(false);
                    done.run();
                }
            });
        } catch (RejectedExecutionException e) { // the tunnel was stopped just now
            r.retesting.set(false);
            return false;
        }
        return true;
    }

    /**
     * Pins a node chosen in the Node list; a node that was never tested is tested first. Runs on
     * the maintenance thread, and {@code done} is called there (post to the main thread before
     * touching views). If the node does not answer, nothing is pinned and {@link #takeNotice} says so.
     *
     * @return false if the tunnel is not running
     */
    static boolean pinNode(ShadowsocksNode node, Runnable done) {
        Filt3rVpnService s = instance;
        NodeRuntime r = s == null ? null : s.runtime;
        ScheduledExecutorService maint = s == null ? null : s.maintenance;
        if (r == null || maint == null) {
            return false;
        }
        try {
            maint.execute(() -> {
                try {
                    if (!r.manager.pin(node)) { // not tested yet
                        try {
                            r.manager.recordTest(node, r.tester.test(node));
                        } catch (IOException | RuntimeException e) {
                            r.manager.recordFailure(node);
                            notice.set(node.name + " did not answer");
                            return;
                        }
                        if (!r.manager.pin(node)) {
                            return;
                        }
                    }
                    new Settings(s).setPinnedNode(NodeManager.keyOf(node));
                } finally {
                    done.run();
                }
            });
        } catch (RejectedExecutionException e) { // the tunnel was stopped just now
            return false;
        }
        return true;
    }

    /** Removes the pin; the current node keeps carrying traffic until it dies. */
    static void unpinNode() {
        Filt3rVpnService s = instance;
        NodeManager m = s == null ? null : s.nodes;
        if (m != null) {
            m.unpin();
        }
        if (s != null) {
            new Settings(s).setPinnedNode(null);
        }
    }

    /** A one-time message for the user (a pinned node died, or a pin attempt failed), or null. */
    static String takeNotice() {
        return notice.getAndSet(null);
    }

    /**
     * Starts a filter-list download now (the filter does it on its own thread).
     *
     * @return false if the tunnel is not running, so there is no node to download through
     */
    static boolean updateFilters() {
        if (instance == null || TunnelState.get() != TunnelState.State.RUNNING) {
            return false;
        }
        DNSFilterManager.getInstance().triggerUpdateFilter();
        return true;
    }

    /** What the hourly maintenance needs; created once the tunnel is up. */
    private final class NodeRuntime {
        final NodeManager manager;
        final NodeTester tester;
        final NodeStore store;
        final FailoverDialer viaNode;
        final AtomicBoolean retesting = new AtomicBoolean();
        /** One fetcher for the subscription and resolver-list downloads, so stopEverything can cut them off. */
        final HttpsFetcher fetcher;
        final AtomicBoolean recovering = new AtomicBoolean();
        /** Looks names up through the node with the built-in resolver, so a bad user choice cannot block downloads. */
        final UpstreamNameResolver names;

        NodeRuntime(NodeManager manager, NodeTester tester, NodeStore store,
                    FailoverDialer viaNode, DnsCryptStamp testStamp) {
            this.manager = manager;
            this.tester = tester;
            this.store = store;
            this.viaNode = viaNode;
            this.names = new UpstreamNameResolver(new DnsCryptUpstream(testStamp, viaNode));
            this.fetcher = new HttpsFetcher(viaNode, names);
        }

        /** Re-reads the subscriptions through the node when a subscription is due. */
        void updateSubscriptions() {
            if (!store.anyDue()) {
                return;
            }
            try {
                store.update(fetcher, names, true);
                manager.setNodes(store.load());
                Log.i(TAG, "subscriptions updated: " + manager.summary());
            } catch (IOException e) {
                Log.w(TAG, "subscription update failed: " + e.getMessage());
            }
        }

        /**
         * Downloads the official resolver list, checks its signature, tests every DNSCrypt resolver in
         * it through the node and keeps the ones that answer.
         *
         * @param force false to skip when the stored list is younger than a week
         */
        void refreshResolvers(boolean force) {
            ResolverStore resolvers = new ResolverStore(getFilesDir());
            if (!force && !resolvers.isOlderThan(RESOLVER_LIST_MAX_AGE_MS)) {
                return;
            }
            try {
                String list = fetcher.get(RESOLVER_LIST_URL);
                String signature = fetcher.get(RESOLVER_LIST_URL + ".minisig");
                if (!MinisignVerifier.verify(list.getBytes(StandardCharsets.UTF_8), signature, RESOLVER_LIST_KEY)) {
                    throw new IOException("resolver list signature is not valid");
                }
                List<ResolverTester.Result> working = ResolverTester.testAll(
                        ResolverList.parse(list), viaNode, TEST_THREADS, RESOLVER_TEST_TIMEOUT_S);
                if (working.isEmpty()) {
                    throw new IOException("no resolver answered");
                }
                resolvers.save(working);
                Log.i(TAG, "resolver list updated: " + working.size() + " working");
            } catch (IOException e) {
                Log.w(TAG, "resolver list update failed: " + e.getMessage());
            }
        }

        /**
         * Looks for a working node until there is one: tests at once, then again after 30 s,
         * doubling up to 10 minutes. Nothing is tested while the device has no network (the
         * manager is told by the network callback). Does nothing if a search is already under way.
         */
        void recoverNodes() {
            ScheduledExecutorService maint = maintenance;
            if (maint == null || !recovering.compareAndSet(false, true)) {
                return;
            }
            try {
                maint.execute(() -> recoveryStep(maint, RECOVERY_FIRST_S));
            } catch (RejectedExecutionException e) { // the tunnel was stopped just now
                recovering.set(false);
            }
        }

        private void recoveryStep(ScheduledExecutorService maint, long nextDelayS) {
            boolean again = false;
            try {
                if (manager.current() == null) {
                    if (manager.isOnline()) {
                        testRound(0, START_ROUND_NODES);
                    }
                    again = manager.current() == null;
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "node search failed: " + e);
                again = manager.current() == null;
            } finally {
                if (again) {
                    try {
                        maint.schedule(() -> recoveryStep(maint, Math.min(nextDelayS * 2, RECOVERY_MAX_S)),
                                nextDelayS, TimeUnit.SECONDS);
                    } catch (RejectedExecutionException e) { // stopped
                        recovering.set(false);
                    }
                } else {
                    recovering.set(false);
                }
            }
        }

        /** Tests the best-ranked {@code top} nodes and {@code random} others. The current node stays unless it died. */
        void testRound(int top, int random) {
            tester.testAndRecord(manager, manager.pickForTest(top, random, RANDOM), TEST_THREADS);
            Log.i(TAG, "node round: " + manager.summary());
        }

        /** Probes every working node for IPv6 egress, so the Node list can show it on every row. */
        void ipv6Round() {
            List<ShadowsocksNode> working = new ArrayList<>();
            for (NodeInfo n : manager.snapshot()) {
                if (n.state == NodeInfo.State.WORKING) {
                    working.add(n.node);
                }
            }
            tester.ipv6AndRecord(manager, working, TEST_THREADS);
            Log.i(TAG, "ipv6 round: " + working.size() + " nodes");
        }

        /** Speed-tests the best-ranked working nodes, one at a time. */
        void speedRound(int top) {
            List<ShadowsocksNode> best = new ArrayList<>();
            for (NodeInfo n : manager.snapshot()) {
                if (n.state == NodeInfo.State.WORKING && best.size() < top) {
                    best.add(n.node);
                }
            }
            tester.speedAndRecord(manager, best);
            Log.i(TAG, "speed round: " + best.size() + " nodes");
        }
    }

    private void startMaintenance(NodeManager manager, NodeTester tester, NodeStore store,
                                  FailoverDialer viaNode, DnsCryptStamp testStamp) {
        NodeRuntime r = new NodeRuntime(manager, tester, store, viaNode, testStamp);
        runtime = r;
        ScheduledExecutorService maint = Executors.newSingleThreadScheduledExecutor();
        maintenance = maint;
        // Downloads run on their own thread: a slow server must not hold up node tests and failover.
        ScheduledExecutorService ref = Executors.newSingleThreadScheduledExecutor();
        refresher = ref;
        maint.scheduleWithFixedDelay(() -> {
            try {
                r.testRound(ROUND_TOP, ROUND_RANDOM);
                probeIpv6();
            } catch (RuntimeException | Error e) {
                Log.w(TAG, "maintenance failed: " + e);
            }
        }, FIRST_MAINTENANCE_MIN, MAINTENANCE_MIN, TimeUnit.MINUTES);
        ref.scheduleWithFixedDelay(() -> {
            try {
                r.updateSubscriptions();
                r.refreshResolvers(false);
            } catch (RuntimeException | Error e) {
                Log.w(TAG, "refresh failed: " + e);
            }
        }, FIRST_MAINTENANCE_MIN, MAINTENANCE_MIN, TimeUnit.MINUTES);
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

    /**
     * A restart failed while the old interface is still held and the user chose to block traffic
     * when no node works: keep the interface (nobody reads it, so traffic is dropped), show that in
     * the notification and the app, and leave the way out to Connect or Stop.
     */
    private void blockAfterFailedRestart() {
        stopEverything(true);
        TunnelState.set(TunnelState.State.FAILED);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                buildNotification("Restart failed: traffic is blocked"));
    }

    private Notification buildNotification() {
        return buildNotification("filt3r is running");
    }

    private Notification buildNotification(String title) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, Filt3rVpnService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(title)
                .setContentIntent(open)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Stop", stop)
                        .setAuthenticationRequired(true) // not usable from the lock screen
                        .build())
                .build();
    }

    /**
     * Tests a resolver stamp through the current node, for the Settings screen's custom-stamp field.
     * Blocks; call it off the main thread.
     *
     * @return a short result such as "works, 340 ms"
     * @throws IOException with a reason a user can read
     */
    static String checkResolver(String stampText) throws IOException {
        DnsCryptStamp stamp;
        try {
            stamp = DnsCryptStamp.parse(stampText);
        } catch (IllegalArgumentException e) {
            throw new IOException("not a usable DNSCrypt stamp: " + e.getMessage());
        }
        Filt3rVpnService s = instance;
        NodeRuntime r = s == null ? null : s.runtime;
        if (r == null) {
            throw new IOException("start the VPN first: resolvers are tested through the node");
        }
        try {
            return "works, " + ResolverTester.test(stamp, r.viaNode) + " ms";
        } catch (IOException e) {
            throw new IOException("resolver did not answer (it may use another cipher or no TCP): " + e.getMessage());
        }
    }

    /** Session traffic through the tunnel as {up, down} bytes, or null while the tunnel is down. */
    static long[] trafficBytes() {
        Filt3rVpnService s = instance;
        if (s == null || s.bridge == null) {
            return null;
        }
        long[] hev = TProxyService.TProxyGetStats();
        return hev != null && hev.length >= 4 ? new long[] {hev[1], hev[3]} : null;
    }

    /** The DNS resolver in use as {name, ip}, or null while the tunnel is down. */
    static String[] resolver() {
        return resolverInfo;
    }

    /** DNS decisions this session as {blocked, allowed}. */
    static long[] dnsCounts() {
        return new long[] {dnsBlocked.get(), dnsAllowed.get()};
    }

    /** The node carrying traffic now, or null while the tunnel is down or no node is chosen. */
    static NodeInfo currentNode() {
        for (NodeInfo n : nodeStatus()) {
            if (n.current) {
                return n;
            }
        }
        return null;
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
        FilterGate gate = new FilterGate();
        filterGate = gate;
        int gen = generation.get();
        FILTER_LIFE.execute(() -> {
            try {
                if (gen != generation.get()) {
                    return; // stopped before it began: nothing to start
                }
                DNSFilterManager.getInstance().init();
                gate.ok = true;
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "filter init failed: " + e);
            } finally {
                gate.ready.countDown();
            }
        });
    }

    /** DNS waits here until the filter lists are loaded. A failed filter blocks DNS (fail closed). */
    private void awaitFilter() throws IOException {
        FilterGate gate = filterGate;
        try {
            if (!gate.ready.await(FILTER_WAIT_S, TimeUnit.SECONDS) || !gate.ok) {
                throw new IOException("filter not ready");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private void stopEverything() {
        stopEverything(false);
    }

    /**
     * Stops the tunnel and everything it owns. Takes the lock so that a start running on the worker
     * either handed its resources over before this (and they are closed here) or sees the new
     * generation afterwards and discards them. The filter is stopped on its own thread, in order
     * with its init, because stopping can wait for a download.
     *
     * @param keepInterface true for a restart: the VPN interface stays open with nobody reading it
     *                      (traffic is dropped, not sent in the clear) until the new one replaces it
     */
    private void stopEverything(boolean keepInterface) {
        synchronized (lock) {
            generation.incrementAndGet(); // a start still running in the worker will notice and stop
            unwatchNetwork();
            starting = false;
            ScheduledExecutorService m = maintenance;
            maintenance = null;
            if (m != null) {
                m.shutdownNow();
            }
            ScheduledExecutorService ref = refresher;
            refresher = null;
            if (ref != null) {
                ref.shutdownNow();
            }
            NodeRuntime r = runtime;
            if (r != null) {
                r.fetcher.cancel();
            }
            HttpsFetcher first = bootstrapFetcher;
            bootstrapFetcher = null;
            if (first != null) {
                first.cancel();
            }
            HttpsFetcher lists = filterFetcher;
            filterFetcher = null;
            if (lists != null) {
                DNSFilterManager.requestAbort(); // first: the cut below must read as an abort, not as a failed list
                lists.cancel(); // a list download blocked on the network ends now, so the filter can stop
            }
            nodes = null;
            nodeTester = null;
            dnsResolver = null;
            runtime = null;
            DNSResponsePatcher.setObserver(null);
            resolverInfo = null;
            DNSFilterManager.setUrlOpener(null);
            handler.removeCallbacksAndMessages(null);
            TProxyService.TProxyStopService();
            deleteConfig();
            Socks5Bridge b = bridge;
            bridge = null;
            if (b != null) {
                Log.i(TAG, "bridge stats: " + b.stats());
                b.stop();
            }
            FILTER_LIFE.execute(() -> {
                try {
                    DNSFilterManager.getInstance().stop();
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "filter stop failed: " + e.getMessage());
                }
            });
            ParcelFileDescriptor old = tun;
            tun = null;
            if (keepInterface && old != null) {
                closeQuietly(heldTun);
                heldTun = old;
            } else if (!keepInterface) {
                closeQuietly(old);
                closeQuietly(heldTun);
                heldTun = null;
            }
        }
    }
}
