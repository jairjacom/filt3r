package com.bridge.filt3r;

import android.content.Context;
import android.net.ConnectivityManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import com.bridge.filt3r.core.util.ExecutionEnvironment;

/** Gives the pure-Java filter its work directory, assets and network state. */
final class FilterEnvironment extends ExecutionEnvironment {

    private final Context context;
    private final File workDir;

    FilterEnvironment(Context context) throws IOException {
        this.context = context.getApplicationContext();
        // app-private storage; the filter lists and the query log live here
        this.workDir = new File(context.getFilesDir(), "filter");
        if (!workDir.isDirectory() && !workDir.mkdirs()) {
            throw new IOException("cannot create " + workDir);
        }
    }

    @Override
    public int getEnvironmentID() {
        return 1; // Android
    }

    @Override
    public String getEnvironmentVersion() {
        return String.valueOf(android.os.Build.VERSION.SDK_INT);
    }

    @Override
    public String getWorkDir() {
        return workDir.getAbsolutePath();
    }

    @Override
    public InputStream getAsset(String path) throws IOException {
        return context.getAssets().open(path);
    }

    @Override
    public boolean hasNetwork() {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        return cm != null && cm.getActiveNetwork() != null;
    }
}
