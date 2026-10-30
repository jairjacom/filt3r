package com.bridge.filt3r;

import android.content.Context;

import com.bridge.filt3r.core.util.QueryLog;

import java.io.File;

/** The one query log of the process, shared by the service (writes) and the Status screen (reads, clears). */
final class QueryLogs {

    private static QueryLog instance;

    private QueryLogs() {
    }

    static synchronized QueryLog get(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            Settings settings = new Settings(app);
            instance = new QueryLog(new File(app.getFilesDir(), "querylog.txt"), settings::queryLogEnabled);
        }
        return instance;
    }
}
