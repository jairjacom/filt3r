package com.bridge.filt3r;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Process;
import android.system.OsConstants;

import com.bridge.filt3r.core.socks.AppSocket;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Names the app that owns a tunnel socket, from Android's connection table. Names are cached per run.
 * System services are read on use: the owner is built as a field of the service, before it is attached to a context.
 */
final class AppOwner {

    private final Context context;
    private final ConcurrentHashMap<Integer, String> names = new ConcurrentHashMap<>();

    AppOwner(Context context) {
        this.context = context;
    }

    /**
     * App name for the socket, or null when hev named no app, or the socket is already closed
     * (browsers cancel lookups, so some DNS queries have no owner).
     *
     * @param remote the far end of the flow: the virtual DNS address for a DNS query
     */
    String owner(AppSocket client, InetSocketAddress remote) {
        if (client == null) {
            return null;
        }
        try {
            InetSocketAddress local = new InetSocketAddress(client.ip(), client.port());
            int protocol = client.proto() == AppSocket.Proto.TCP ? OsConstants.IPPROTO_TCP : OsConstants.IPPROTO_UDP;
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            int uid = cm.getConnectionOwnerUid(protocol, local, remote);
            return uid < 0 ? null : appName(uid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** App name for a uid: the app's label, "System (name)" below the app range, else "App <uid>". */
    private String appName(int uid) {
        String cached = names.get(uid);
        if (cached != null) {
            return cached;
        }
        PackageManager pm = context.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        String name;
        if (pkgs != null && pkgs.length > 0) {
            try {
                name = pm.getApplicationLabel(pm.getApplicationInfo(pkgs[0], 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                name = pkgs[0];
            }
        } else if (uid < Process.FIRST_APPLICATION_UID) {
            name = context.getString(R.string.qlog_app_system, systemUidName(pm, uid));
        } else {
            // The owner is known but Android hides the package from us.
            name = context.getString(R.string.qlog_app_uid, uid);
        }
        names.put(uid, name);
        return name;
    }

    private static String systemUidName(PackageManager pm, int uid) {
        if (uid == 0) {
            return "root";
        }
        String raw = pm.getNameForUid(uid);
        return raw != null ? raw : String.valueOf(uid);
    }
}
