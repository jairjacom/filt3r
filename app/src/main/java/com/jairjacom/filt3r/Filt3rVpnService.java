package com.jairjacom.filt3r;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;

public class Filt3rVpnService extends VpnService {

    private static final String TAG = "filt3r";
    private static final String ADDRESS_V4 = "10.0.2.15";
    private static final String ADDRESS_V6 = "fdc8:1095:91e1:aaaa:aaaa:aaaa:aaaa:aaa2";
    private static final String DNS_V4 = "10.10.10.10";
    private static final int MTU = 1500;

    private ParcelFileDescriptor tun;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (tun == null) {
            tun = new Builder()
                    .setSession("filt3r")
                    .setMtu(MTU)
                    .setBlocking(true)
                    .addAddress(ADDRESS_V4, 24)
                    .addAddress(ADDRESS_V6, 64)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer(DNS_V4)
                    .establish();
            if (tun == null) {
                Log.e(TAG, "establish() returned null: VPN consent revoked");
                stopSelf();
            } else {
                Log.i(TAG, "tunnel established, fd=" + tun.getFd());
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        close();
    }

    @Override
    public void onRevoke() {
        close();
        stopSelf();
    }

    private void close() {
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
