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
import java.util.function.BooleanSupplier;

/**
 * A plain-text log of DNS queries, one line each: "MM-dd HH:mm:ss ALLOWED|BLOCKED name". It keeps
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

    /** Called for every A/AAAA query the filter decides on. */
    public synchronized void add(String host, boolean blocked) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        String line = time.format(new Date()) + (blocked ? " BLOCKED " : " ALLOWED ") + host;
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
