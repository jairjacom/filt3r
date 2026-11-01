package com.bridge.filt3r.core.filter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the plain domain rules of an AdBlock-style list (EasyPrivacy) into one domain per line.
 * Kept: "||domain^" and "||domain^$third-party". Tracker lists use the third-party form for
 * domains that exist only to track, so blocking the whole domain is what the list means.
 * Dropped: exceptions (@@), paths, regex, cosmetic rules, wildcards, IP literals and every other
 * option (for example "$domain=..."), because DNS cannot express them.
 */
public final class AdblockDomainRules {

    /** Lists from these places are in AdBlock format and go through {@link #convert}. */
    public static boolean isAdblockList(String url) {
        return url != null && (url.startsWith("https://easylist.to/")
                || url.startsWith("https://raw.githubusercontent.com/easylist/easylist/"));
    }

    private static final Pattern PLAIN_DOMAIN_RULE = Pattern.compile(
            "\\|\\|((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\^(?:\\$third-party)?",
            Pattern.CASE_INSENSITIVE);

    private AdblockDomainRules() {
    }

    /** Reads the whole list (the caller caps its size) and returns the domains as hosts-style lines. */
    public static InputStream convert(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            Matcher m = PLAIN_DOMAIN_RULE.matcher(line.trim());
            if (m.matches() && !isNumeric(lastLabel(m.group(1))))
                out.write((m.group(1).toLowerCase(Locale.ROOT) + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        return new ByteArrayInputStream(out.toByteArray());
    }

    private static String lastLabel(String host) {
        return host.substring(host.lastIndexOf('.') + 1);
    }

    /** A host whose last label is all digits is an IP literal, not a domain. */
    private static boolean isNumeric(String s) {
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) < '0' || s.charAt(i) > '9')
                return false;
        return true;
    }
}
