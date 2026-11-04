package com.bridge.filt3r.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.bridge.filt3r.R;
import com.bridge.filt3r.core.nodes.NodeInfo;
import com.bridge.filt3r.core.nodes.NodeLocation;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen node list: the current node on top, then every node with status, place, latency,
 * IPv6 and speed. Tap a node to pin it (tap the pinned one to unpin); long-press shows its address.
 */
public class NodesPanel extends LinearLayout {

    /** What the panel needs from the app; implemented by the activity next to the service. */
    public interface Backend {
        /** Every node, best first; empty while the tunnel is down. */
        List<NodeInfo> nodes();

        /** Starts a re-test; {@code done} runs on a background thread. @return false if not possible now */
        boolean retest(Runnable done);

        /** Pins the node (testing it first if needed); {@code done} runs on a background thread. */
        boolean pin(ShadowsocksNode node, Runnable done);

        void unpin();

        boolean sortBySpeed();

        void setSortBySpeed(boolean bySpeed);

        /** The subscription slot (0-2) the node came from, or -1 if unknown. */
        int source(ShadowsocksNode node);

        /** How many subscriptions supply nodes; the tag is shown only when this is above 1. */
        int sourceCount();

        /** "Nodes tested 4 h ago", or a line saying they were not tested yet. */
        String lastTestText();
    }

    private static final long REFRESH_MS = 2000;

    private final Backend backend;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NodeAdapter adapter = new NodeAdapter();
    private final TextView currentName;
    private final TextView currentDetail;
    private final TextView currentIp;
    private final TextView currentTested;
    private final TextView empty;
    private final ImageButton retestButton;
    private final ListView list;
    private boolean busy;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            reload();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    public NodesPanel(Context context, Backend backend, Runnable onClose) {
        super(context);
        this.backend = backend;
        setOrientation(VERTICAL);
        setBackgroundColor(MaterialColors.getColor(this, android.R.attr.colorBackground));
        setClickable(true); // swallow touches so the screen underneath stays still

        // Title row: back, title, re-test.
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(8), dp(8), dp(8));
        ImageButton back = iconButton(context, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> onClose.run());
        TextView title = new TextView(context);
        title.setText("Nodes");
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        retestButton = iconButton(context, R.drawable.ic_refresh, "Re-test nodes");
        retestButton.setOnClickListener(v -> retest());
        bar.addView(back);
        bar.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(retestButton);
        addView(bar);

        // Current node card.
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceContainer));
        cardBg.setCornerRadius(dp(24));
        card.setBackground(cardBg);
        TextView label = new TextView(context);
        label.setText("Current node");
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        label.setAlpha(0.7f);
        currentName = new TextView(context);
        currentName.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        currentDetail = new TextView(context);
        currentDetail.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        currentDetail.setAlpha(0.7f);
        currentIp = new TextView(context);
        currentIp.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        currentIp.setAlpha(0.7f);
        currentTested = new TextView(context);
        currentTested.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        currentTested.setAlpha(0.7f);
        card.addView(label);
        card.addView(currentName);
        card.addView(currentDetail);
        card.addView(currentIp);
        card.addView(currentTested);
        LayoutParams cardParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(dp(16), dp(4), dp(16), dp(12));
        addView(card, cardParams);

        // Sort switch.
        MaterialButtonToggleGroup sort = new MaterialButtonToggleGroup(context);
        sort.setSingleSelection(true);
        sort.setSelectionRequired(true);
        sort.setSpacing(dp(8));
        MaterialButton byLatency = sortButton(context, "Latency");
        MaterialButton bySpeed = sortButton(context, "Speed");
        sort.addView(byLatency);
        sort.addView(bySpeed);
        sort.check(backend.sortBySpeed() ? bySpeed.getId() : byLatency.getId());
        sort.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) {
                backend.setSortBySpeed(id == bySpeed.getId());
                reload();
            }
        });
        LayoutParams sortParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        sortParams.setMargins(dp(16), 0, dp(16), dp(8));
        addView(sort, sortParams);

        empty = new TextView(context);
        empty.setText("Connect to see the nodes.");
        empty.setGravity(Gravity.CENTER);
        empty.setAlpha(0.7f);
        empty.setPadding(dp(16), dp(48), dp(16), dp(16));
        addView(empty);

        list = new ListView(context);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(96)); // the toolbar overlays the bottom
        list.setOnItemClickListener((p, v, pos, id) -> onNodeTap(adapter.getItem(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            ShadowsocksNode n = adapter.getItem(pos).node;
            Toast.makeText(getContext(), n.host + ":" + n.port, Toast.LENGTH_LONG).show();
            return true;
        });
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void onNodeTap(NodeInfo n) {
        if (n.state == NodeInfo.State.BLACKLISTED) {
            Toast.makeText(getContext(), "This node failed 3 times in a row; it is skipped for a while and tried again automatically.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (n.pinned) {
            backend.unpin();
            reload();
            return;
        }
        if (n.state == NodeInfo.State.UNTESTED) {
            Toast.makeText(getContext(), "Testing the node first...", Toast.LENGTH_SHORT).show();
        }
        if (!backend.pin(n.node, () -> post(this::reload))) {
            Toast.makeText(getContext(), "Connect first.", Toast.LENGTH_SHORT).show();
        }
    }

    private void retest() {
        if (busy) {
            return;
        }
        busy = true;
        retestButton.setEnabled(false);
        retestButton.setAlpha(0.4f);
        Toast.makeText(getContext(), "Re-testing and speed-testing the best nodes...", Toast.LENGTH_SHORT).show();
        boolean started = backend.retest(() -> post(() -> {
            busy = false;
            retestButton.setEnabled(true);
            retestButton.setAlpha(1f);
            reload();
        }));
        if (!started) {
            busy = false;
            retestButton.setEnabled(true);
            retestButton.setAlpha(1f);
            Toast.makeText(getContext(), "Not possible now (tunnel off or already testing).",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void reload() {
        List<NodeInfo> all = new ArrayList<>(backend.nodes());
        if (all.isEmpty() && busy) {
            // The tunnel stopped while a re-test was queued: its callback will never run.
            busy = false;
            retestButton.setEnabled(true);
            retestButton.setAlpha(1f);
        }
        NodeInfo current = null;
        for (NodeInfo n : all) {
            if (n.current) {
                current = n;
            }
        }
        if (backend.sortBySpeed()) {
            // Stable: nodes with the same speed (or none) keep their latency order; state groups stay.
            all.sort((a, b) -> {
                int byState = a.state.compareTo(b.state);
                return byState != 0 ? byState : Long.compare(b.speedBps, a.speedBps);
            });
        }
        if (current != null) {
            all.remove(current);
            all.add(0, current); // the node in use is always the first row
        }
        adapter.set(all);
        empty.setVisibility(all.isEmpty() ? VISIBLE : GONE);
        list.setVisibility(all.isEmpty() ? GONE : VISIBLE);
        if (current == null) {
            currentName.setText("—");
            currentDetail.setText("");
            currentDetail.setVisibility(GONE);
            currentIp.setVisibility(GONE);
        } else {
            NodeLocation loc = NodeLocation.parse(current.node.name);
            currentName.setText(place(loc));
            currentDetail.setText(details(current));
            currentDetail.setVisibility(VISIBLE);
            currentIp.setText("IP  " + current.node.host);
            currentIp.setVisibility(VISIBLE);
        }
        currentTested.setText(backend.lastTestText());
        currentTested.setVisibility(all.isEmpty() ? GONE : VISIBLE);
    }

    private static String place(NodeLocation loc) {
        return loc.flag.isEmpty() ? loc.city : loc.flag + " " + loc.city;
    }

    private static String details(NodeInfo n) {
        StringBuilder sb = new StringBuilder();
        if (n.medianMs >= 0) {
            sb.append(n.medianMs).append(" ms");
        } else {
            sb.append("not measured");
        }
        if (n.speedBps >= 0) {
            sb.append("  ·  ").append(Format.bytes(n.speedBps)).append("/s");
        }
        sb.append("  ·  ").append(n.ipv6 == null ? "IPv6 checking" : n.ipv6 ? "IPv6 on" : "IPv6 off");
        if (n.pinned) {
            sb.append("  ·  pinned");
        }
        return sb.toString();
    }

    private MaterialButton sortButton(Context context, String text) {
        MaterialButton b = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setId(View.generateViewId());
        b.setCheckable(true);
        return b;
    }

    private ImageButton iconButton(Context context, int res, String description) {
        ImageButton b = new ImageButton(context);
        b.setImageResource(res);
        b.setContentDescription(description);
        b.setBackground(null);
        b.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface)));
        b.setLayoutParams(new LayoutParams(dp(48), dp(48)));
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class NodeAdapter extends BaseAdapter {
        private List<NodeInfo> items = new ArrayList<>();

        void set(List<NodeInfo> next) {
            items = next;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public NodeInfo getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Row row = convert == null ? new Row(parent.getContext()) : (Row) convert;
            row.bind(items.get(position), backend.sortBySpeed());
            return row;
        }
    }

    /** One node row: status dot, place and detail lines, latency and IPv6 on the right. */
    private final class Row extends LinearLayout {
        private final View dot;
        private final TextView title;
        private final TextView subtitle;
        private final TextView latency;
        private final TextView ipv6;
        private final ImageView pin;
        private final GradientDrawable dotBg = new GradientDrawable();
        private final TextView tag;
        private final GradientDrawable tagBg = new GradientDrawable();

        Row(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(20), dp(12), dp(20), dp(12));

            dotBg.setShape(GradientDrawable.OVAL);
            dot = new View(context);
            dot.setBackground(dotBg);
            LayoutParams dotParams = new LayoutParams(dp(10), dp(10));
            dotParams.rightMargin = dp(16);
            addView(dot, dotParams);

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            title = new TextView(context);
            title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            subtitle = new TextView(context);
            subtitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            subtitle.setAlpha(0.7f);
            // The tag sits on the same line as the flag and country.
            tag = new TextView(context);
            tag.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
            tag.setSingleLine(true);
            tag.setPadding(dp(8), dp(1), dp(8), dp(1));
            tag.setBackground(tagBg);
            tagBg.setCornerRadius(dp(10));
            LinearLayout subRow = new LinearLayout(context);
            subRow.setGravity(Gravity.CENTER_VERTICAL);
            subRow.addView(subtitle, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            LayoutParams tagParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            tagParams.leftMargin = dp(8);
            subRow.addView(tag, tagParams);
            texts.addView(title);
            texts.addView(subRow);
            addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            pin = new ImageView(context);
            pin.setImageResource(R.drawable.ic_pin);
            pin.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.brand_red)));
            LayoutParams pinParams = new LayoutParams(dp(18), dp(18));
            pinParams.rightMargin = dp(12);
            addView(pin, pinParams);

            LinearLayout right = new LinearLayout(context);
            right.setOrientation(VERTICAL);
            right.setGravity(Gravity.END);
            latency = new TextView(context);
            latency.setGravity(Gravity.END);
            latency.setSingleLine(true);
            latency.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            ipv6 = new TextView(context);
            ipv6.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
            ipv6.setText("IPv6");
            ipv6.setTextColor(context.getColor(R.color.state_active));
            right.addView(latency);
            right.addView(ipv6);
            addView(right, new LayoutParams(dp(84), LayoutParams.WRAP_CONTENT)); // fixed width: columns line up
        }

        void bind(NodeInfo n, boolean bySpeed) {
            NodeLocation loc = NodeLocation.parse(n.node.name);
            title.setText(loc.city);
            // The right column shows the metric the list is sorted by; the other one goes under the place.
            String ms = n.medianMs >= 0 ? n.medianMs + " ms" : "—";
            String speed = n.speedBps >= 0 ? Format.bytes(n.speedBps) + "/s" : "—";
            StringBuilder sub = new StringBuilder();
            if (!loc.countryCode.isEmpty()) {
                sub.append(loc.flag).append(' ').append(loc.countryCode).append("  ·  ");
            }
            sub.append(bySpeed ? ms : speed);
            int source = backend.sourceCount() > 1 ? backend.source(n.node) : -1;
            if (source >= 0) {
                sub.append("  ·  Sub ").append(source + 1);
            }
            subtitle.setText(sub.toString());
            latency.setText(bySpeed ? speed : ms);
            ipv6.setVisibility(Boolean.TRUE.equals(n.ipv6) ? VISIBLE : GONE);
            pin.setVisibility(n.pinned ? VISIBLE : GONE);

            int color;
            switch (n.state) {
                case WORKING:
                    color = getContext().getColor(R.color.state_active);
                    break;
                case BLACKLISTED:
                    color = getContext().getColor(R.color.brand_red);
                    break;
                default:
                    color = getContext().getColor(R.color.state_inactive);
                    break;
            }
            dotBg.setColor(color);
            // The tag says why a row has no green dot (or that it is the node in use).
            String tagText = null;
            int tagColor = color;
            if (n.current) {
                tagText = "in use";
            } else if (n.state == NodeInfo.State.BLACKLISTED) {
                tagText = "dead";
            } else if (n.state == NodeInfo.State.UNTESTED) {
                tagText = n.failures > 0 ? "no answer" : "not tested";
                tagColor = MaterialColors.getColor(this,
                        com.google.android.material.R.attr.colorOnSurfaceVariant);
            }
            if (tagText == null) {
                tag.setVisibility(GONE);
            } else {
                tag.setText(tagText);
                tag.setTextColor(tagColor);
                tagBg.setColor((tagColor & 0x00FFFFFF) | 0x26000000);
                tag.setVisibility(VISIBLE);
            }
            setAlpha(n.state == NodeInfo.State.BLACKLISTED ? 0.5f : 1f);
            setBackgroundColor(n.current
                    ? (color & 0x00FFFFFF) | 0x1A000000
                    : 0);
        }
    }
}
