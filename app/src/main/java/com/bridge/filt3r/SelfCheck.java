package com.bridge.filt3r;

import android.content.Context;

import com.bridge.filt3r.core.nodes.NodeInfo;
import com.bridge.filt3r.core.filter.FilterStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A checklist of what the tunnel needs, read from the running service. It contacts nobody outside the
 * tunnel: the one network step is the DNS test, which goes through the node like every other query.
 */
final class SelfCheck {

    private static final String OK = "✓  ";
    private static final String BAD = "✗  ";
    private static final String NOTE = "–  ";

    private SelfCheck() {
    }

    /** One line per check: a mark, what was checked and the result. Blocks (the DNS test); call it off the main thread. */
    static List<String> run(Context ctx, Settings settings) {
        List<String> lines = new ArrayList<>();
        if (TunnelState.get() != TunnelState.State.RUNNING) {
            lines.add(BAD + ctx.getString(R.string.selfcheck_vpn_off));
            return lines;
        }
        lines.add(OK + ctx.getString(R.string.selfcheck_vpn_on));

        NodeInfo node = Filt3rVpnService.currentNode();
        if (node == null) {
            lines.add(BAD + ctx.getString(R.string.selfcheck_node_none));
        } else if (node.medianMs >= 0) {
            lines.add(OK + ctx.getString(R.string.selfcheck_node_ok, node.node.name, node.medianMs));
        } else {
            lines.add(NOTE + ctx.getString(R.string.selfcheck_node_untimed, node.node.name));
        }

        String[] resolver = Filt3rVpnService.resolver();
        String resolverName = resolver == null ? ctx.getString(R.string.selfcheck_unknown) : resolver[0];
        try {
            String answer = Filt3rVpnService.checkResolver(ctx, settings.resolverStamp());
            lines.add(OK + ctx.getString(R.string.selfcheck_dns_ok, resolverName, answer));
        } catch (IOException e) {
            lines.add(BAD + ctx.getString(R.string.selfcheck_dns_fail, resolverName, e.getMessage()));
        }

        if (node != null) {
            lines.add((node.ipv6 == null ? NOTE : OK) + ctx.getString(node.ipv6 == null
                    ? R.string.selfcheck_ipv6_unknown
                    : node.ipv6 ? R.string.selfcheck_ipv6_on : R.string.selfcheck_ipv6_off));
        }

        String lists = FilterStatusText.describe(ctx, FilterStatus.get(), true, System.currentTimeMillis(), true);
        lines.add(NOTE + ctx.getString(R.string.selfcheck_lists, lists));
        String ip = Filt3rVpnService.ipBlockStatus();
        if (!ip.isEmpty()) {
            lines.add(NOTE + ctx.getString(R.string.selfcheck_ip_list, ip));
        }

        if (TunnelState.pausedUntil() != 0) {
            lines.add(BAD + ctx.getString(R.string.selfcheck_paused));
        }
        return lines;
    }
}
