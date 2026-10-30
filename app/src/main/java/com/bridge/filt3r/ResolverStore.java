package com.bridge.filt3r;

import com.bridge.filt3r.core.dnscrypt.DnsCryptStamp;
import com.bridge.filt3r.core.dnscrypt.ResolverTester;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The resolvers that passed the last test through a node (files/resolvers.txt, one tab-separated
 * line each: milliseconds, name, stamp, description). The file's modification time is the time of
 * the last refresh. Resolvers that failed the test are not stored.
 */
final class ResolverStore {

    /** One working resolver. The flags are the operator's claims from the stamp, not verified. */
    static final class Info {
        final String name;
        final String description;
        final String stamp;
        final long millis;
        final boolean dnssec;
        final boolean noLogs;
        final boolean noFilter;

        Info(String name, String description, String stamp, long millis, DnsCryptStamp parsed) {
            this.name = name;
            this.description = description;
            this.stamp = stamp;
            this.millis = millis;
            this.dnssec = parsed.claimsDnssec();
            this.noLogs = parsed.claimsNoLogs();
            this.noFilter = parsed.claimsNoFilter();
        }
    }

    private static final String FILE = "resolvers.txt";

    private final File dir;

    ResolverStore(File filesDir) {
        this.dir = filesDir;
    }

    /** The working resolvers, fastest first. */
    List<Info> load() {
        List<Info> out = new ArrayList<>();
        File f = new File(dir, FILE);
        if (!f.isFile()) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", 4);
                if (p.length < 3) {
                    continue;
                }
                try {
                    out.add(new Info(p[1], p.length > 3 ? p[3] : "", p[2], Long.parseLong(p[0]), DnsCryptStamp.parse(p[2])));
                } catch (IllegalArgumentException e) {
                    // a bad line is skipped
                }
            }
        } catch (IOException e) {
            // unreadable file: treated as empty
        }
        return out;
    }

    boolean isOlderThan(long maxAgeMs) {
        File f = new File(dir, FILE);
        return !f.isFile() || System.currentTimeMillis() - f.lastModified() > maxAgeMs;
    }

    void save(List<ResolverTester.Result> results) throws IOException {
        StringBuilder text = new StringBuilder();
        for (ResolverTester.Result r : results) {
            text.append(r.millis).append('\t').append(clean(r.entry.name)).append('\t')
                    .append(r.entry.stampText).append('\t').append(clean(r.entry.description)).append('\n');
        }
        File tmp = new File(dir, FILE + ".tmp");
        Files.write(tmp.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), new File(dir, FILE).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
