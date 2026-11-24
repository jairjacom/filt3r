package com.bridge.filt3r.core.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The Shadowsocks SIP008 "online configuration": a JSON object whose "servers" lists the nodes. */
class SubscriptionParserSip008Test {

    private static final String DOC = "{\"version\":1,\"servers\":["
            + "{\"id\":\"a\",\"remarks\":\"Home\",\"server\":\"203.0.113.5\",\"server_port\":8388,"
            + "\"password\":\"pw1\",\"method\":\"chacha20-ietf-poly1305\"},"
            + "{\"id\":\"b\",\"remarks\":\"Plugin\",\"server\":\"203.0.113.6\",\"server_port\":443,"
            + "\"password\":\"pw2\",\"method\":\"chacha20-ietf-poly1305\",\"plugin\":\"v2ray-plugin\",\"plugin_opts\":\"tls\"},"
            + "{\"id\":\"c\",\"server\":\"203.0.113.5\",\"server_port\":8388,"
            + "\"password\":\"pw1\",\"method\":\"chacha20-ietf-poly1305\"}"
            + "],\"bytes_used\":1,\"bytes_remaining\":2}";

    @Test
    void readsNodesFromTheServersList() {
        SubscriptionParser.Result r = SubscriptionParser.parse(DOC);
        assertEquals(1, r.nodes.size()); // the duplicate is dropped
        assertEquals("203.0.113.5", r.nodes.get(0).host);
        assertEquals(8388, r.nodes.get(0).port);
        assertEquals(1, r.skipped); // the plugin entry
    }

    @Test
    void anObjectWithoutServersIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SubscriptionParser.parse("{\"version\":1}"));
    }
}
