package com.bridge.filt3r.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.loadingindicator.LoadingIndicator;

import java.util.Locale;

/**
 * The Status canvas: hero circle, context-aware buttons and three cards (node, traffic, DNS).
 * The activity pushes state with {@link #setState} and calls {@link #update} once a second.
 */
public class StatusView extends ScrollView {

    public enum State { STOPPED, STARTING, RUNNING, FAILED }

    /** What the screen shows; the activity fills it from the service. All fields may be null/-1. */
    public static final class Snapshot {
        public String nodeName;
        public long nodeMs = -1;
        public Boolean nodeIpv6;
        public String nodeIp;
        public String resolverName;
        public String resolverIp;
        public long[] trafficBytes;   // {up, down} since start
        public long[] dnsCounts;      // {blocked, allowed}
        public String filterText;     // filter-list status, may be several lines
    }

    public interface Actions {
        void connect();
        void disconnect();
        void restart();
    }

    private final HeroView hero;
    private final MaterialButton connect;
    private final MaterialButton disconnect;
    private final MaterialButton restart;
    private final LoadingIndicator loading;
    private final Card nodeCard;
    private final Card trafficCard;
    private final Card dnsCard;

    private long lastUp = -1;
    private long lastDown = -1;
    private long lastMs;

    public StatusView(Context context, Actions actions) {
        super(context);
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        // Start 5% down the screen so the pulsing circle has room; bottom leaves room for the toolbar.
        int top = (int) (getResources().getDisplayMetrics().heightPixels * 0.05f);
        column.setPadding(dp(16), top, dp(16), dp(112));
        column.setClipChildren(false);
        column.setClipToPadding(false);
        setClipChildren(false);

        hero = new HeroView(context);
        column.addView(hero, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout buttonSlot = new FrameLayout(context);
        // A plain row, not MaterialButtonGroup: the group squashed the Connect button to an empty stub after a disconnect.
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.HORIZONTAL);
        connect = button(context, "Connect", false);
        disconnect = button(context, "Disconnect", false);
        restart = button(context, "Restart", true);
        connect.setOnClickListener(v -> actions.connect());
        disconnect.setOnClickListener(v -> actions.disconnect());
        restart.setOnClickListener(v -> actions.restart());
        group.addView(connect);
        group.addView(disconnect);
        LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        restartParams.setMarginStart(dp(8));
        group.addView(restart, restartParams);
        buttonSlot.addView(group, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        loading = new LoadingIndicator(context);
        loading.setVisibility(GONE);
        buttonSlot.addView(loading, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        slotParams.topMargin = dp(24);
        column.addView(buttonSlot, slotParams);

        nodeCard = new Card(context, "Node");
        trafficCard = new Card(context, "Traffic");
        dnsCard = new Card(context, "DNS");
        for (Card c : new Card[] {nodeCard, trafficCard, dnsCard}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(12);
            column.addView(c.card, p);
        }
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setState(State.STOPPED);
    }

    public void setState(State state) {
        switch (state) {
            case RUNNING:
                hero.setMode(HeroView.Mode.ACTIVE);
                break;
            case STARTING:
                hero.setMode(HeroView.Mode.CONNECTING);
                break;
            case FAILED:
                hero.setMode(HeroView.Mode.FAILED);
                break;
            default:
                hero.setMode(HeroView.Mode.OFF);
                break;
        }
        boolean off = state == State.STOPPED || state == State.FAILED;
        connect.setVisibility(off ? VISIBLE : GONE);
        disconnect.setVisibility(state == State.RUNNING ? VISIBLE : GONE);
        restart.setVisibility(state == State.RUNNING ? VISIBLE : GONE);
        loading.setVisibility(state == State.STARTING ? VISIBLE : GONE);
        if (state != State.RUNNING) {
            lastUp = -1;
            lastDown = -1;
            update(new Snapshot());
        }
    }

    public void update(Snapshot s) {
        if (s.nodeName == null) {
            nodeCard.set("—", "", "");
        } else {
            String ms = s.nodeMs >= 0 ? s.nodeMs + " ms" : "not measured";
            String v6 = s.nodeIpv6 == null ? "IPv6 checking" : s.nodeIpv6 ? "IPv6 on" : "IPv6 off";
            nodeCard.set(s.nodeName, ms + "  ·  " + v6, s.nodeIp == null ? "" : "IP " + s.nodeIp);
        }

        if (s.trafficBytes == null) {
            trafficCard.set("—", "", "");
            lastUp = -1;
            lastDown = -1;
        } else {
            long now = System.currentTimeMillis();
            long up = s.trafficBytes[0];
            long down = s.trafficBytes[1];
            double secs = Math.max(0.001, (now - lastMs) / 1000.0);
            long upRate = lastUp < 0 ? 0 : (long) ((up - lastUp) / secs);
            long downRate = lastDown < 0 ? 0 : (long) ((down - lastDown) / secs);
            lastUp = up;
            lastDown = down;
            lastMs = now;
            trafficCard.set("↓ " + Format.bytes(downRate) + "/s   ↑ " + Format.bytes(upRate) + "/s",
                    "Session  ↓ " + Format.bytes(down) + "   ↑ " + Format.bytes(up), "");
        }

        if (s.dnsCounts == null) {
            dnsCard.set("—", "", "");
        } else {
            long blocked = s.dnsCounts[0];
            long total = blocked + s.dnsCounts[1];
            String pct = total == 0 ? "" : String.format(Locale.US, "  (%.0f%%)", 100.0 * blocked / total);
            String resolver = s.resolverName == null ? ""
                    : "Resolver " + s.resolverName + (s.resolverIp == null ? "" : "  ·  " + s.resolverIp);
            String lists = s.filterText == null ? "" : s.filterText;
            String more = resolver.isEmpty() || lists.isEmpty() ? resolver + lists : resolver + "\n" + lists;
            dnsCard.set("Blocked " + blocked + pct, "Allowed " + s.dnsCounts[1], more);
        }
    }

    private MaterialButton button(Context context, String text, boolean outlined) {
        MaterialButton b = outlined
                ? new MaterialButton(context, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle)
                : new MaterialButton(context);
        b.setText(text);
        return b;
    }

    private static void dim(TextView t) {
        t.setTextColor(MaterialColors.getColor(t.getContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** One rounded card: a small title, a large value and a detail line. */
    private final class Card {
        final MaterialCardView card;
        private final TextView value;
        private final TextView detail;
        private final TextView extra;

        Card(Context context, String title) {
            card = new MaterialCardView(context);
            card.setCardElevation(0f);
            card.setRadius(dp(24));
            card.setCardBackgroundColor(MaterialColors.getColor(card,
                    com.google.android.material.R.attr.colorSurfaceContainer));
            card.setStrokeWidth(0);
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(16), dp(20), dp(16));
            TextView label = new TextView(context);
            label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
            label.setText(title);
            dim(label);
            value = new TextView(context);
            value.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
            detail = new TextView(context);
            detail.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            dim(detail);
            extra = new TextView(context);
            extra.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            dim(extra);
            box.addView(label);
            box.addView(value);
            box.addView(detail);
            box.addView(extra);
            card.addView(box);
        }

        void set(String main, String sub, String more) {
            value.setText(main);
            detail.setText(sub);
            detail.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
            extra.setText(more);
            extra.setVisibility(more.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }
}
