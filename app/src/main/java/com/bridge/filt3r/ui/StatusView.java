package com.bridge.filt3r.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.google.android.material.button.MaterialButton;
import com.bridge.filt3r.Effects;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.loadingindicator.LoadingIndicator;

import com.bridge.filt3r.R;
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
        public float[] graphDown;     // bytes per second, oldest first
        public float[] graphUp;
        public long peakBps;          // fastest combined speed since the start or the last reset
        public long sessionStartMs;   // wall clock; 0 if unknown
        public int nodeConnections = -1; // times this node carried traffic in 7 days; -1 while the node history is off
        public long nodeTotalMs;      // time on this node in 7 days
        public int nodeFailovers;     // times in 7 days a node took over because another died
    }

    public interface Actions {
        void connect();
        void disconnect();
        void restart();
        /** Node card tap: the IP lookup. */
        void openNodeLookup();
        /** Traffic card tap: the expanded graph. */
        void openTrafficDetails();
        /** Traffic card long-press: start the session counters again. */
        void resetCounters();
        /** DNS card tap: Settings, DNS and filtering. */
        void openDnsSettings();
        /** DNS card long-press: the most blocked names. */
        void openDnsDetails();
    }

    private final HeroView hero;
    private final MaterialButton connect;
    private final MaterialButton disconnect;
    private final MaterialButton restart;
    private final LoadingIndicator loading;
    private final Card nodeCard;
    private final Card trafficCard;
    private final Card dnsCard;
    private final TrafficGraph graph;

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
        connect = button(context, context.getString(R.string.status_connect), false);
        disconnect = button(context, context.getString(R.string.status_disconnect), false);
        restart = button(context, context.getString(R.string.status_restart), true);
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

        nodeCard = new Card(context, context.getString(R.string.status_card_node), true);
        trafficCard = new Card(context, context.getString(R.string.status_card_traffic), false);
        dnsCard = new Card(context, context.getString(R.string.status_card_dns), true);
        nodeCard.onTap(actions::openNodeLookup, null);
        trafficCard.onTap(actions::openTrafficDetails, actions::resetCounters);
        dnsCard.onTap(actions::openDnsSettings, actions::openDnsDetails);
        for (Card c : new Card[] {nodeCard, trafficCard, dnsCard}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(12);
            column.addView(c.card, p);
        }
        graph = new TrafficGraph(context, false);
        trafficCard.box.addView(graph, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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

    /** Centre of the status shield on screen; false until the screen is laid out. For the splash hand-over. */
    public boolean heroShieldCenter(int[] out) {
        return hero.shieldCenter(out);
    }

    public int heroShieldSize() {
        return hero.shieldSize();
    }

    public void update(Snapshot s) {
        Context ctx = getContext();
        if (s.nodeName == null) {
            nodeCard.set("—", "", "");
        } else {
            String ms = s.nodeMs >= 0 ? ctx.getString(R.string.status_node_ms, s.nodeMs)
                    : ctx.getString(R.string.status_node_not_measured);
            String v6 = ctx.getString(s.nodeIpv6 == null ? R.string.status_node_ipv6_checking
                    : s.nodeIpv6 ? R.string.status_node_ipv6_on : R.string.status_node_ipv6_off);
            StringBuilder more = new StringBuilder();
            if (s.nodeIp != null) {
                more.append(ctx.getString(R.string.status_node_ip, s.nodeIp));
            }
            if (s.nodeConnections >= 0) {
                if (more.length() > 0) {
                    more.append('\n');
                }
                more.append(ctx.getResources().getQuantityString(R.plurals.status_node_history, s.nodeConnections,
                        s.nodeConnections, Format.duration(ctx, s.nodeTotalMs)));
                if (s.nodeFailovers > 0) {
                    more.append('\n').append(ctx.getResources().getQuantityString(R.plurals.status_node_failovers,
                            s.nodeFailovers, s.nodeFailovers));
                }
            }
            nodeCard.set(s.nodeName, ctx.getString(R.string.status_node_detail, ms, v6), more.toString());
        }

        boolean graphOn = s.trafficBytes != null && Effects.on(ctx, Effects.Effect.SPARKLINE);
        graph.setVisibility(graphOn ? VISIBLE : GONE);
        if (s.trafficBytes == null) {
            trafficCard.set("—", "", "");
            lastUp = -1;
            lastDown = -1;
            graph.setData(new float[0], new float[0]);
        } else {
            long now = System.currentTimeMillis();
            long up = s.trafficBytes[0];
            long down = s.trafficBytes[1];
            double secs = Math.max(0.001, (now - lastMs) / 1000.0);
            long upRate = lastUp < 0 || up < lastUp ? 0 : (long) ((up - lastUp) / secs);
            long downRate = lastDown < 0 || down < lastDown ? 0 : (long) ((down - lastDown) / secs);
            lastUp = up;
            lastDown = down;
            lastMs = now;
            String facts = s.sessionStartMs > 0
                    ? ctx.getString(R.string.status_traffic_facts, Format.bytes(s.peakBps),
                            Format.duration(ctx, Math.max(0, now - s.sessionStartMs)))
                    : "";
            trafficCard.set(ctx.getString(R.string.status_traffic_rate, Format.bytes(downRate), Format.bytes(upRate)),
                    ctx.getString(R.string.status_traffic_session, Format.bytes(down), Format.bytes(up)), facts);
            if (graphOn && s.graphDown != null && s.graphUp != null) {
                graph.setData(s.graphDown, s.graphUp);
            }
        }

        if (s.dnsCounts == null) {
            dnsCard.set("—", "", "");
        } else {
            long blocked = s.dnsCounts[0];
            long total = blocked + s.dnsCounts[1];
            String pct = total == 0 ? "" : ctx.getString(R.string.status_dns_percent, 100.0 * blocked / total);
            String resolver = s.resolverName == null ? ""
                    : s.resolverIp == null ? ctx.getString(R.string.status_dns_resolver, s.resolverName)
                    : ctx.getString(R.string.status_dns_resolver_ip, s.resolverName, s.resolverIp);
            String lists = s.filterText == null ? "" : s.filterText;
            String more = resolver.isEmpty() || lists.isEmpty() ? resolver + lists : resolver + "\n" + lists;
            dnsCard.setCount(blocked, n -> ctx.getString(R.string.status_dns_blocked, n) + pct,
                    ctx.getString(R.string.status_dns_allowed, s.dnsCounts[1]), more);
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
        private final LinearLayout box;
        private final boolean pulses;
        private SpringAnimation springX;
        private SpringAnimation springY;
        private String lastMain;

        Card(Context context, String title, boolean pulses) {
            this.pulses = pulses;
            card = new MaterialCardView(context);
            card.setCardElevation(0f);
            card.setRadius(dp(24));
            card.setCardBackgroundColor(MaterialColors.getColor(card,
                    com.google.android.material.R.attr.colorSurfaceContainer));
            card.setStrokeWidth(0);
            box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(16), dp(20), dp(16));
            TextView label = new TextView(context);
            label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
            label.setText(title);
            dim(label);
            value = new TextView(context);
            value.setTextAppearance(Effects.on(context, Effects.Effect.BOLD_TYPE)
                    ? com.google.android.material.R.style.TextAppearance_Material3_TitleLarge_Emphasized
                    : com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
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

        /**
         * Makes the card tappable (either action may be null). The card springs in while pressed and
         * ticks when it is tapped or held.
         */
        void onTap(Runnable tap, Runnable hold) {
            card.setClickable(true);
            card.setFocusable(true);
            if (tap != null) {
                card.setOnClickListener(v -> {
                    Effects.haptic(card, HapticFeedbackConstants.CONTEXT_CLICK);
                    tap.run();
                });
            }
            if (hold != null) {
                card.setOnLongClickListener(v -> {
                    Effects.haptic(card, HapticFeedbackConstants.LONG_PRESS);
                    hold.run();
                    return true;
                });
            }
            card.setOnTouchListener((v, e) -> {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    scaleTo(0.97f);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    scaleTo(1f);
                }
                return false; // the click and long-press still follow
            });
        }

        private void scaleTo(float to) {
            springX = Motion.spring(card, DynamicAnimation.SCALE_X, card.getScaleX(), to, true, springX);
            springY = Motion.spring(card, DynamicAnimation.SCALE_Y, card.getScaleY(), to, true, springY);
        }

        /** A small bounce when the number changed. */
        private void pulse() {
            if (!pulses || !Effects.on(card.getContext(), Effects.Effect.TICKERS)) {
                return;
            }
            springX = Motion.spring(card, DynamicAnimation.SCALE_X, 1.04f, 1f, true, springX);
            springY = Motion.spring(card, DynamicAnimation.SCALE_Y, 1.04f, 1f, true, springY);
        }

        private ValueAnimator ticker;
        private long shown = -1;

        void set(String main, String sub, String more) {
            show(main, -1, null, sub, more);
        }

        /** A value made of a count that is animated when it changes; {@code render} turns a count into the text. */
        void setCount(long count, java.util.function.LongFunction<String> render, String sub, String more) {
            show(render.apply(count), count, render, sub, more);
        }

        private void show(String main, long target, java.util.function.LongFunction<String> render,
                          String sub, String more) {
            boolean changed = target >= 0 ? shown >= 0 && target != shown
                    : lastMain != null && !main.equals(lastMain);
            if (changed) {
                pulse();
            }
            lastMain = main;
            if (ticker != null) {
                ticker.cancel();
            }
            if (target >= 0 && shown >= 0 && target != shown
                    && Effects.on(value.getContext(), Effects.Effect.TICKERS)) {
                ticker = ValueAnimator.ofFloat(shown, target);
                ticker.setDuration(400);
                ticker.addUpdateListener(a -> value.setText(
                        render.apply(Math.round((float) a.getAnimatedValue()))));
                ticker.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        value.setText(main);
                    }
                });
                ticker.start();
            } else {
                value.setText(main);
            }
            shown = target;
            detail.setText(sub);
            detail.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
            extra.setText(more);
            extra.setVisibility(more.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }
}
