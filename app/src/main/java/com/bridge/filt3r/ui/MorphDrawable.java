package com.bridge.filt3r.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.graphics.shapes.CornerRounding;
import androidx.graphics.shapes.Morph;
import androidx.graphics.shapes.RoundedPolygon;
import androidx.graphics.shapes.ShapesKt;
import androidx.graphics.shapes.Shapes_androidKt;

/**
 * The status circle's background: a filled outline that can morph from one shape to another.
 * Shapes are drawn in a unit square and scaled to the drawable's bounds.
 */
final class MorphDrawable extends Drawable {

    static final int CIRCLE = 0;
    static final int FLOWER = 1;
    static final int COOKIE = 2;
    static final int SQUIRCLE = 3;

    private static final RoundedPolygon[] SHAPES = shapes();

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Matrix matrix = new Matrix();
    private final float strokePx;
    private Morph morph; // null while showing one shape
    private int from = CIRCLE;
    private int to = CIRCLE;
    private float progress = 1f;
    private int fillColor;
    private int outlineColor;
    private int alpha = 255;

    MorphDrawable(float strokePx) {
        this.strokePx = strokePx;
        fill.setStyle(Paint.Style.FILL);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(strokePx);
        rebuild();
    }

    private static RoundedPolygon[] shapes() {
        RoundedPolygon.Companion k = RoundedPolygon.Companion;
        RoundedPolygon[] shapes = new RoundedPolygon[4];
        shapes[CIRCLE] = ShapesKt.circle(k, 12, 0.5f, 0.5f, 0.5f);
        shapes[FLOWER] = ShapesKt.star(k, 8, 0.5f, 0.40f, new CornerRounding(0.12f, 0.5f), null, null, 0.5f, 0.5f);
        shapes[COOKIE] = ShapesKt.star(k, 9, 0.5f, 0.45f, new CornerRounding(0.15f, 0.5f), null, null, 0.5f, 0.5f);
        shapes[SQUIRCLE] = ShapesKt.rectangle(k, 0.88f, 0.88f, new CornerRounding(0.28f, 0.5f), null, 0.5f, 0.5f);
        return shapes;
    }

    /** Shows the shape {@code from} morphed {@code progress} (0 to 1) of the way to {@code to}. */
    void set(int fromShape, int toShape, float morphProgress) {
        if (fromShape != from || toShape != to) {
            from = fromShape;
            to = toShape;
            morph = from == to ? null : new Morph(SHAPES[from], SHAPES[to]);
        }
        progress = Math.max(0f, Math.min(1f, morphProgress));
        rebuild();
        invalidateSelf();
    }

    void setColors(int fill, int outline) {
        fillColor = fill;
        outlineColor = outline;
        applyColors();
        invalidateSelf();
    }

    private void applyColors() {
        this.fill.setColor(fillColor);
        this.fill.setAlpha(android.graphics.Color.alpha(fillColor) * alpha / 255);
        this.outline.setColor(outlineColor);
        this.outline.setAlpha(android.graphics.Color.alpha(outlineColor) * alpha / 255);
    }

    private void rebuild() {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        path.rewind();
        if (morph == null) {
            Shapes_androidKt.toPath(SHAPES[to], path);
        } else {
            Shapes_androidKt.toPath(morph, progress, path);
        }
        // Keep the outline inside the bounds.
        matrix.setScale(b.width() - strokePx, b.height() - strokePx);
        matrix.postTranslate(b.left + strokePx / 2f, b.top + strokePx / 2f);
        path.transform(matrix);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        rebuild();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawPath(path, fill);
        canvas.drawPath(path, outline);
    }

    @Override
    public void setAlpha(int newAlpha) {
        alpha = newAlpha;
        applyColors();
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        outline.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
