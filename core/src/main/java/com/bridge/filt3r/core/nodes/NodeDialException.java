package com.bridge.filt3r.core.nodes;

import java.io.IOException;

/** The connection to the node itself failed; the failure is already counted against that node. */
final class NodeDialException extends IOException {

    NodeDialException(String message, Throwable cause) {
        super(message, cause);
    }
}
