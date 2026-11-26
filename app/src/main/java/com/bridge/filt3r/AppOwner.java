package com.bridge.filt3r;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Process;
import android.system.OsConstants;

import com.bridge.filt3r.core.socks.AppSocket;
import com.bridge.filt3r.core.util.AppId;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Names the app that owns a tunnel socket, from Android's connection table. Identities are cached per run.
 * System services are read on use: the owner is built as a field of the service, before it is attached to a context.
 */
final class AppOwner {

    private final Context context;
    private final ConcurrentHashMap<Integer, AppId> names = new ConcurrentHashMap<>();

    AppOwner(Context context) {
        this.context = context;
    }

    /**
     * The app for the socket, or null when hev named no app, or the socket is already closed
     * (browsers cancel lookups, so some DNS queries have no owner).
     *
     * @param remote the far end of the flow: the virtual DNS address for a DNS query
     */
    AppId owner(AppSocket client, InetSocketAddress remote) {
        if (client == null) {
            return null;
        }
        try {
            InetSocketAddress local = new InetSocketAddress(client.ip(), client.port());
            int protocol = client.proto() == AppSocket.Proto.TCP ? OsConstants.IPPROTO_TCP : OsConstants.IPPROTO_UDP;
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            int uid = cm.getConnectionOwnerUid(protocol, local, remote);
            return uid < 0 ? null : appId(uid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The app for a uid. The label is the app's name, "System (name)" below the app range, else "App <uid>". */
    private AppId appId(int uid) {
        AppId cached = names.get(uid);
        if (cached != null) {
            return cached;
        }
        PackageManager pm = context.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        String systemName = systemUidName(pm, uid);
        String label;
        if (pkgs != null && pkgs.length > 0) {
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkgs[0], 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                label = pkgs[0];
            }
        } else if (uid < Process.FIRST_APPLICATION_UID) {
            label = context.getString(R.string.qlog_app_system, systemName);
        } else {
            // The owner is known but Android hides the package from us.
            label = context.getString(R.string.qlog_app_uid, uid);
        }
        AppId id = AppId.of(pkgs, uid, label, systemName);
        names.put(uid, id);
        return id;
    }

    private static String systemUidName(PackageManager pm, int uid) {
        if (uid == 0) {
            return "root";
        }
        String raw = pm.getNameForUid(uid);
        return raw != null ? raw : String.valueOf(uid);
    }
}
