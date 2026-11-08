package com.bridge.filt3r.core.backup;

import com.bridge.filt3r.core.dnscrypt.DnsCryptStamp;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.socks.FailureMode;
import com.bridge.filt3r.core.subscription.MiniJson;
import com.bridge.filt3r.core.util.IpLiteral;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The settings backup file: plain JSON with settings, exempt apps and domain rules. Subscription
 * addresses and pasted nodes hold secrets, so they are in the file only when the user asked for them.
 *
 * {@link #parse} accepts nothing it does not understand: every value is checked for type and range,
 * unknown keys are ignored, and nothing is applied by this class. The caller applies a {@link Backup}
 * only after the user confirmed.
 */
public final class BackupFormat {

    public static final int VERSION = 1;
    public static final int MAX_BYTES = 256 * 1024;

    private static final int MAX_APPS = 500;
    private static final int MAX_RULES = 5000;
    private static final int MAX_NODES = 200;
    private static final int MAX_SUBSCRIPTIONS = 3;
    private static final int MAX_URL = 2048;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9_*]([A-Za-z0-9_.*-]*[A-Za-z0-9_*])?");
    private static final Pattern LIST_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern EFFECT_KEY = Pattern.compile("fx_[a-z]{1,16}");

    public static final class Rule {
        public final String host;
        public final boolean allow;

        public Rule(String host, boolean allow) {
            this.host = host;
            this.allow = allow;
        }
    }

    public static final class Subscription {
        public final int slot;
        public final String url;
        public final int hours;
        public final boolean on;

        public Subscription(int slot, String url, int hours, boolean on) {
            this.slot = slot;
            this.url = url;
            this.hours = hours;
            this.on = on;
        }
    }

    /**
     * What a backup holds. {@code settings} maps a preference key to a String, Boolean, Integer or
     * List of String; a key that is absent is left alone when the backup is applied.
     */
    public static final class Backup {
        public final Map<String, Object> settings = new LinkedHashMap<>();
        /** Null when the file has no list: the phone's list is left alone. */
        public List<String> exemptApps;
        public final List<Rule> rules = new ArrayList<>();
        public final List<Subscription> subscriptions = new ArrayList<>();
        public final List<String> manualNodes = new ArrayList<>();

        public boolean hasSecrets() {
            return !subscriptions.isEmpty() || !manualNodes.isEmpty();
        }
    }

    private BackupFormat() {
    }

    // ---- writing ----

    public static String write(Backup b, String appVersion) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("app", "filt3r");
        root.put("format", VERSION);
        root.put("version", appVersion);
        root.put("settings", new LinkedHashMap<>(b.settings));
        if (b.exemptApps != null) {
            root.put("exempt_apps", new ArrayList<>(b.exemptApps));
        }
        List<Object> rules = new ArrayList<>();
        for (Rule r : b.rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("host", r.host);
            m.put("allow", r.allow);
            rules.add(m);
        }
        root.put("rules", rules);
        if (!b.subscriptions.isEmpty()) {
            List<Object> subs = new ArrayList<>();
            for (Subscription s : b.subscriptions) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("slot", s.slot);
                m.put("url", s.url);
                m.put("hours", s.hours);
                m.put("on", s.on);
                subs.add(m);
            }
            root.put("subscriptions", subs);
        }
        if (!b.manualNodes.isEmpty()) {
            root.put("manual_nodes", new ArrayList<>(b.manualNodes));
        }
        return MiniJson.write(root);
    }

    // ---- reading ----

    /**
     * @throws IllegalArgumentException with the name of the first field that is not acceptable, or
     *                                  "file" when the text is not a filt3r backup at all
     */
    public static Backup parse(String text) {
        if (text.length() > MAX_BYTES) {
            throw new IllegalArgumentException("file");
        }
        Object parsed;
        try {
            parsed = MiniJson.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("file");
        }
        Map<String, Object> root = asMap(parsed, "file");
        if (!"filt3r".equals(root.get("app")) || !(root.get("format") instanceof Double)
                || ((Double) root.get("format")).intValue() != VERSION) {
            throw new IllegalArgumentException("file");
        }
        Backup b = new Backup();
        Object settings = root.get("settings");
        if (settings != null) {
            for (Map.Entry<String, Object> e : asMap(settings, "settings").entrySet()) {
                Object v = checkSetting(e.getKey(), e.getValue());
                if (v != IGNORED) {
                    b.settings.put(e.getKey(), v);
                }
            }
        }
        checkStampPair(b.settings);
        if (root.get("exempt_apps") != null) {
            b.exemptApps = new ArrayList<>();
            for (Object o : asList(root.get("exempt_apps"), "exempt_apps", MAX_APPS)) {
                String p = asString(o, "exempt_apps", 200);
                if (!PACKAGE.matcher(p).matches()) {
                    throw new IllegalArgumentException("exempt_apps");
                }
                b.exemptApps.add(p);
            }
        }
        if (root.get("rules") != null) {
            for (Object o : asList(root.get("rules"), "rules", MAX_RULES)) {
                Map<String, Object> m = asMap(o, "rules");
                String host = asString(m.get("host"), "rules", 253);
                if (!HOST.matcher(host).matches() || !(m.get("allow") instanceof Boolean)) {
                    throw new IllegalArgumentException("rules");
                }
                b.rules.add(new Rule(host, (Boolean) m.get("allow")));
            }
        }
        if (root.get("subscriptions") != null) {
            for (Object o : asList(root.get("subscriptions"), "subscriptions", MAX_SUBSCRIPTIONS)) {
                Map<String, Object> m = asMap(o, "subscriptions");
                int slot = asInt(m.get("slot"), "subscriptions", 0, MAX_SUBSCRIPTIONS - 1);
                String url = asString(m.get("url"), "subscriptions", MAX_URL);
                if (!isHttpsUrl(url) || !(m.get("on") instanceof Boolean)) {
                    throw new IllegalArgumentException("subscriptions");
                }
                int hours = asInt(m.get("hours"), "subscriptions", 1, 24 * 365);
                b.subscriptions.add(new Subscription(slot, url, hours, (Boolean) m.get("on")));
            }
        }
        if (root.get("manual_nodes") != null) {
            for (Object o : asList(root.get("manual_nodes"), "manual_nodes", MAX_NODES)) {
                String uri = asString(o, "manual_nodes", MAX_URL);
                try {
                    if (IpLiteral.parse(ShadowsocksNode.parse(uri).host) == null) {
                        throw new IllegalArgumentException("manual_nodes");
                    }
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("manual_nodes");
                }
                b.manualNodes.add(uri);
            }
        }
        return b;
    }

    private static final Object IGNORED = new Object();

    /** Validates one settings entry; returns the value to keep or {@link #IGNORED} for an unknown key. */
    private static Object checkSetting(String key, Object v) {
        switch (key) {
            case "query_log":
            case "node_sort_speed":
            case "ip_block":
            case "test_only_cheap":
            case "toolbar_pinned":
            case "pure_black":
                return asBool(v, key);
            case "failure_mode":
                try {
                    return FailureMode.valueOf(asString(v, key, 20)).name();
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(key);
                }
            case "resolver_name":
                return asString(v, key, 120);
            case "resolver_stamp": {
                String stamp = asString(v, key, 1024);
                try {
                    DnsCryptStamp.parse(stamp);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(key);
                }
                return stamp;
            }
            case "filter_url": {
                String urls = asString(v, key, 8192);
                for (String u : urls.split("[;\\s]+")) {
                    if (!u.isEmpty() && !isHttpsUrl(u)) {
                        throw new IllegalArgumentException(key);
                    }
                }
                return urls;
            }
            case "enabled_lists": {
                List<String> ids = new ArrayList<>();
                for (Object o : asList(v, key, 200)) {
                    String id = asString(o, key, 64);
                    if (!LIST_ID.matcher(id).matches()) {
                        throw new IllegalArgumentException(key);
                    }
                    ids.add(id);
                }
                return ids;
            }
            case "list_refresh_days":
                return asInt(v, key, 1, 30);
            case "toolbar_style":
                return oneOf(v, key, "floating", "docked");
            case "color_scheme":
                return oneOf(v, key, "default", "standard", "vibrant", "expressive", "dynamic");
            case "theme_mode":
                return asInt(v, key, 0, 2);
            case "effects_level":
                return asInt(v, key, 0, 3);
            case "transition_style":
                return asInt(v, key, 0, 2);
            case "seed_color":
                return asInt(v, key, Integer.MIN_VALUE, Integer.MAX_VALUE);
            default:
                if (EFFECT_KEY.matcher(key).matches()) {
                    return asBool(v, key);
                }
                return IGNORED; // a setting from a newer version
        }
    }

    /** A resolver name without its stamp (or the other way round) would leave the two out of step. */
    private static void checkStampPair(Map<String, Object> s) {
        if (s.containsKey("resolver_name") != s.containsKey("resolver_stamp")) {
            throw new IllegalArgumentException("resolver_stamp");
        }
    }

    private static boolean isHttpsUrl(String u) {
        if (u.length() <= 8 || u.length() > MAX_URL || !u.regionMatches(true, 0, "https://", 0, 8)) {
            return false;
        }
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c <= ' ' || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o, String field) {
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException(field);
        }
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o, String field, int max) {
        if (!(o instanceof List) || ((List<Object>) o).size() > max) {
            throw new IllegalArgumentException(field);
        }
        return (List<Object>) o;
    }

    private static String asString(Object o, String field, int max) {
        if (!(o instanceof String) || ((String) o).length() > max) {
            throw new IllegalArgumentException(field);
        }
        String s = (String) o;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < ' ' || s.charAt(i) == 0x7f) {
                throw new IllegalArgumentException(field); // no control characters in any value
            }
        }
        return s;
    }

    private static Boolean asBool(Object o, String field) {
        if (!(o instanceof Boolean)) {
            throw new IllegalArgumentException(field);
        }
        return (Boolean) o;
    }

    private static Integer asInt(Object o, String field, int min, int max) {
        if (!(o instanceof Double)) {
            throw new IllegalArgumentException(field);
        }
        double d = (Double) o;
        if (d != Math.rint(d) || d < min || d > max) {
            throw new IllegalArgumentException(field);
        }
        return (int) d;
    }

    private static String oneOf(Object o, String field, String... allowed) {
        String s = asString(o, field, 20);
        for (String a : allowed) {
            if (a.equals(s)) {
                return s;
            }
        }
        throw new IllegalArgumentException(field);
    }
}
