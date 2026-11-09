package com.bridge.filt3r.core.subscription;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer (core has no Android imports, so no org.json). Used for subscription
 * files and settings backups. Reads to Map, List, String, Double, Boolean or null. Throws
 * IllegalArgumentException on bad input.
 */
public final class MiniJson {

    private static final int MAX_DEPTH = 16;

    private final String s;
    private int pos;

    private MiniJson(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        Object v = p.value(0);
        p.skipSpace();
        if (p.pos != p.s.length()) {
            throw p.error("trailing data");
        }
        return v;
    }

    /**
     * Writes a Map (String keys), List, String, Boolean, Number or null as indented JSON.
     *
     * @throws IllegalArgumentException for any other type
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.append('\n').toString();
    }

    private static void write(StringBuilder out, Object v, int depth) {
        if (v == null) {
            out.append("null");
        } else if (v instanceof String) {
            quote(out, (String) v);
        } else if (v instanceof Boolean) {
            out.append(v);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("not a JSON number");
            }
            out.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d));
        } else if (v instanceof Number) {
            out.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String)) {
                    throw new IllegalArgumentException("JSON keys must be strings");
                }
                indent(out, depth + 1);
                quote(out, (String) e.getKey());
                out.append(": ");
                write(out, e.getValue(), depth + 1);
                out.append(++i < m.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append('}');
        } else if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                indent(out, depth + 1);
                write(out, l.get(i), depth + 1);
                out.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append(']');
        } else {
            throw new IllegalArgumentException("cannot write " + v.getClass().getSimpleName());
        }
    }

    private static void indent(StringBuilder out, int depth) {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("too deep");
        }
        skipSpace();
        if (pos >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(pos);
        if (c == '{') {
            return object(depth);
        }
        if (c == '[') {
            return array(depth);
        }
        if (c == '"') {
            return string();
        }
        if (s.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (peek() == '}') {
            pos++;
            return m;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("expected a key");
            }
            String key = string();
            skipSpace();
            expect(':');
            m.put(key, value(depth + 1));
            skipSpace();
            if (peek() == ',') {
                pos++;
            } else {
                expect('}');
                return m;
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        pos++;
        skipSpace();
        if (peek() == ']') {
            pos++;
            return l;
        }
        while (true) {
            l.add(value(depth + 1));
            skipSpace();
            if (peek() == ',') {
                pos++;
            } else {
                expect(']');
                return l;
            }
        }
    }

    private String string() {
        pos++; // opening quote
        StringBuilder b = new StringBuilder();
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (pos >= s.length()) {
                break;
            }
            char e = s.charAt(pos++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (pos + 4 > s.length()) {
                        throw error("bad escape");
                    }
                    try {
                        b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad escape");
                    }
                    pos += 4;
                    break;
                default: b.append(e); // quote, backslash, slash
            }
        }
        throw error("unterminated string");
    }

    private Double number() {
        int start = pos;
        while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
            pos++;
        }
        try {
            return Double.valueOf(s.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("bad value");
        }
    }

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected " + c);
        }
        pos++;
    }

    private void skipSpace() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("bad JSON at " + pos + ": " + what);
    }
}
