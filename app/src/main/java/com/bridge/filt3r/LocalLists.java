package com.bridge.filt3r;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.bridge.filt3r.core.filter.IpBlockList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * List files the user picked with the system file picker. They are copied into app-private storage, so
 * filt3r needs no storage permission and the list keeps working if the original is moved. One folder per
 * kind of list: block lists of host names ("lists").
 */
final class LocalLists {

    static final String HOSTS = "lists";
    /** Lists of IP addresses and CIDR ranges for the IP blocklist. */
    static final String IPS = "iplists";

    /** Same cap as a downloaded list (core DNSFilterManager.MAX_LIST_BYTES). */
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    /** An IP list is read whole into memory, so IpBlockList.MAX_BYTES (8 MB) is the limit there. */
    private static final long MAX_IP_BYTES = 8L * 1024 * 1024;
    private static final int MAX_FILES = 8;

    /** Its message is written for the user and safe to show; any other exception is not. */
    static final class ImportException extends IOException {
        ImportException(String message) {
            super(message);
        }
    }

    private LocalLists() {}

    private static File dir(Context ctx, String kind) {
        return new File(ctx.getFilesDir(), kind);
    }

    /** File names, sorted. */
    static List<String> names(Context ctx, String kind) {
        String[] names = dir(ctx, kind).list((d, n) -> !n.endsWith(".tmp")); // a leftover from a crash
        if (names == null) {
            return new ArrayList<>();
        }
        Arrays.sort(names);
        return new ArrayList<>(Arrays.asList(names));
    }

    /** The typed filter-list URLs plus a file:// entry for each picked list, joined with "; ". */
    static String withHostLists(Context ctx, String typed) {
        StringBuilder out = new StringBuilder(typed);
        for (String name : names(ctx, HOSTS)) {
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append("file://").append(new File(dir(ctx, HOSTS), name).getAbsolutePath());
        }
        return out.toString();
    }

    /** Every picked IP list as one list, or null when there is none. A file that no longer parses is skipped. */
    static IpBlockList loadIpLists(Context ctx) {
        IpBlockList all = null;
        for (String name : names(ctx, IPS)) {
            try (InputStream in = Files.newInputStream(new File(dir(ctx, IPS), name).toPath())) {
                IpBlockList one = IpBlockList.parse(in);
                all = all == null ? one : all.plus(one);
            } catch (IOException | RuntimeException e) {
                // skipped: it was checked when it was added
            }
        }
        return all;
    }

    /**
     * Copies the picked file in and returns the name it was saved under.
     *
     * @throws IOException with a user-readable message when the file is unusable
     */
    static String importFile(Context ctx, String kind, Uri uri) throws IOException {
        File dir = dir(ctx, kind);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new ImportException(ctx.getString(R.string.local_list_save_failed));
        }
        if (names(ctx, kind).size() >= MAX_FILES) {
            throw new ImportException(ctx.getString(R.string.local_list_too_many, MAX_FILES));
        }
        File target = uniqueFile(dir, safeName(displayName(ctx, uri)));
        File tmp = new File(dir, target.getName() + ".tmp");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new ImportException(ctx.getString(R.string.local_list_read_failed));
            }
            try (OutputStream out = Files.newOutputStream(tmp.toPath())) {
                byte[] buf = new byte[8192];
                long total = 0;
                boolean text = false;
                int r;
                while ((r = in.read(buf)) != -1) {
                    total += r;
                    if (total > (IPS.equals(kind) ? MAX_IP_BYTES : MAX_BYTES)) {
                        throw new ImportException(ctx.getString(IPS.equals(kind)
                                ? R.string.local_ip_list_too_big : R.string.local_list_too_big));
                    }
                    for (int i = 0; i < r; i++) {
                        text |= buf[i] > ' ';
                        // The filter refuses these bytes and would fail the whole list every hour.
                        if (HOSTS.equals(kind) && buf[i] >= 0 && buf[i] < 32 && buf[i] != 9 && buf[i] != 10 && buf[i] != 13) {
                            throw new ImportException(ctx.getString(R.string.local_list_binary));
                        }
                    }
                    out.write(buf, 0, r);
                }
                if (!text) {
                    throw new ImportException(ctx.getString(R.string.local_list_empty));
                }
            }
            if (IPS.equals(kind)) {
                try (InputStream check = Files.newInputStream(tmp.toPath())) {
                    IpBlockList.parse(check); // refuses a file with no address in it
                } catch (IOException e) {
                    boolean noAddress = "not an IP list".equals(e.getMessage()) || "IP list is empty".equals(e.getMessage());
                    throw new ImportException(ctx.getString(noAddress
                            ? R.string.local_ip_list_bad : R.string.local_ip_list_too_big));
                }
            }
            Files.move(tmp.toPath(), target.toPath());
            return target.getName();
        } finally {
            tmp.delete();
        }
    }

    static boolean remove(Context ctx, String kind, String name) {
        // A name from our own list only; never a path.
        return name.indexOf('/') < 0 && new File(dir(ctx, kind), name).delete();
    }

    private static String displayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (RuntimeException e) {
            // fall through to the default name
        }
        return "list.txt";
    }

    /** Letters, digits, dot, dash and underscore only, so the name can never be a path. */
    private static String safeName(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");
        if (s.endsWith(".tmp")) {
            s += ".txt"; // names() hides *.tmp (crash leftovers), so such a list would be saved but never used
        }
        if (s.length() > 60) {
            s = s.substring(s.length() - 60);
        }
        return s.isEmpty() ? "list.txt" : s;
    }

    /**
     * A time prefix makes every saved name new. The filter reloads a list only when the list of
     * addresses changes, so removing ads.txt and adding another ads.txt must not give the same path.
     */
    private static File uniqueFile(File dir, String name) {
        String stamp = Long.toString(System.currentTimeMillis() / 1000, 36);
        File f = new File(dir, stamp + "-" + name);
        for (int i = 2; f.exists(); i++) {
            f = new File(dir, stamp + "-" + i + "-" + name);
        }
        return f;
    }
}
