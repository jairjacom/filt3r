package com.bridge.filt3r.core.http;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import com.bridge.filt3r.core.subscription.MiniJson;

/**
 * Looks up where an IP address is (country, city, provider) through two free HTTPS JSON services:
 * ipwho.is first and ipapi.co only if that one fails.
 */
public final class IpLookup {

    /** Fetches a URL as text; {@link HttpsFetcher#get} fits. */
    public interface Fetcher {
        String get(String url) throws IOException;
    }

    public static final class Result {
        public final String ip;
        public final String countryCode;
        public final String country;
        public final String region;
        public final String city;
        public final String isp;
        public final String asn;
        public final String provider;
        /** True if the address belongs to a hosting provider, null if the service does not say. */
        public final Boolean hosting;

        private Result(String ip, String countryCode, String country, String region, String city,
                String isp, String asn, String provider, Boolean hosting) {
            this.ip = ip;
            this.countryCode = countryCode;
            this.country = country;
            this.region = region;
            this.city = city;
            this.isp = isp;
            this.asn = asn;
            this.provider = provider;
            this.hosting = hosting;
        }

        public boolean isIpv6() {
            return ip.indexOf(':') >= 0;
        }
    }

    private static final String IPWHO = "ipwho.is";
    private static final String IPAPI = "ipapi.co";

    private IpLookup() {
    }

    /** ip == null looks up the address the request comes from (the exit IP when the fetcher goes through a node). */
    public static Result lookup(Fetcher fetcher, String ip) throws IOException {
        if (ip != null && !ip.matches("[0-9A-Fa-f.:]{1,45}")) {
            throw new IllegalArgumentException("not an IP address");
        }
        try {
            return parseIpWho(fetcher.get(ip == null ? "https://ipwho.is/" : "https://ipwho.is/" + ip));
        } catch (IOException first) {
            // fall through to the second service
        }
        try {
            return parseIpApi(fetcher.get(ip == null ? "https://ipapi.co/json/" : "https://ipapi.co/" + ip + "/json/"));
        } catch (IOException second) {
            throw new IOException(IPAPI + ": " + second.getMessage());
        }
    }

    static Result parseIpWho(String json) throws IOException {
        Map<String, Object> m = object(json, IPWHO);
        if (Boolean.FALSE.equals(m.get("success"))) {
            String why = str(m.get("message"));
            throw new IOException(IPWHO + ": " + (why.isEmpty() ? "lookup failed" : why));
        }
        String ip = str(m.get("ip"));
        if (ip.isEmpty()) {
            throw new IOException(IPWHO + ": no address in the answer");
        }
        Map<String, Object> conn = sub(m, "connection");
        String isp = str(conn.get("isp"));
        if (isp.isEmpty()) {
            isp = str(conn.get("org"));
        }
        Object hosting = sub(m, "security").get("hosting");
        return new Result(ip, str(m.get("country_code")), str(m.get("country")), str(m.get("region")),
                str(m.get("city")), isp, asn(conn.get("asn")), IPWHO,
                hosting instanceof Boolean ? (Boolean) hosting : null);
    }

    static Result parseIpApi(String json) throws IOException {
        Map<String, Object> m = object(json, IPAPI);
        if (Boolean.TRUE.equals(m.get("error"))) {
            String why = str(m.get("reason"));
            throw new IOException(IPAPI + ": " + (why.isEmpty() ? "lookup failed" : why));
        }
        String ip = str(m.get("ip"));
        if (ip.isEmpty()) {
            throw new IOException(IPAPI + ": no address in the answer");
        }
        return new Result(ip, str(m.get("country_code")), str(m.get("country_name")), str(m.get("region")),
                str(m.get("city")), str(m.get("org")), str(m.get("asn")), IPAPI, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(String json, String provider) throws IOException {
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException(provider + ": answer is not JSON");
        }
        if (!(root instanceof Map)) {
            throw new IOException(provider + ": unexpected answer");
        }
        return (Map<String, Object>) root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String name) {
        Object o = m.get(name);
        return o instanceof Map ? (Map<String, Object>) o : Collections.<String, Object>emptyMap();
    }

    private static String str(Object o) {
        return o instanceof String ? ((String) o).trim() : "";
    }

    /** ipwho.is gives the AS number as a JSON number; show it as "AS15169". */
    private static String asn(Object o) {
        if (o instanceof Number) {
            return "AS" + ((Number) o).longValue();
        }
        String s = str(o);
        if (s.isEmpty() || s.regionMatches(true, 0, "AS", 0, 2)) {
            return s;
        }
        return "AS" + s;
    }
}
