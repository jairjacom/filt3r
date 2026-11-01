package com.bridge.filt3r.core.subscription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/**
 * Reads the {@code proxies:} list of a Clash YAML file and keeps the {@code type: ss} entries
 * (name, server, port, cipher, password). Not a YAML parser: it handles the two shapes Clash
 * files use for a proxy, a block ("- name: x" followed by indented keys) and a flow map
 * ("- {name: x, server: y}"). Everything else (other proxy types, entries with a plugin, the
 * rest of the file) is skipped; skipped proxies are counted.
 */
final class ClashParser {

    private ClashParser() {
    }

    static boolean looksLikeClash(String text) {
        return text.startsWith("proxies:") || text.contains("\nproxies:");
    }

    static SubscriptionParser.Result parse(String text) {
        List<Map<String, String>> items = new ArrayList<>();
        Map<String, String> current = null;
        int keyIndent = -1;
        boolean inProxies = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = stripComment(raw);
            if (line.trim().isEmpty()) {
                continue;
            }
            if (!inProxies) {
                inProxies = line.trim().equals("proxies:") && !Character.isWhitespace(line.charAt(0));
                continue;
            }
            int indent = indentOf(line);
            String body = line.trim();
            if (indent == 0 && !body.startsWith("-")) {
                break; // next top-level key: the proxies list is over
            }
            if (body.startsWith("-")) {
                String rest = body.substring(1).trim();
                current = new HashMap<>();
                items.add(current);
                if (rest.startsWith("{")) {
                    parseFlow(rest, current);
                    current = null; // a flow map is complete on its line
                } else {
                    keyIndent = line.indexOf(rest, line.indexOf('-') + 1);
                    putPair(rest, current);
                }
            } else if (current != null && indent == keyIndent) {
                putPair(body, current); // deeper lines (nested maps) are ignored
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        List<ShadowsocksNode> nodes = new ArrayList<>();
        int skipped = 0;
        for (Map<String, String> m : items) {
            ShadowsocksNode n = toNode(m);
            if (n == null) {
                skipped++;
            } else if (seen.add(n.host + ":" + n.port)) {
                nodes.add(n);
            }
        }
        return new SubscriptionParser.Result(nodes, skipped);
    }

    private static ShadowsocksNode toNode(Map<String, String> m) {
        if (!"ss".equals(m.get("type")) || m.containsKey("plugin")) {
            return null;
        }
        try {
            return ShadowsocksNode.of(m.get("cipher"), m.get("password"), m.get("server"),
                    Integer.parseInt(m.get("port")), m.get("name"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** "{a: 1, b: 'x, y'}" into the map; commas inside quotes do not split. */
    private static void parseFlow(String flow, Map<String, String> out) {
        String inner = flow.trim();
        int end = inner.lastIndexOf('}');
        inner = inner.substring(1, end < 0 ? inner.length() : end);
        StringBuilder part = new StringBuilder();
        char quote = 0;
        for (char c : inner.toCharArray()) {
            if (quote != 0) {
                quote = c == quote ? 0 : quote;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ',') {
                putPair(part.toString().trim(), out);
                part.setLength(0);
                continue;
            }
            part.append(c);
        }
        putPair(part.toString().trim(), out);
    }

    private static void putPair(String pair, Map<String, String> out) {
        int colon = pair.indexOf(':');
        if (colon <= 0) {
            return;
        }
        String key = pair.substring(0, colon).trim();
        String value = unquote(pair.substring(colon + 1).trim());
        if (!key.isEmpty() && !value.isEmpty()) {
            out.putIfAbsent(key, value);
        }
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.charAt(0) == '\'' || v.charAt(0) == '"') && v.charAt(v.length() - 1) == v.charAt(0)) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** Drops a " #comment" tail that is outside quotes. */
    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                quote = c == quote ? 0 : quote;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }
}
