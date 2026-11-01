package com.bridge.filt3r.core.subscription;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import com.bridge.filt3r.core.dns.NameResolver;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.util.IpLiteral;

/** Replaces node host names with an IPv4 address (nodes are dialled by IP). Nodes that do not resolve are dropped. */
public final class HostNameResolver {

    private HostNameResolver() {
    }

    public static List<ShadowsocksNode> resolve(List<ShadowsocksNode> nodes, NameResolver resolver) {
        List<ShadowsocksNode> out = new ArrayList<>(nodes.size());
        for (ShadowsocksNode n : nodes) {
            if (IpLiteral.parse(n.host) != null) {
                out.add(n);
                continue;
            }
            try {
                for (InetAddress a : resolver.resolve(n.host)) {
                    if (a.getAddress().length == 4) {
                        out.add(ShadowsocksNode.of(n.suite.methodName, n.password, a.getHostAddress(), n.port, n.name));
                        break;
                    }
                }
            } catch (IOException | IllegalArgumentException e) {
                // skipped: this node cannot be reached by name
            }
        }
        return out;
    }
}
