package com.bridge.filt3r.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewTreeObserver;

import com.google.android.material.color.MaterialColors;

/**
 * A frosted strip along the bottom of the screen: whatever scrolls under the toolbar is drawn blurred
 * and fades out towards the top. It copies its parent's drawing into a blurred layer, so it works
 * without the system's cross-window blur.
 *
 * The copy is redrawn on scroll and layout, and for a short while after {@link #pulse()} (touches and
 * screen changes), not on every frame, so an idle screen costs nothing.
 */
public class BlurBehind extends View {

    private static final int BLUR_DP = 16;
    private static final int PULSE_MS = 700;
    private static final int TINT_ALPHA = 150;

    private final RenderNode node = new RenderNode("blurBehind");
    private final Paint tint = new Paint();
    private final Paint mask = new Paint();
    private final float radius;
    private final View source; // a sibling drawn under this view, never this view's own parent or child
    private long pulseUntil;

    private final ViewTreeObserver.OnScrollChangedListener onScroll = this::pulse;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            invalidate();
            if (isAttachedToWindow()
                    && android.view.animation.AnimationUtils.currentAnimationTimeMillis() < pulseUntil) {
                postOnAnimation(this);
            }
        }
    };

    public BlurBehind(Context context, View source) {
        super(context);
        this.source = source;
        radius = BLUR_DP * context.getResources().getDisplayMetrics().density;
        node.setClipToBounds(true);
        node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Keeps the strip up to date for a moment; call when something under it is about to move. */
    public void pulse() {
        long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
        boolean idle = pulseUntil <= now;
        pulseUntil = now + PULSE_MS;
        if (idle) {
            postOnAnimation(frame);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(onScroll);
        pulse();
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnScrollChangedListener(onScroll);
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mask.setShader(new LinearGradient(0, 0, 0, h, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        pulse();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!canvas.isHardwareAccelerated()) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        node.setPosition(0, 0, w, h);
        RecordingCanvas recording = node.beginRecording();
        try {
            recording.translate(source.getLeft() - getLeft(), source.getTop() - getTop());
            source.draw(recording);
        } finally {
            node.endRecording();
        }
        int save = canvas.saveLayer(0, 0, w, h, null);
        canvas.drawRenderNode(node);
        tint.setColor(MaterialColors.getColor(this, android.R.attr.colorBackground));
        tint.setAlpha(TINT_ALPHA);
        canvas.drawRect(0, 0, w, h, tint);
        canvas.drawRect(0, 0, w, h, mask);
        canvas.restoreToCount(save);
    }
}
