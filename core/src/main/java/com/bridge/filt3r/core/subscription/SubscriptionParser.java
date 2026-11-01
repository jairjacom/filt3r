package com.bridge.filt3r.core.subscription;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.util.Text;

/**
 * Reads a subscription body into nodes. Accepted: (a) a JSON array of
 * {server, server_port, password, method, plugin, remarks} (Shadowmere), (b) base64 text of
 * newline-separated ss:// URLs, (c) plain ss:// lines, (d) a Clash YAML file (its type: ss
 * proxies). Entries we cannot use (plugins, other
 * ciphers, malformed) are skipped and counted, never fatal.
 */
public final class SubscriptionParser {

    /** The usable nodes (no duplicates) and how many entries were skipped. */
    public static final class Result {
        public final List<ShadowsocksNode> nodes;
        public final int skipped;

        Result(List<ShadowsocksNode> nodes, int skipped) {
            this.nodes = nodes;
            this.skipped = skipped;
        }
    }

    private SubscriptionParser() {
    }

    /** @throws IllegalArgumentException if the body is none of the accepted formats */
    public static Result parse(String body) {
        String text = body.trim();
        if (text.startsWith("[")) {
            return fromJson(text);
        }
        if (ClashParser.looksLikeClash(text)) {
            return ClashParser.parse(text);
        }
        if (!text.contains("ss://")) {
            text = decodeBase64Text(text);
        }
        return fromLines(text);
    }

    private static Result fromJson(String text) {
        Object root = MiniJson.parse(text);
        if (!(root instanceof List)) {
            throw new IllegalArgumentException("expected a JSON array");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ShadowsocksNode> nodes = new ArrayList<>();
        int skipped = 0;
        for (Object o : (List<?>) root) {
            ShadowsocksNode n = o instanceof Map ? fromEntry((Map<?, ?>) o) : null;
            if (n == null) {
                skipped++;
            } else if (seen.add(n.host + ":" + n.port)) {
                nodes.add(n);
            }
        }
        return new Result(nodes, skipped);
    }

    private static ShadowsocksNode fromEntry(Map<?, ?> m) {
        Object plugin = m.get("plugin");
        if (plugin instanceof String && !((String) plugin).isEmpty()) {
            return null; // no plugin support
        }
        Object server = m.get("server");
        Object port = m.get("server_port");
        Object password = m.get("password");
        Object method = m.get("method");
        Object remarks = m.get("remarks");
        if (!(server instanceof String) || !(port instanceof Double) || !(password instanceof String)
                || !(method instanceof String)) {
            return null;
        }
        try {
            return ShadowsocksNode.of((String) method, (String) password, ((String) server).trim(),
                    (int) (double) (Double) port, remarks instanceof String ? (String) remarks : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Result fromLines(String text) {
        Set<String> seen = new LinkedHashSet<>();
        List<ShadowsocksNode> nodes = new ArrayList<>();
        int skipped = 0;
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty()) {
                continue;
            }
            try {
                ShadowsocksNode n = ShadowsocksNode.parse(l);
                if (seen.add(n.host + ":" + n.port)) {
                    nodes.add(n);
                }
            } catch (IllegalArgumentException e) {
                skipped++;
            }
        }
        if (nodes.isEmpty() && skipped == 0) {
            throw new IllegalArgumentException("no ss:// entries found");
        }
        return new Result(nodes, skipped);
    }

    private static String decodeBase64Text(String text) {
        try {
            String t = Text.trimTrailing(text.replaceAll("\\s+", "").replace('-', '+').replace('_', '/'), '=');
            return new String(Base64.getDecoder().decode(t), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not JSON, base64 or ss:// lines");
        }
    }
}
