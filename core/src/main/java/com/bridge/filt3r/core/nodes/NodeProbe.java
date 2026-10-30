package com.bridge.filt3r.core.nodes;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/** Checks whether a node itself works, independent of the user's chosen DNS resolver. */
public interface NodeProbe {

    boolean works(ShadowsocksNode node);
}
