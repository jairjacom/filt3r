package com.bridge.filt3r.core.filter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The user's own rules, kept in the filter's additionalHosts.txt: "host" blocks a domain, "!host"
 * allows it (a rule covers the domain and its sub domains), and ">host ip" answers a name with a
 * fixed address (an answer covers exactly that name). A host may hold "*" as a wildcard, as in
 * "*.example.com". Comments in the file are left alone. A change reaches the running filter at
 * once; a filter that is not loaded yet reads the file when it loads.
 */
public final class UserOverrides {

    /** One rule: allow (true) or block (false) a domain, or a custom answer when {@link #ip} is set. */
    public static final class Entry {
        public final String host;
        /** Meaningful only when {@link #ip} is null. */
        public final boolean allow;
        /** The fixed address for a custom answer; null for an allow or block rule. */
        public final String ip;

        Entry(String host, boolean allow, String ip) {
            this.host = host;
            this.allow = allow;
            this.ip = ip;
        }

        public boolean isAnswer() {
            return ip != null;
        }
    }

    private final File file;

    public UserOverrides(File file) {
        this.file = file;
    }

    /** Every rule and custom answer, in file order. */
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
        String h = normalisePattern(host);
        for (String line : readLines()) {
            Entry e = parse(line);
            if (e != null && !e.isAnswer() && e.host.equals(h)) {
                return e.allow;
            }
        }
        return null;
    }

    /**
     * Allows or blocks a domain, replacing an earlier rule for it.
     *
     * @return the domain as stored (lower case, no trailing dot)
     * @throws IllegalArgumentException if the text is not a domain name (a "*" wildcard is allowed)
     */
    public synchronized String set(String host, boolean allow) throws IOException {
        String h = normalisePattern(host);
        List<String> kept = withoutRule(h);
        kept.add(allow ? "!" + h : h);
        write(kept);
        DNSFilterManager.applyOverruleLive(h, !allow, true);
        return h;
    }

    /** Removes the allow or block rule for this domain, if any. */
    public synchronized void remove(String host) throws IOException {
        String h = normalisePattern(host);
        write(withoutRule(h));
    }

    /**
     * Answers a name with a fixed address, replacing an earlier answer for it. It beats the filter
     * lists and every allow or block rule.
     *
     * @param ip an IPv4 or IPv6 address, written as numbers
     * @return the entry as stored
     * @throws IllegalArgumentException if the host is not a domain name or the address is not an IP address
     */
    public synchronized Entry setAnswer(String host, String ip) throws IOException {
        String h = normalisePattern(host);
        String a = normaliseIp(ip);
        List<String> kept = withoutAnswer(h);
        kept.add(">" + h + " " + a);
        write(kept);
        applyAnswers(kept);
        return new Entry(h, false, a);
    }

    /** Removes the custom answer for this name, if any. */
    public synchronized void removeAnswer(String host) throws IOException {
        List<String> kept = withoutAnswer(normalisePattern(host));
        write(kept);
        applyAnswers(kept);
    }

    private static void applyAnswers(List<String> lines) {
        List<String[]> answers = new ArrayList<>();
        for (String line : lines) {
            Entry e = parse(line);
            if (e != null && e.isAnswer()) {
                answers.add(new String[]{e.host, e.ip});
            }
        }
        DNSFilterManager.applyAnswersLive(answers);
    }

    private List<String> withoutAnswer(String h) throws IOException {
        List<String> kept = new ArrayList<>();
        for (String line : readLines()) {
            Entry e = parse(line);
            if (e == null || !e.isAnswer() || !e.host.equals(h)) {
                kept.add(line);
            }
        }
        return kept;
    }

    /** The lines without any allow/block rule for {@code h}; the running filter drops the old rule too. */
    private List<String> withoutRule(String h) throws IOException {
        List<String> kept = new ArrayList<>();
        for (String line : readLines()) {
            Entry e = parse(line);
            if (e != null && !e.isAnswer() && e.host.equals(h)) {
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

    /** A rule line ("host", "!host" or ">host ip"); null for comments, blanks and lines that do not fit. */
    private static Entry parse(String line) {
        String s = line.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.startsWith("#")) {
            return null;
        }
        if (s.startsWith(">")) {
            String[] parts = s.substring(1).trim().split("\\s+");
            return parts.length == 2 && !parts[0].isEmpty() ? new Entry(parts[0], false, parts[1]) : null;
        }
        boolean allow = s.startsWith("!");
        String host = allow ? s.substring(1).trim() : s;
        return host.isEmpty() || host.indexOf(' ') >= 0 || host.indexOf('\t') >= 0 ? null : new Entry(host, allow, null);
    }

    /** Like {@link #normalise} but lets "*" stand for any text; at least one letter or digit must remain. */
    static String normalisePattern(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (h.indexOf('*') < 0) {
            return normalise(h);
        }
        boolean literal = false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            literal |= c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
        }
        if (!literal) {
            throw new IllegalArgumentException("Not a domain name");
        }
        normalise(h.replace('*', 'x')); // checks the rest of the name
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    /**
     * An IPv4 address of four numbers, or an IPv6 address; never a host name. Returns it lower case.
     *
     * @throws IllegalArgumentException if the text is not an IP address
     */
    public static String normaliseIp(String ip) {
        String a = ip == null ? "" : ip.trim().toLowerCase(Locale.ROOT);
        if (a.indexOf(':') < 0) {
            String[] parts = a.split("\\.", -1);
            if (parts.length != 4) {
                throw new IllegalArgumentException("Not an IP address");
            }
            for (String p : parts) {
                if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(c -> c >= '0' && c <= '9') || Integer.parseInt(p) > 255) {
                    throw new IllegalArgumentException("Not an IP address");
                }
            }
            return a;
        }
        if (a.length() > 45 || !a.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c == ':' || c == '.')) {
            throw new IllegalArgumentException("Not an IP address");
        }
        try {
            java.net.InetAddress.getByName(a); // has a ':', so this is parsed as a literal and never looks anything up
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("Not an IP address");
        }
        return a;
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
