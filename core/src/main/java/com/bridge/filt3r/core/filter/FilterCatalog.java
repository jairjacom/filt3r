package com.bridge.filt3r.core.filter;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** The selectable domain blocklists, read from the four parallel list keys of the filter config. */
public final class FilterCatalog {

    public static final class Entry {
        public final String id;
        public final String category;
        public final String url;
        public final boolean onByDefault;

        Entry(String id, String category, String url, boolean onByDefault) {
            this.id = id;
            this.category = category;
            this.url = url;
            this.onByDefault = onByDefault;
        }
    }

    private FilterCatalog() {
    }

    /** Reads the list entries from a dnsfilter.conf stream. The four key lists must have equal length. */
    public static List<Entry> fromConf(InputStream in) throws IOException {
        Properties p = new Properties();
        p.load(in);
        String[] urls = split(p.getProperty("filterAutoUpdateURL", ""));
        String[] ids = split(p.getProperty("filterAutoUpdateURL_IDs", ""));
        String[] categories = split(p.getProperty("filterAutoUpdateURL_categories", ""));
        String[] switches = split(p.getProperty("filterAutoUpdateURL_switchs", ""));
        if (urls.length != ids.length || urls.length != categories.length || urls.length != switches.length)
            throw new IOException("filter config: list keys have different counts");
        List<Entry> entries = new ArrayList<>(urls.length);
        for (int i = 0; i < urls.length; i++)
            entries.add(new Entry(ids[i], categories[i], urls[i], Boolean.parseBoolean(switches[i])));
        return entries;
    }

    /**
     * Replaces the list switches in the config with the chosen IDs. Keeps the order of the URL key,
     * so the switch at each position matches its list.
     */
    static void applyEnabled(Properties config, Set<String> chosen) {
        String[] ids = split(config.getProperty("filterAutoUpdateURL_IDs", ""));
        StringBuilder switches = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0)
                switches.append("; ");
            switches.append(chosen.contains(ids[i]));
        }
        config.setProperty("filterAutoUpdateURL_switchs", switches.toString());
    }

    /** Copies the four list keys from the bundled defaults, so a switch can reach a list added after install. */
    static void copyListKeys(Properties defaults, Properties config) {
        for (String key : new String[] {"filterAutoUpdateURL", "filterAutoUpdateURL_IDs", "filterAutoUpdateURL_categories", "filterAutoUpdateURL_switchs"})
            config.setProperty(key, defaults.getProperty(key, ""));
    }

    /** Maps each list URL in the config to its ID, for status lines. */
    static java.util.Map<String, String> namesByUrl(Properties config) {
        String[] urls = split(config.getProperty("filterAutoUpdateURL", ""));
        String[] ids = split(config.getProperty("filterAutoUpdateURL_IDs", ""));
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (int i = 0; i < urls.length && i < ids.length; i++)
            names.put(urls[i], ids[i]);
        return names;
    }

    /** Appends the user's own list URLs ("; " separated) to the four list keys, each switched on. */
    static void addCustom(Properties config, String customUrls) {
        String[] extra = split(customUrls);
        String[] keys = {"filterAutoUpdateURL", "filterAutoUpdateURL_IDs", "filterAutoUpdateURL_categories", "filterAutoUpdateURL_switchs"};
        for (int i = 0; i < extra.length; i++) {
            String[] values = {extra[i], "custom-" + (i + 1), "custom", "true"};
            for (int k = 0; k < keys.length; k++) {
                String old = config.getProperty(keys[k], "").trim();
                config.setProperty(keys[k], old.isEmpty() ? values[k] : old + "; " + values[k]);
            }
        }
    }

    private static String[] split(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split(";")) {
            String t = part.trim();
            if (!t.isEmpty())
                parts.add(t);
        }
        return parts.toArray(new String[0]);
    }
}
