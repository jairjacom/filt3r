package com.bridge.filt3r.core.dns;

import java.io.IOException;

/** The resolver itself could not be reached or did not answer over HTTP (not: it answered with a DNS error). */
public final class ResolverUnreachableException extends IOException {

    public ResolverUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
