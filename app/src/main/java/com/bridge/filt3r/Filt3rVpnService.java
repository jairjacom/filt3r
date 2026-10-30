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

import com.bridge.filt3r.core.socks.SocketProtector;
import com.bridge.filt3r.core.filter.DNSFilterManager;
import com.bridge.filt3r.core.filter.DNSResolver;
import com.bridge.filt3r.core.socks.DirectDialer;
import com.bridge.filt3r.core.socks.FailureMode;
import com.bridge.filt3r.core.socks.ProtectedUdpUpstream;
import com.bridge.filt3r.core.socks.Socks5Bridge;
import com.bridge.filt3r.core.util.ExecutionEnvironment;

import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import hev.htproxy.TProxyService;

public class Filt3rVpnService extends VpnService {

    private static final String TAG = "filt3r";
    private static final String ADDRESS_V4 = "10.0.2.15";
    private static final String ADDRESS_V6 = "fdc8:1095:91e1:aaaa:aaaa:aaaa:aaaa:aaa2";
    private static final byte[] DNS_V4_BYTES = {10, 10, 10, 10};
    private static final String DNS_V4 = "10.10.10.10";
    // Interim: where the filter sends allowed queries (plain UDP). Replaced by DNSCrypt at Step 4.
    private static final String UPSTREAM_DNS = "1.1.1.1";
    private static final int MTU = 1500;
    private static final long CONFIG_BACKSTOP_MS = 5000;
    static final String ACTION_STOP = "com.bridge.filt3r.STOP";
    static final String ACTION_RESTART = "com.bridge.filt3r.RESTART";
    static final String PREFS = "filt3r";
    static final String PREF_FAILURE_MODE = "failure_mode";
    private static final String CHANNEL_ID = "tunnel";
    private static final int NOTIFICATION_ID = 1;
    private static final long FILTER_WAIT_S = 30;

    private final SocketProtector protector = new SocketProtector() {
        @Override
        public boolean protect(Socket socket) {
            return Filt3rVpnService.this.protect(socket);
        }

        @Override
        public boolean protect(DatagramSocket socket) {
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
        if (tun != null) {
            return START_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        TunnelState.set(TunnelState.State.STARTING);
        try {
            ExecutionEnvironment.setEnvironment(new FilterEnvironment(this));
            startFilter();
            DNSResolver resolver = new DNSResolver(
                    new ProtectedUdpUpstream(protector, new InetSocketAddress(UPSTREAM_DNS, 53)));
            DirectDialer direct = new DirectDialer(protector);
            bridge = new Socks5Bridge(DNS_V4_BYTES, (client, query) -> {
                awaitFilter();
                return resolver.resolve(client, query);
            }, direct, direct);
            bridge.setFailureMode(readFailureMode());
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
            TunnelState.set(TunnelState.State.RUNNING);
        } catch (IOException | android.content.pm.PackageManager.NameNotFoundException e) {
            Log.e(TAG, "start failed: " + e.getMessage());
            shutdown(TunnelState.State.FAILED);
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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
        return traffic + b.stats();
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
