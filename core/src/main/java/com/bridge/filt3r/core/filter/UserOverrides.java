package com.bridge.filt3r.core.filter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The user's own allow and block rules for single domains, kept in the filter's additionalHosts.txt
 * ("host" blocks it, "!host" allows it; a rule covers the domain and its sub domains). Comments and
 * custom IP mappings in the file are left alone. A change reaches the running filter at once; a
 * filter that is not loaded yet reads the file when it loads.
 */
public final class UserOverrides {

    /** One rule: allow (true) or block (false) a domain. */
    public static final class Entry {
        public final String host;
        public final boolean allow;

        Entry(String host, boolean allow) {
            this.host = host;
            this.allow = allow;
        }
    }

    private final File file;

    public UserOverrides(File file) {
        this.file = file;
    }

    /** Every allow/block rule, in file order. */
    public synchronized List<Entry> list() throws IOException {
        List<Entry> out = new ArrayList<>();
        for (String line : readLines()) {
            Entry e = parse(line);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    /** TRUE = allowed, FALSE = blocked, null = no rule for this exact domain. */
    public synchronized Boolean find(String host) throws IOException {
        String h = normalise(host);
        for (String line : readLines()) {
            Entry e = parse(line);
            if (e != null && e.host.equals(h)) {
                return e.allow;
            }
        }
        return null;
    }

    /**
     * Allows or blocks a domain, replacing an earlier rule for it.
     *
     * @return the domain as stored (lower case, no trailing dot)
     * @throws IllegalArgumentException if the text is not a domain name
     */
    public synchronized String set(String host, boolean allow) throws IOException {
        String h = normalise(host);
        List<String> kept = withoutRule(h);
        kept.add(allow ? "!" + h : h);
        write(kept);
        DNSFilterManager.applyOverruleLive(h, !allow, true);
        return h;
    }

    /** Removes the rule for this domain, if any. */
    public synchronized void remove(String host) throws IOException {
        String h = normalise(host);
        write(withoutRule(h));
    }

    /** The lines without any rule for {@code h}; the running filter drops the old rule too. */
    private List<String> withoutRule(String h) throws IOException {
        List<String> kept = new ArrayList<>();
        for (String line : readLines()) {
            Entry e = parse(line);
            if (e != null && e.host.equals(h)) {
                DNSFilterManager.applyOverruleLive(h, !e.allow, false);
            } else {
                kept.add(line);
            }
        }
        return kept;
    }

    private List<String> readLines() throws IOException {
        return file.exists() ? new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
                : new ArrayList<>();
    }

    private void write(List<String> lines) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Files.write(file.toPath(), String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    /** A rule line ("host" or "!host"); null for comments, blanks and custom IP mappings. */
    private static Entry parse(String line) {
        String s = line.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.startsWith("#") || s.startsWith(">")) {
            return null;
        }
        boolean allow = s.startsWith("!");
        String host = allow ? s.substring(1).trim() : s;
        return host.isEmpty() || host.indexOf(' ') >= 0 || host.indexOf('\t') >= 0 ? null : new Entry(host, allow);
    }

    /** Lower-cases, trims and drops a trailing dot; accepts only letters, digits, '.', '-' and '_'. */
    static String normalise(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.isEmpty() || h.length() > 253 || h.startsWith(".") || h.contains("..")) {
            throw new IllegalArgumentException("Not a domain name");
        }
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-' || c == '_';
            if (!ok) {
                throw new IllegalArgumentException("Not a domain name");
            }
        }
        return h;
    }
}
