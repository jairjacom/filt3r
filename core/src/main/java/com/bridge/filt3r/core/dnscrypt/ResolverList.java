package com.bridge.filt3r.core.dnscrypt;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the DNSCrypt project's public resolver list (markdown: "## name", a description, then one
 * or more sdns:// stamps). Keeps DNSCrypt and DoH stamps the tunnel can reach (not IPv6-only);
 * relay and other stamp types are skipped.
 */
public final class ResolverList {

    /** One resolver. {@link #name} is made unique when a list entry has several stamps. */
    public static final class Entry {
        public final String name;
        public final String description;
        public final String stampText;
        public final ResolverStamp stamp;

        Entry(String name, String description, String stampText, ResolverStamp stamp) {
            this.name = name;
            this.description = description;
            this.stampText = stampText;
            this.stamp = stamp;
        }
    }

    private ResolverList() {
    }

    public static List<Entry> parse(String markdown) {
        List<Entry> out = new ArrayList<>();
        String name = null;
        StringBuilder description = new StringBuilder();
        int stampsInEntry = 0;
        for (String raw : markdown.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("## ")) {
                name = line.substring(3).trim();
                description.setLength(0);
                stampsInEntry = 0;
            } else if (name != null && line.startsWith("sdns://")) {
                try {
                    ResolverStamp s = ResolverStamp.parse(line);
                    if (s.usable()) {
                        stampsInEntry++;
                        out.add(new Entry(stampsInEntry == 1 ? name : name + " #" + stampsInEntry,
                                description.toString().trim(), line, s));
                    }
                } catch (IllegalArgumentException e) {
                    // not a DNSCrypt or DoH stamp we can use
                }
            } else if (name != null && !line.isEmpty() && stampsInEntry == 0) {
                if (description.length() > 0) {
                    description.append(' ');
                }
                description.append(line);
            }
        }
        return out;
    }
}
