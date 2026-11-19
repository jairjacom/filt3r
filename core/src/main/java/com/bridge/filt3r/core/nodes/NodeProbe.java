package com.bridge.filt3r.core.nodes;

import java.io.IOException;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/** Checks whether a node itself works, independent of the user's chosen DNS resolver. */
public interface NodeProbe {

    boolean works(ShadowsocksNode node);

    /**
     * @return ms until the answer arrived
     * @throws IOException if the node does not work
     */
    long test(ShadowsocksNode node) throws IOException;
}
