package com.bridge.filt3r.core.dns;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

/** Turns a host name into addresses. */
public interface NameResolver {

    /** @return at least one address; @throws IOException if the name does not resolve */
    List<InetAddress> resolve(String host) throws IOException;
}
