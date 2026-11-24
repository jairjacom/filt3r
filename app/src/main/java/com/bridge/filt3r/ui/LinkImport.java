package com.bridge.filt3r.ui;

import android.content.Context;
import android.view.View;

import com.bridge.filt3r.R;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.util.IpLiteral;
import com.google.android.material.snackbar.Snackbar;

/**
 * Adds a node or a subscription from a link the user pasted or shared to the app. Every path ends in a
 * confirm dialog: nothing is added unasked, and a subscription address (often a secret token) is never shown,
 * only its host.
 */
public final class LinkImport {

    private LinkImport() {
    }

    /**
     * The first ss:// link in the text, else the first https:// one, else null. A node link wins because a
     * chat post often has a channel link before it. Quotes, brackets and sentence punctuation around the
     * link are cut off.
     */
    static String firstLink(CharSequence text) {
        if (text == null) {
            return null;
        }
        String s = text.toString();
        String link = linkAfter(s, "ss://");
        return link != null ? link : linkAfter(s, "https://");
    }

    private static String linkAfter(String text, String scheme) {
        int start = text.toLowerCase(java.util.Locale.ROOT).indexOf(scheme);
        if (start < 0) {
            return null;
        }
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        while (end > start + scheme.length() && ".,;:!?)]}>\"'".indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return end > start + scheme.length() ? text.substring(start, end) : null;
    }

    /**
     * Shared text: a node link or a subscription address.
     *
     * @param afterNodeAdded runs after a node was saved, for example to refresh the Nodes list
     */
    public static void fromText(Context context, View anchor, CharSequence text, NodesPanel.Backend nodes,
            SettingsPanel.Backend settings, Runnable afterNodeAdded) {
        String link = firstLink(text);
        if (link == null) {
            Messages.show(anchor, context.getString(R.string.link_import_none));
        } else if (link.regionMatches(true, 0, "ss://", 0, 5)) {
            addNode(context, anchor, link, nodes, afterNodeAdded);
        } else {
            addSubscription(context, anchor, link, settings);
        }
    }

    /** Checks a node link and asks before adding it. */
    static void addNode(Context context, View anchor, String link, NodesPanel.Backend backend,
            Runnable afterAdded) {
        ShadowsocksNode node;
        try {
            node = ShadowsocksNode.parse(link);
        } catch (IllegalArgumentException e) {
            Messages.show(anchor, context.getString(R.string.nodes_add_bad, e.getMessage()));
            return;
        }
        if (IpLiteral.parse(node.host) == null) {
            Messages.show(anchor, context.getString(R.string.nodes_add_hostname));
            return;
        }
        Dialogs.confirm(context, context.getString(R.string.nodes_add_title),
                context.getString(R.string.nodes_add_message, node.host, node.port, node.suite.methodName),
                context.getString(R.string.nodes_add_action), () -> {
                    int result = backend.addManualNode(node);
                    Messages.show(anchor, context.getString(result == NodesPanel.Backend.ADD_OK ? R.string.nodes_added
                            : result == NodesPanel.Backend.ADD_DUPLICATE ? R.string.nodes_add_duplicate
                            : R.string.nodes_add_failed), Snackbar.LENGTH_SHORT);
                    afterAdded.run();
                });
    }

    /** Puts the address in the first free subscription slot, after the user agrees. */
    private static void addSubscription(Context context, View anchor, String url, SettingsPanel.Backend backend) {
        String host;
        try {
            // java.net.URI, like the download, so the host shown is the host contacted
            java.net.URI uri = new java.net.URI(url);
            host = uri.getHost();
            if (host != null && uri.getPort() != -1) {
                host += ":" + uri.getPort();
            }
        } catch (java.net.URISyntaxException e) {
            host = null;
        }
        if (host == null || host.isEmpty()) {
            Messages.show(anchor, context.getString(R.string.settings_subscription_url_invalid));
            return;
        }
        String shownHost = host;
        int free = -1;
        for (int slot = 0; slot < backend.subscriptionSlots(); slot++) {
            String existing = backend.subscriptionUrl(slot);
            if (existing.equals(url)) {
                Messages.show(anchor, context.getString(R.string.link_import_sub_duplicate));
                return;
            }
            if (free < 0 && existing.isEmpty()) {
                free = slot;
            }
        }
        if (free < 0) {
            Messages.show(anchor, context.getString(R.string.link_import_sub_full));
            return;
        }
        int slot = free;
        Dialogs.confirm(context, context.getString(R.string.link_import_sub_title),
                context.getString(R.string.link_import_sub_message, shownHost, slot + 1),
                context.getString(R.string.nodes_add_action), () -> {
                    String error = backend.setSubscription(slot, url, backend.subscriptionHours(slot));
                    if (error == null) {
                        backend.setSubscriptionEnabled(slot, true); // a cleared slot can still be switched off
                        backend.setRestartPending(backend.running()); // a new subscription joins at the next start
                    }
                    Messages.show(anchor, error != null ? error
                            : context.getString(R.string.link_import_sub_added, slot + 1), Snackbar.LENGTH_LONG);
                });
    }
}
