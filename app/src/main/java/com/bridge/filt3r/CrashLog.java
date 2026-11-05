package com.bridge.filt3r;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the stack trace of the last crash in a private file, so it can be read in Settings > About
 * (the system log is not available to the user). The file never leaves the device.
 */
public final class CrashLog {

    private static final String FILE = "last_crash.txt";

    private CrashLog() {
    }

    /** Remembers the next uncaught exception, then lets the normal crash handling go on. Safe to call twice. */
    public static void install(Context context) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof Handler) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(new Handler(context.getApplicationContext(), previous));
    }

    /** The saved crash report, or null when there is none. */
    public static String read(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (!file.exists()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public static void clear(Context context) {
        new File(context.getFilesDir(), FILE).delete();
    }

    private static final class Handler implements Thread.UncaughtExceptionHandler {
        private final Context context;
        private final Thread.UncaughtExceptionHandler previous;

        Handler(Context context, Thread.UncaughtExceptionHandler previous) {
            this.context = context;
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable error) {
            try {
                StringWriter text = new StringWriter();
                PrintWriter out = new PrintWriter(text);
                out.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                        + " thread " + thread.getName());
                error.printStackTrace(out);
                out.flush();
                Files.write(new File(context.getFilesDir(), FILE).toPath(),
                        text.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
                // nothing more can be done while crashing
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        }
    }
}
