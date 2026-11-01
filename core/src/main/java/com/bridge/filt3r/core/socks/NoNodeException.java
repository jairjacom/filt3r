package com.bridge.filt3r.core.socks;

import java.io.IOException;

/** No node is usable at all. The only failure that "connect directly" mode may answer by going direct. */
public final class NoNodeException extends IOException {

    public NoNodeException() {
        super("no working node");
    }
}
