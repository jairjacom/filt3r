package com.bridge.filt3r;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.Network;
import android.os.CancellationSignal;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Answers a DNS query with the phone's own resolver on the real network, unfiltered and not through a node.
 * Only an Exempt app that has a firewall rule uses it (ADR 0006): the app is in the tunnel so the rule can reach
 * it, but it keeps its real DNS. This app is outside the tunnel, so the active network seen here is the real one.
 */
final class SystemDns {

    private static final long TIMEOUT_S = 5;

    private final Context context;

    SystemDns(Context context) {
        this.context = context;
    }

    /** @return the response message for one query message */
    byte[] resolve(byte[] query) throws IOException {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        Network network = cm == null ? null : cm.getActiveNetwork();
        if (network == null) {
            throw new IOException("no network");
        }
        CompletableFuture<byte[]> answer = new CompletableFuture<>();
        CancellationSignal cancel = new CancellationSignal();
        DnsResolver.getInstance().rawQuery(network, query, DnsResolver.FLAG_EMPTY, Runnable::run, cancel,
                new DnsResolver.Callback<byte[]>() {
                    @Override
                    public void onAnswer(byte[] response, int rcode) {
                        answer.complete(response);
                    }

                    @Override
                    public void onError(DnsResolver.DnsException error) {
                        answer.completeExceptionally(error);
                    }
                });
        try {
            return answer.get(TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            cancel.cancel();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("the system resolver failed", e.getCause());
        } catch (TimeoutException e) {
            cancel.cancel();
            throw new IOException("the system resolver timed out", e);
        }
    }
}
