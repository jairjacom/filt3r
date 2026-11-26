package com.bridge.filt3r.core.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * A plain-text log of DNS queries, one line each: "MM-dd HH:mm:ss ALLOWED|BLOCKED name[TAB app[TAB key]]",
 * where app is the asking app's name and key its package name, each when known. It keeps
 * the last {@value #MAX_LINES} lines in memory and in one private file. Nothing is written while
 * the enabled switch is off. The file is compacted when it grows past twice the limit.
 */
public final class QueryLog {

    public static final int MAX_LINES = 1000;

    private final File file;
    private final BooleanSupplier enabled;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private int linesInFile;
    private BufferedWriter writer;
    private final SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT);

    /** @param enabled asked on every query, so the switch takes effect at once */
    public QueryLog(File file, BooleanSupplier enabled) {
        this.file = file;
        this.enabled = enabled;
        load();
    }

    /** One parsed log line. */
    public static final class Entry {
        public final String time;
        public final boolean blocked;
        public final String host;
        public final String app; // null when the line has no app
        public final String key; // null when the line has no key (lines written before keys existed)

        Entry(String time, boolean blocked, String host, String app, String key) {
            this.time = time;
            this.blocked = blocked;
            this.host = host;
            this.app = app;
            this.key = key;
        }
    }

    private static final int TIME_LENGTH = "MM-dd HH:mm:ss".length();
    private static final int MAX_APP_LENGTH = 64;

    /** "MM-dd HH:mm:ss ALLOWED|BLOCKED name[TAB app]" -> Entry; null for a line that does not fit. */
    public static Entry parse(String line) {
        if (line.length() < TIME_LENGTH + 9) {
            return null;
        }
        String rest = line.substring(TIME_LENGTH + 1);
        int space = rest.indexOf(' ');
        if (space < 0) {
            return null;
        }
        String verdict = rest.substring(0, space);
        if (!verdict.equals("BLOCKED") && !verdict.equals("ALLOWED")) {
            return null;
        }
        String[] fields = rest.substring(space + 1).split("\t", -1);
        String app = fields.length > 1 && !fields[1].isEmpty() ? fields[1] : null;
        String key = fields.length > 2 ? fields[2] : null;
        return new Entry(line.substring(0, TIME_LENGTH), verdict.equals("BLOCKED"), fields[0], app, key);
    }

    /**
     * An app picks its own label, so it is untrusted: control and format characters (line breaks, tabs,
     * right-to-left overrides) become spaces and it is cut to {@value #MAX_APP_LENGTH} characters.
     * Null when nothing printable is left.
     */
    private static String cleanApp(String app) {
        if (app == null) {
            return null;
        }
        String clean = app.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
        if (clean.codePointCount(0, clean.length()) > MAX_APP_LENGTH) {
            clean = clean.substring(0, clean.offsetByCodePoints(0, MAX_APP_LENGTH)).trim();
        }
        return clean.isEmpty() ? null : clean;
    }

    /** A package name is plain text; control characters are blanked so the key cannot break the line. */
    private static String cleanKey(String key) {
        if (key == null) {
            return null;
        }
        String clean = key.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
        return clean.isEmpty() ? null : clean;
    }

    /**
     * Called for every A/AAAA query the filter decides on.
     *
     * @param app the asking app's name, or null when unknown
     * @param key the asking app's package name, or null when unknown
     */
    public synchronized void add(String host, boolean blocked, String app, String key) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        String line = time.format(new Date()) + (blocked ? " BLOCKED " : " ALLOWED ") + host;
        String name = cleanApp(app);
        String appKey = cleanKey(key);
        if (name != null || appKey != null) {
            line += "\t" + (name == null ? "" : name);
        }
        if (appKey != null) {
            line += "\t" + appKey;
        }
        lines.addLast(line);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
        try {
            if (linesInFile >= 2 * MAX_LINES) {
                rewrite();
            } else {
                open().write(line);
                writer.newLine();
                writer.flush();
                linesInFile++;
            }
        } catch (IOException e) {
            closeWriter(); // the in-memory lines stay; the next query tries the file again
        }
    }

    /** The kept lines, oldest first. */
    public synchronized List<String> lines() {
        return new ArrayList<>(lines);
    }

    public synchronized void clear() {
        lines.clear();
        closeWriter();
        linesInFile = 0;
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            // an undeletable file is truncated on the next write
        }
    }

    /** Drops every line whose app key is in keys, then rewrites the file. */
    public synchronized void removeApps(Set<String> keys) {
        lines.removeIf(line -> {
            Entry entry = parse(line);
            return entry != null && entry.key != null && keys.contains(entry.key);
        });
        try {
            rewrite();
        } catch (IOException e) {
            closeWriter(); // the in-memory lines are already right; the next query writes the file again
        }
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        try {
            List<String> all = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            linesInFile = all.size();
            for (int i = Math.max(0, all.size() - MAX_LINES); i < all.size(); i++) {
                lines.addLast(all.get(i));
            }
        } catch (IOException e) {
            // unreadable: start empty
        }
    }

    private BufferedWriter open() throws IOException {
        if (writer == null) {
            writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return writer;
    }

    private void rewrite() throws IOException {
        closeWriter();
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        linesInFile = lines.size();
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                // nothing to do
            }
            writer = null;
        }
    }
}
