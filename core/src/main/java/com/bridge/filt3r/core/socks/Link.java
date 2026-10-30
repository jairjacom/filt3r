package com.bridge.filt3r.core.socks;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** One bidirectional byte connection: a plain socket, or one carried through a proxy node. */
public interface Link extends Closeable {

    InputStream in() throws IOException;

    OutputStream out() throws IOException;

    /** Signals end of data to the peer but keeps the receiving direction open. */
    void shutdownOutput() throws IOException;
}
