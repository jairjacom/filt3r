package com.bridge.filt3r.ui;

import android.content.Context;
import android.animation.ObjectAnimator;
import android.text.TextUtils;
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
import android.graphics.Color;
import android.transition.Transition;
import android.transition.TransitionListenerAdapter;
import android.transition.TransitionManager;
import android.widget.FrameLayout;
import com.google.android.material.snackbar.Snackbar;
import android.text.Editable;
import android.text.TextWatcher;
import com.bridge.filt3r.core.nodes.NodeManager;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.Locale;

import com.bridge.filt3r.Effects;
import com.bridge.filt3r.R;
import com.bridge.filt3r.core.nodes.NodeInfo;
import com.bridge.filt3r.core.nodes.NodeLocation;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.transition.platform.MaterialContainerTransform;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen node list: the current node on top, then every node with status, place, latency,
 * IPv6 and speed. Tap a node to pin it (tap the pinned one to unpin); long-press opens its details.
 */
public class NodesPanel extends LinearLayout {

    /** What the panel needs from the app; implemented by the activity next to the service. */
    public interface Backend {
        /** Every node, best first; empty while the tunnel is down. */
        List<NodeInfo> nodes();

        /** Starts a re-test; {@code done} runs on a background thread. @return false if not possible now */
        boolean retest(Runnable done);

        /** Re-tests one node; {@code done} runs on a background thread. @return false if not possible now */
        boolean retestNode(ShadowsocksNode node, Runnable done);

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

        /** Keys (host:port) of the nodes the user hid. */
        java.util.Set<String> hiddenNodes();

        /** Hides the node: it is left out of the list and never used from the next start. */
        void hideNode(ShadowsocksNode node);

        /** Brings every hidden node back from the next start. */
        void showHiddenNodes();

        /** What {@link #source} returns for a node the user pasted. */
        int SOURCE_MANUAL = 99;

        int ADD_OK = 0;
        int ADD_DUPLICATE = 1;
        int ADD_FAILED = 2;

        /** Saves a pasted node in its own file and puts it in the running list. @return an ADD_ constant */
        int addManualNode(ShadowsocksNode node);

        /** Deletes a pasted node. Subscription nodes cannot be removed. */
        void removeManualNode(ShadowsocksNode node);
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
    private boolean onlyMine; // show only the nodes the user added with +
    private final LinearProgressIndicator progress;
    private FrameLayout detailLayer;
    private Runnable unblur = () -> { };
    private View detailCard;
    private View detailRow;
    private int panelAccessibility;
    private String query = "";
    private final MaterialButton showHidden;
    private ObjectAnimator retestSpin;

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
        ImageButton back = iconButton(context, R.drawable.ic_back, context.getString(R.string.nodes_back));
        back.setOnClickListener(v -> onClose.run());
        TextView title = new TextView(context);
        title.setText(R.string.nodes_title);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        retestButton = iconButton(context, R.drawable.ic_refresh, context.getString(R.string.nodes_retest_description));
        retestButton.setOnClickListener(v -> retest());
        ImageButton add = iconButton(context, R.drawable.ic_add, context.getString(R.string.nodes_add_description));
        add.setOnClickListener(v -> addFromClipboard());
        bar.addView(back);
        bar.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(add);
        bar.addView(retestButton);
        addView(bar);

        // Shown while the nodes are re-tested.
        progress = new LinearProgressIndicator(context);
        progress.setIndeterminate(true);
        progress.setVisibility(INVISIBLE);
        progress.setContentDescription(context.getString(R.string.nodes_testing_description));
        if (Effects.on(context, Effects.Effect.WAVY_PROGRESS)) {
            progress.setTrackThickness(dp(6));
            progress.setWaveAmplitude(dp(3));
            progress.setWavelengthIndeterminate(dp(40));
        }
        LayoutParams progressParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        progressParams.setMargins(dp(20), 0, dp(20), dp(8));
        addView(progress, progressParams);

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
        label.setText(R.string.nodes_current_label);
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        dim(label);
        currentName = new TextView(context);
        currentName.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        currentName.setSingleLine(true);
        currentName.setEllipsize(TextUtils.TruncateAt.END);
        currentDetail = new TextView(context);
        currentDetail.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        dim(currentDetail);
        currentIp = new TextView(context);
        currentIp.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        dim(currentIp);
        currentTested = new TextView(context);
        currentTested.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        dim(currentTested);
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
        MaterialButton byLatency = sortButton(context, context.getString(R.string.nodes_sort_latency), R.drawable.ic_clock);
        MaterialButton bySpeed = sortButton(context, context.getString(R.string.nodes_sort_speed), R.drawable.ic_bolt);
        sort.addView(byLatency);
        sort.addView(bySpeed);
        sort.check(backend.sortBySpeed() ? bySpeed.getId() : byLatency.getId());
        sort.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) {
                backend.setSortBySpeed(id == bySpeed.getId());
                reload();
            }
        });
        MaterialButton mine = sortButton(context, context.getString(R.string.nodes_filter_mine));
        mine.setIcon(context.getDrawable(R.drawable.ic_person));
        mine.setIconTint(mine.getTextColors());
        mine.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        mine.addOnCheckedChangeListener((b, checked) -> {
            onlyMine = checked;
            reload();
        });
        LinearLayout sortRow = new LinearLayout(context);
        sortRow.setGravity(Gravity.CENTER_VERTICAL);
        sortRow.addView(sort);
        LayoutParams mineParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        mineParams.leftMargin = dp(8);
        sortRow.addView(mine, mineParams);
        LayoutParams sortParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        sortParams.setMargins(dp(16), 0, dp(16), dp(8));
        addView(sortRow, sortParams);

        TextInputLayout searchLayout = new TextInputLayout(context);
        searchLayout.setHint(context.getString(R.string.nodes_search_hint));
        TextInputEditText search = new TextInputEditText(searchLayout.getContext());
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                reload();
            }
        });
        searchLayout.addView(search, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LayoutParams searchParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(dp(16), 0, dp(16), dp(8));
        addView(searchLayout, searchParams);

        showHidden = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        showHidden.setVisibility(GONE);
        showHidden.setOnClickListener(v -> Dialogs.confirm(getContext(),
                context.getString(R.string.nodes_unhide_title), context.getString(R.string.nodes_unhide_message),
                context.getString(R.string.nodes_unhide_action), () -> {
                    backend.showHiddenNodes();
                    reload();
                    Messages.show(this, context.getString(R.string.nodes_unhidden_done), Snackbar.LENGTH_SHORT);
                }));
        LayoutParams hiddenParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hiddenParams.setMargins(dp(16), 0, dp(16), dp(8));
        addView(showHidden, hiddenParams);

        empty = new TextView(context);
        empty.setText(R.string.nodes_empty);
        empty.setGravity(Gravity.CENTER);
        dim(empty);
        empty.setPadding(dp(16), dp(48), dp(16), dp(16));
        addView(empty);

        list = new ListView(context);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(96)); // the toolbar overlays the bottom
        list.setOnItemClickListener((p, v, pos, id) -> onNodeTap(adapter.getItem(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showDetail(v, adapter.getItem(pos));
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
        if (detailLayer != null) {
            removeDetail(); // no transition: the panel is going away
        }
        super.onDetachedFromWindow();
    }

    /** Closes the node details if they are open. Returns false when there was nothing to close. */
    public boolean handleBack() {
        if (detailLayer == null) {
            return false;
        }
        closeDetail();
        return true;
    }

    private void onNodeTap(NodeInfo n) {
        if (n.state == NodeInfo.State.BLACKLISTED) {
            Messages.show(this, getContext().getString(R.string.nodes_blacklisted_message));
            return;
        }
        if (n.pinned) {
            backend.unpin();
            reload();
            return;
        }
        if (n.state == NodeInfo.State.UNTESTED) {
            Messages.show(this, getContext().getString(R.string.nodes_testing_first), Snackbar.LENGTH_SHORT);
        }
        if (!backend.pin(n.node, () -> post(this::reload))) {
            Messages.show(this, getContext().getString(R.string.nodes_connect_first), Snackbar.LENGTH_SHORT);
        }
    }

    private void setBusy(boolean on) {
        busy = on;
        retestButton.setEnabled(!on);
        retestButton.setAlpha(on ? 0.4f : 1f);
        if (on) {
            progress.show();
        } else {
            progress.hide();
        }
    }

    /** Re-tests one node from its details card: the button shows a spinner, then a check, then the card closes. */
    private void retestFromCard(NodeInfo n, ProgressButton button) {
        if (busy) {
            Messages.show(this, getContext().getString(R.string.nodes_already_testing), Snackbar.LENGTH_SHORT);
            return;
        }
        View cardAtStart = detailCard;
        setBusy(true);
        button.busy();
        boolean started = backend.retestNode(n.node, () -> post(() -> {
            setBusy(false);
            button.finish(true);
            reload();
            postDelayed(() -> {
                if (detailCard == cardAtStart) {
                    closeDetail(); // the row behind shows the new result
                }
            }, 1000);
        }));
        if (!started) {
            setBusy(false);
            button.finish(false);
            Messages.show(this, getContext().getString(R.string.nodes_not_possible), Snackbar.LENGTH_SHORT);
        }
    }

    private void retest() {
        if (busy) {
            return;
        }
        setBusy(true);
        retestSpin = ActionMorph.busy(retestButton);
        Messages.show(this, getContext().getString(R.string.nodes_retesting_best), Snackbar.LENGTH_SHORT);
        boolean started = backend.retest(() -> post(() -> {
            setBusy(false);
            ActionMorph.finish(retestButton, retestSpin, true, R.drawable.ic_refresh);
            reload();
        }));
        if (!started) {
            setBusy(false);
            ActionMorph.finish(retestButton, retestSpin, false, R.drawable.ic_refresh);
            Messages.show(this, getContext().getString(R.string.nodes_not_possible), Snackbar.LENGTH_SHORT);
        }
    }

    private void reload() {
        List<NodeInfo> everything = new ArrayList<>(backend.nodes());
        if (everything.isEmpty() && busy) {
            // The tunnel stopped while a re-test was queued: its callback will never run.
            setBusy(false);
        }
        NodeInfo current = null;
        for (NodeInfo n : everything) {
            if (n.current) {
                current = n;
            }
        }
        java.util.Set<String> hidden = backend.hiddenNodes();
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<NodeInfo> all = new ArrayList<>();
        for (NodeInfo n : everything) {
            if (hidden.contains(NodeManager.keyOf(n.node)) && !n.current) {
                continue; // hidden nodes stay out of sight; the one in use stays until the next start
            }
            if (!q.isEmpty() && !n.node.name.toLowerCase(Locale.ROOT).contains(q)
                    && !n.node.host.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            all.add(n);
        }
        showHidden.setVisibility(hidden.isEmpty() ? GONE : VISIBLE);
        showHidden.setText(getContext().getString(R.string.nodes_show_hidden, hidden.size()));
        if (backend.sortBySpeed()) {
            // Stable: nodes with the same speed (or none) keep their latency order; state groups stay.
            all.sort((a, b) -> {
                int byState = a.state.compareTo(b.state);
                return byState != 0 ? byState : Long.compare(b.speedBps, a.speedBps);
            });
        }
        // Nodes the user added come first, so a new one is easy to find; the order inside each group stays.
        List<NodeInfo> mineFirst = new ArrayList<>();
        List<NodeInfo> others = new ArrayList<>();
        for (NodeInfo n : all) {
            (backend.source(n.node) == Backend.SOURCE_MANUAL ? mineFirst : others).add(n);
        }
        all = new ArrayList<>(mineFirst);
        if (!onlyMine) {
            all.addAll(others);
        }
        if (current != null && all.remove(current)) {
            all.add(0, current); // the node in use is always the first row
        }
        boolean nothingAdded = onlyMine && mineFirst.isEmpty() && !others.isEmpty();
        adapter.set(all);
        empty.setText(everything.isEmpty() ? R.string.nodes_empty
                : nothingAdded ? R.string.nodes_none_added : R.string.nodes_no_match);
        if (all.isEmpty() && empty.getVisibility() != VISIBLE
                && Effects.on(getContext(), Effects.Effect.TRANSITIONS)) {
            empty.setAlpha(0f);
            empty.animate().alpha(1f).setDuration(Effects.ms(getContext(), Effects.Effect.TRANSITIONS, 200));
        }
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
            currentDetail.setText(details(getContext(), current));
            currentDetail.setVisibility(VISIBLE);
            currentIp.setText(getContext().getString(R.string.nodes_current_ip, current.node.host));
            currentIp.setVisibility(VISIBLE);
        }
        currentTested.setText(backend.lastTestText());
        currentTested.setVisibility(everything.isEmpty() ? GONE : VISIBLE);
    }

    private static String place(NodeLocation loc) {
        return loc.flag.isEmpty() ? loc.city : loc.flag + " " + loc.city;
    }

    private static String details(Context ctx, NodeInfo n) {
        StringBuilder sb = new StringBuilder();
        if (n.medianMs >= 0) {
            sb.append(ctx.getString(R.string.nodes_ms, n.medianMs));
        } else {
            sb.append(ctx.getString(R.string.nodes_not_measured));
        }
        if (n.speedBps >= 0) {
            sb.append("  ·  ").append(Format.bytes(n.speedBps)).append("/s");
        }
        sb.append("  ·  ").append(ctx.getString(n.ipv6 == null ? R.string.nodes_ipv6_checking
                : n.ipv6 ? R.string.nodes_ipv6_on : R.string.nodes_ipv6_off));
        if (n.pinned) {
            sb.append("  ·  ").append(ctx.getString(R.string.nodes_pinned));
        }
        return sb.toString();
    }

    private static String stateText(Context ctx, NodeInfo n) {
        if (n.current) {
            return ctx.getString(R.string.nodes_state_in_use);
        }
        switch (n.state) {
            case WORKING:
                return ctx.getString(R.string.nodes_state_working);
            case BLACKLISTED:
                return ctx.getString(R.string.nodes_state_dead);
            default:
                return ctx.getString(n.failures > 0 ? R.string.nodes_state_no_answer_yet : R.string.nodes_state_not_tested);
        }
    }

    /** Opens the details of a node; the row grows into the card (and shrinks back when it closes). */
    private void showDetail(View row, NodeInfo n) {
        if (detailLayer != null || !(getParent() instanceof FrameLayout)) {
            return;
        }
        FrameLayout host = (FrameLayout) getParent();
        Context context = getContext();
        FrameLayout layer = new FrameLayout(context);
        layer.setClickable(true);
        View scrim = new View(context);
        scrim.setBackgroundColor(0x66000000);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> closeDetail());
        scrim.setContentDescription(context.getString(R.string.nodes_close_details));
        layer.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout card = detailCard(context, n);
        card.setVisibility(INVISIBLE);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cardParams.setMargins(dp(24), dp(24), dp(24), dp(24));
        layer.addView(card, cardParams);
        host.addView(layer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        detailLayer = layer;
        detailCard = card;
        detailRow = row;
        unblur = Dialogs.blurView(this);
        panelAccessibility = getImportantForAccessibility();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        int ms = Effects.ms(context, Effects.Effect.TRANSITIONS, 300);
        if (ms == 0) {
            card.setVisibility(VISIBLE);
            scrim.setAlpha(1f);
            return;
        }
        layer.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                layer.removeOnLayoutChangeListener(this);
                morph(host, row, card, ms, null);
                scrim.animate().alpha(1f).setDuration(ms);
            }
        });
    }

    private void closeDetail() {
        FrameLayout layer = detailLayer;
        if (layer == null) {
            return;
        }
        View row = detailRow;
        View card = detailCard;
        detailLayer = null;
        unblur.run();
        unblur = () -> { };
        setImportantForAccessibility(panelAccessibility);
        int ms = Effects.ms(getContext(), Effects.Effect.TRANSITIONS, 250);
        if (ms == 0 || row == null || !row.isAttachedToWindow() || !(layer.getParent() instanceof FrameLayout)) {
            if (row != null) {
                row.setVisibility(VISIBLE);
            }
            ((ViewGroup) layer.getParent()).removeView(layer);
            return;
        }
        morph((FrameLayout) layer.getParent(), card, row, ms, () -> {
            if (layer.getParent() != null) {
                ((ViewGroup) layer.getParent()).removeView(layer);
            }
        });
        layer.getChildAt(0).animate().alpha(0f).setDuration(ms);
    }

    /** Removes the details at once (the panel is closing). */
    private void removeDetail() {
        FrameLayout layer = detailLayer;
        detailLayer = null;
        unblur.run();
        unblur = () -> { };
        if (detailRow != null) {
            detailRow.setVisibility(VISIBLE);
        }
        if (layer != null && layer.getParent() != null) {
            ((ViewGroup) layer.getParent()).removeView(layer);
        }
    }

    /** Container transform: {@code from} turns into {@code to}. {@code done} runs when it ends. */
    private void morph(FrameLayout host, View from, View to, int ms, Runnable done) {
        MaterialContainerTransform t = new MaterialContainerTransform();
        t.setStartView(from);
        t.setEndView(to);
        t.setScrimColor(Color.TRANSPARENT);
        t.setAllContainerColors(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceContainerHigh));
        t.setFadeMode(MaterialContainerTransform.FADE_MODE_CROSS);
        ShapeAppearanceModel round = ShapeAppearanceModel.builder().setAllCornerSizes(dp(28)).build();
        t.setStartShapeAppearanceModel(round);
        t.setEndShapeAppearanceModel(round);
        t.setDuration(ms);
        if (done != null) {
            t.addListener(new TransitionListenerAdapter() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    done.run();
                }

                @Override
                public void onTransitionCancel(Transition transition) {
                    done.run();
                }
            });
        }
        TransitionManager.beginDelayedTransition(host, t);
        to.setVisibility(VISIBLE);
        from.setVisibility(INVISIBLE);
    }

    private LinearLayout detailCard(Context context, NodeInfo n) {
        NodeLocation loc = NodeLocation.parse(n.node.name);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(24), dp(24), dp(24), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh));
        bg.setCornerRadius(dp(28));
        card.setBackground(bg);
        card.setAccessibilityPaneTitle(place(loc));

        TextView name = new TextView(context);
        name.setText(place(loc));
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        card.addView(name);
        card.addView(detailLine(context, context.getString(R.string.nodes_detail_address), n.node.host + ":" + n.node.port));
        card.addView(detailLine(context, context.getString(R.string.nodes_detail_status), stateText(context, n)));
        card.addView(detailLine(context, context.getString(R.string.nodes_detail_latency), n.medianMs >= 0
                ? context.getString(R.string.nodes_ms, n.medianMs) : context.getString(R.string.nodes_detail_not_measured)));
        card.addView(detailLine(context, context.getString(R.string.nodes_detail_speed), n.speedBps >= 0
                ? Format.bytes(n.speedBps) + "/s" : context.getString(R.string.nodes_detail_not_measured)));
        card.addView(detailLine(context, context.getString(R.string.nodes_ipv6), context.getString(n.ipv6 == null
                ? R.string.nodes_detail_checking : n.ipv6 ? R.string.nodes_detail_on : R.string.nodes_detail_off)));
        int source = backend.source(n.node);
        if (source == Backend.SOURCE_MANUAL || (source >= 0 && backend.sourceCount() > 1)) {
            card.addView(detailLine(context, context.getString(R.string.nodes_detail_subscription),
                    sourceTag(context, source)));
        }

        LinearLayout buttons = new LinearLayout(context);
        buttons.setGravity(Gravity.END);
        MaterialButton close = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        close.setText(R.string.nodes_close);
        close.setOnClickListener(v -> closeDetail());
        MaterialButton pin = new MaterialButton(context);
        pin.setText(n.pinned ? R.string.nodes_unpin : R.string.nodes_pin);
        pin.setEnabled(n.state != NodeInfo.State.BLACKLISTED);
        pin.setOnClickListener(v -> {
            closeDetail();
            onNodeTap(n);
        });
        LayoutParams gap = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        gap.rightMargin = dp(8);
        ProgressButton test = new ProgressButton(context, context.getString(R.string.nodes_retest_button));
        test.setOnClick(v -> retestFromCard(n, test));
        buttons.addView(close, gap);
        LayoutParams testGap = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        testGap.rightMargin = dp(8);
        buttons.addView(test, testGap);
        buttons.addView(pin);
        LayoutParams buttonParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(16);
        card.addView(buttons, buttonParams);

        MaterialButton hide = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        hide.setText(R.string.nodes_hide_button);
        hide.setOnClickListener(v -> {
            closeDetail();
            Dialogs.confirm(getContext(), context.getString(R.string.nodes_hide_title),
                    context.getString(R.string.nodes_hide_message), context.getString(R.string.nodes_hide_action), () -> {
                        backend.hideNode(n.node);
                        reload();
                        Messages.show(this, context.getString(R.string.nodes_hidden_done), Snackbar.LENGTH_SHORT);
                    });
        });
        LayoutParams hideParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        hideParams.topMargin = dp(8);
        card.addView(hide, hideParams);
        LinearLayout more = new LinearLayout(context);
        more.setGravity(Gravity.END);
        MaterialButton copy = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        copy.setText(R.string.nodes_copy_link);
        copy.setOnClickListener(v -> confirmCopy(n));
        more.addView(copy);
        if (source == Backend.SOURCE_MANUAL) {
            MaterialButton remove = new MaterialButton(context, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            remove.setText(R.string.nodes_remove);
            remove.setOnClickListener(v -> confirmRemove(n));
            LayoutParams removeGap = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            removeGap.leftMargin = dp(8);
            more.addView(remove, removeGap);
        }
        LayoutParams moreParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        moreParams.topMargin = dp(8);
        card.addView(more, moreParams);
        return card;
    }

    private View detailLine(Context context, String label, String value) {
        LinearLayout line = new LinearLayout(context);
        line.setPadding(0, dp(10), 0, 0);
        TextView l = new TextView(context);
        l.setText(label);
        l.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        dim(l);
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        line.addView(l, new LayoutParams(dp(104), LayoutParams.WRAP_CONTENT));
        line.addView(v, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        return line;
    }

    private MaterialButton sortButton(Context context, String text, int icon) {
        MaterialButton b = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setId(View.generateViewId());
        b.setCheckable(true);
        Accent.selectable(b);
        Accent.morphIcon(b, icon);
        return b;
    }

    private ImageButton iconButton(Context context, int res, String description) {
        ImageButton b = new ImageButton(context);
        b.setImageResource(res);
        b.setContentDescription(description);
        b.setBackground(Ripple.borderless(context));
        b.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface)));
        b.setLayoutParams(new LayoutParams(dp(48), dp(48)));
        return b;
    }

    private static String sourceTag(Context context, int source) {
        return source == Backend.SOURCE_MANUAL ? context.getString(R.string.nodes_manual_tag)
                : context.getString(R.string.nodes_sub, source + 1);
    }

    /** Reads the clipboard (only now, because the user tapped +), checks it and asks before adding. */
    private void addFromClipboard() {
        Context context = getContext();
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = cm.getPrimaryClip();
        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(context);
        String link = text == null ? "" : text.toString().trim();
        if (!link.regionMatches(true, 0, "ss://", 0, 5)) {
            Messages.show(this, context.getString(R.string.nodes_add_clipboard_empty));
            return;
        }
        ShadowsocksNode node;
        try {
            node = ShadowsocksNode.parse(link);
        } catch (IllegalArgumentException e) {
            Messages.show(this, context.getString(R.string.nodes_add_bad, e.getMessage()));
            return;
        }
        if (com.bridge.filt3r.core.util.IpLiteral.parse(node.host) == null) {
            Messages.show(this, context.getString(R.string.nodes_add_hostname));
            return;
        }
        ShadowsocksNode chosen = node;
        Dialogs.confirm(context, context.getString(R.string.nodes_add_title),
                context.getString(R.string.nodes_add_message, node.host, node.port, node.suite.methodName),
                context.getString(R.string.nodes_add_action), () -> {
                    int result = backend.addManualNode(chosen);
                    Messages.show(this, context.getString(result == Backend.ADD_OK ? R.string.nodes_added
                            : result == Backend.ADD_DUPLICATE ? R.string.nodes_add_duplicate
                            : R.string.nodes_add_failed), Snackbar.LENGTH_SHORT);
                    reload();
                });
    }

    /** The link holds the password, so it is only copied after the user agrees, and marked sensitive. */
    private void confirmCopy(NodeInfo n) {
        Context context = getContext();
        Dialogs.confirm(context, context.getString(R.string.nodes_copy_title),
                context.getString(R.string.nodes_copy_message), context.getString(R.string.nodes_copy_action), () -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            context.getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("filt3r node", n.node.toUri());
                    android.os.PersistableBundle extras = new android.os.PersistableBundle();
                    extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
                    clip.getDescription().setExtras(extras); // keeps keyboards and clipboard previews from showing it
                    cm.setPrimaryClip(clip);
                    Messages.show(this, context.getString(R.string.nodes_copied), Snackbar.LENGTH_SHORT);
                });
    }

    private void confirmRemove(NodeInfo n) {
        Context context = getContext();
        Dialogs.confirm(context, context.getString(R.string.nodes_remove_title),
                context.getString(R.string.nodes_remove_message), context.getString(R.string.nodes_remove),
                () -> {
                    closeDetail();
                    backend.removeManualNode(n.node);
                    Messages.show(this, context.getString(R.string.nodes_removed), Snackbar.LENGTH_SHORT);
                    reload();
                });
    }

    private static void dim(TextView t) {
        t.setTextColor(MaterialColors.getColor(t.getContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class NodeAdapter extends BaseAdapter {
        private List<NodeInfo> items = new ArrayList<>();
        private boolean boundBySpeed;

        void set(List<NodeInfo> next) {
            boolean bySpeed = backend.sortBySpeed();
            if (bySpeed == boundBySpeed && sameItems(items, next)) {
                return;
            }
            boundBySpeed = bySpeed;
            items = next;
            notifyDataSetChanged();
        }

        /** NodeInfo has no equals(): compare every displayed field (the node by address and name). */
        private boolean sameItems(List<NodeInfo> a, List<NodeInfo> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                NodeInfo x = a.get(i);
                NodeInfo y = b.get(i);
                if (!x.node.host.equals(y.node.host) || x.node.port != y.node.port
                        || !x.node.name.equals(y.node.name) || x.state != y.state
                        || x.medianMs != y.medianMs || x.failures != y.failures
                        || x.current != y.current || x.pinned != y.pinned
                        || x.speedBps != y.speedBps || !java.util.Objects.equals(x.ipv6, y.ipv6)) {
                    return false;
                }
            }
            return true;
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
            row.bind(items.get(position), boundBySpeed);
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
        private final View bar;
        private final GradientDrawable barBg = new GradientDrawable();

        Row(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(20), dp(12), dp(20), dp(12));

            dotBg.setShape(GradientDrawable.OVAL);
            dot = new View(context);
            dot.setBackground(dotBg);
            dot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // the subtitle says the state
            LayoutParams dotParams = new LayoutParams(dp(10), dp(10));
            dotParams.rightMargin = dp(16);
            addView(dot, dotParams);

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            title = new TextView(context);
            title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            subtitle = new TextView(context);
            subtitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            dim(subtitle);
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
            pin.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // the row says "pinned"
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
            ipv6.setText(R.string.nodes_ipv6);
            ipv6.setTextColor(Colors.active(context));
            right.addView(latency);
            barBg.setCornerRadius(dp(2));
            bar = new View(context);
            bar.setBackground(barBg);
            bar.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            right.addView(bar, new LayoutParams(dp(84), dp(3)));
            right.addView(ipv6);
            addView(right, new LayoutParams(dp(84), LayoutParams.WRAP_CONTENT)); // fixed width: columns line up
        }

        void bind(NodeInfo n, boolean bySpeed) {
            NodeLocation loc = NodeLocation.parse(n.node.name);
            title.setText(loc.city);
            // The right column shows the metric the list is sorted by; the other one goes under the place.
            String ms = n.medianMs >= 0 ? getContext().getString(R.string.nodes_ms, n.medianMs) : "—";
            String speed = n.speedBps >= 0 ? Format.bytes(n.speedBps) + "/s" : "—";
            StringBuilder sub = new StringBuilder();
            if (!loc.countryCode.isEmpty()) {
                sub.append(loc.flag).append(' ').append(loc.countryCode).append("  ·  ");
            }
            sub.append(bySpeed ? ms : speed);
            int source = backend.source(n.node);
            if (source == Backend.SOURCE_MANUAL || (source >= 0 && backend.sourceCount() > 1)) {
                sub.append("  ·  ").append(sourceTag(getContext(), source));
            }
            subtitle.setText(sub.toString());
            latency.setText(bySpeed ? speed : ms);
            ipv6.setVisibility(Boolean.TRUE.equals(n.ipv6) ? VISIBLE : GONE);
            pin.setVisibility(n.pinned ? VISIBLE : GONE);

            int color;
            switch (n.state) {
                case WORKING:
                    color = Colors.active(getContext());
                    break;
                case BLACKLISTED:
                    color = getContext().getColor(R.color.brand_red);
                    break;
                default:
                    color = getContext().getColor(R.color.state_inactive);
                    break;
            }
            dotBg.setColor(color);
            if (Effects.on(getContext(), Effects.Effect.NODE_BARS) && n.medianMs >= 0
                    && n.state != NodeInfo.State.BLACKLISTED) {
                float fill = 1f - Math.min(n.medianMs, 1000) / 1000f;
                barBg.setColor(color);
                LayoutParams barParams = (LayoutParams) bar.getLayoutParams();
                barParams.width = Math.max(dp(2), (int) (dp(84) * fill));
                bar.setLayoutParams(barParams);
                bar.setVisibility(VISIBLE);
            } else {
                bar.setVisibility(GONE);
            }
            // The tag says why a row has no green dot (or that it is the node in use).
            String tagText = null;
            int tagColor = color;
            if (n.current) {
                tagText = getContext().getString(R.string.nodes_tag_in_use);
            } else if (n.state == NodeInfo.State.BLACKLISTED) {
                tagText = getContext().getString(R.string.nodes_tag_dead);
            } else if (n.state == NodeInfo.State.UNTESTED) {
                tagText = getContext().getString(n.failures > 0 ? R.string.nodes_tag_no_answer : R.string.nodes_tag_not_tested);
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
            StringBuilder said = new StringBuilder(loc.city);
            if (!loc.countryCode.isEmpty()) {
                said.append(", ").append(loc.countryCode);
            }
            said.append(", ").append(n.medianMs >= 0
                    ? getContext().getString(R.string.nodes_said_ms, n.medianMs)
                    : getContext().getString(R.string.nodes_not_measured));
            if (n.speedBps >= 0) {
                said.append(", ").append(getContext().getString(R.string.nodes_said_per_second, Format.bytes(n.speedBps)));
            }
            if (tagText != null) {
                said.append(", ").append(tagText);
            }
            if (n.pinned) {
                said.append(", ").append(getContext().getString(R.string.nodes_pinned));
            }
            if (Boolean.TRUE.equals(n.ipv6)) {
                said.append(", ").append(getContext().getString(R.string.nodes_ipv6));
            }
            setContentDescription(said.toString());
            setAlpha(n.state == NodeInfo.State.BLACKLISTED ? 0.5f : 1f);
            setBackgroundColor(n.current
                    ? (color & 0x00FFFFFF) | 0x1A000000
                    : 0);
        }
    }
}
