package com.bridge.filt3r;

import android.content.Context;

import com.bridge.filt3r.core.util.ConnectionLog;

import java.io.File;
import java.util.TimeZone;

/** The one connection log of the process, shared by the service (writes) and the log screen (reads, clears). Same switch as the DNS log. */
final class ConnectionLogs {

    private static ConnectionLog instance;

    private ConnectionLogs() {
    }

    static synchronized ConnectionLog get(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            Settings settings = new Settings(app);
            instance = new ConnectionLog(new File(app.getFilesDir(), "connectionlog.txt"),
                    settings::queryLogEnabled, System::currentTimeMillis, TimeZone.getDefault());
        }
        return instance;
    }
}
