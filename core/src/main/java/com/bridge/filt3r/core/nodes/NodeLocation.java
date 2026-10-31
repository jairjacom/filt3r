package com.bridge.filt3r.core.nodes;

/**
 * Where a node is, read from its subscription remark, e.g. "🇦🇹 Vienna, 9, Austria": a flag emoji,
 * the city, optional extra parts and the country last. There is no GeoIP lookup; a remark without
 * a flag gives an empty country code and the whole remark as the city.
 */
public final class NodeLocation {

    /** Two-letter ISO code from the flag emoji, or "" if the remark has none. */
    public final String countryCode;
    /** The flag emoji itself, or "". */
    public final String flag;
    /** First part of the remark after the flag, or "Unknown" if the remark is empty. */
    public final String city;

    private NodeLocation(String countryCode, String flag, String city) {
        this.countryCode = countryCode;
        this.flag = flag;
        this.city = city;
    }

    public static NodeLocation parse(String remark) {
        String s = remark == null ? "" : remark.trim();
        String code = "";
        String flag = "";
        // A flag is two regional-indicator code points (U+1F1E6..U+1F1FF), 2 chars each.
        if (s.length() >= 4) {
            int a = s.codePointAt(0);
            int b = s.codePointAt(Character.charCount(a));
            if (isRegional(a) && isRegional(b)) {
                code = "" + (char) ('A' + (a - 0x1F1E6)) + (char) ('A' + (b - 0x1F1E6));
                int end = Character.charCount(a) + Character.charCount(b);
                flag = s.substring(0, end);
                s = s.substring(end).trim();
            }
        }
        int comma = s.indexOf(',');
        String city = (comma >= 0 ? s.substring(0, comma) : s).trim();
        return new NodeLocation(code, flag, city.isEmpty() ? "Unknown" : city);
    }

    private static boolean isRegional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }
}
