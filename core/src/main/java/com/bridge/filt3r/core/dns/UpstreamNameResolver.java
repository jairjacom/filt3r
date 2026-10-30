package com.bridge.filt3r.core.dns;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import com.bridge.filt3r.core.filter.DNSResolver;

/** Looks up IPv4 addresses through a DNS upstream (the DNSCrypt client, so the lookup goes through the node). */
public final class UpstreamNameResolver implements NameResolver {

    private final DNSResolver.Upstream upstream;

    public UpstreamNameResolver(DNSResolver.Upstream upstream) {
        this.upstream = upstream;
    }

    @Override
    public List<InetAddress> resolve(String host) throws IOException {
        List<InetAddress> out = new ArrayList<>();
        for (byte[] a : DnsWire.answers(upstream.query(DnsWire.query(host, DnsWire.TYPE_A)), DnsWire.TYPE_A)) {
            out.add(InetAddress.getByAddress(a));
        }
        if (out.isEmpty()) {
            throw new IOException("no address for host");
        }
        return out;
    }
}
