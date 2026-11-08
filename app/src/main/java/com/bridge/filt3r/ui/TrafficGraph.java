package com.bridge.filt3r.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import com.bridge.filt3r.R;
import com.google.android.material.color.MaterialColors;

/**
 * The traffic graph: download and upload as two smooth lines, the download with a soft fill and a dot
 * at the newest value. The compact form sits on the Status card; the expanded form adds a speed
 * scale, time labels and touch-to-inspect. The newest sample is at the right edge.
 */
public final class TrafficGraph extends View {

    /** How many samples fill the whole width (the service keeps this many, one every 2 seconds). */
    private static final int COUNT = 300;
    private static final int SECONDS_PER_SAMPLE = 2;

    private final boolean expanded;
    private final float density;
    private float[] down = new float[0];
    private float[] up = new float[0];
    private int touchIndex = -1;

    private final Paint downLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint upLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path downPath = new Path();
    private final Path upPath = new Path();
    private final Path fillPath = new Path();
    private final float[] xs = new float[COUNT];
    private final float[] ys = new float[COUNT];
    private final int downColor;

    public TrafficGraph(Context context, boolean expanded) {
        super(context);
        this.expanded = expanded;
        density = getResources().getDisplayMetrics().density;
        downColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary);
        int upColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, downColor);
        int textColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, downColor);
        int gridColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, textColor);
        for (Paint p : new Paint[] {downLine, upLine}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        downLine.setStrokeWidth((expanded ? 2.5f : 2f) * density);
        downLine.setColor(downColor);
        upLine.setStrokeWidth((expanded ? 2f : 1.5f) * density);
        upLine.setColor(upColor);
        fill.setStyle(Paint.Style.FILL);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(density);
        grid.setColor(gridColor);
        label.setColor(textColor);
        label.setTextSize(12 * density);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(downColor);
        if (!expanded) {
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // the card already says it in words
        }
    }

    /** Replaces the samples (bytes per second, oldest first). The arrays are read, not copied. */
    public void setData(float[] down, float[] up) {
        this.down = down;
        this.up = up;
        if (touchIndex >= down.length) {
            touchIndex = -1;
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(getDefaultSize(0, widthSpec), (int) ((expanded ? 220 : 56) * density + 0.5f));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        fill.setShader(new LinearGradient(0, 0, 0, h, (downColor & 0x00FFFFFF) | 0x59000000,
                downColor & 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = Math.min(down.length, up.length);
        float w = getWidth();
        float pad = 2 * density;
        float top = expanded ? 20 * density : pad;
        float bottom = getHeight() - (expanded ? 20 * density : pad);
        float h = bottom - top;
        float max = 1;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, Math.max(down[i], up[i]));
        }
        if (expanded) {
            for (int i = 0; i < 3; i++) {
                float y = top + h * i / 2f;
                canvas.drawLine(0, y, w, y, grid);
            }
            label.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(Format.bytes((long) max) + "/s", 0, top - 6 * density, label);
            canvas.drawText(getContext().getString(R.string.status_graph_minutes_ago,
                    Math.max(1, Math.round(n * SECONDS_PER_SAMPLE / 60f))), 0, getHeight() - 4 * density, label);
            label.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(getContext().getString(R.string.status_graph_now), w, getHeight() - 4 * density, label);
        }
        if (n < 2) {
            return;
        }
        build(downPath, down, n, max, w, top, h);
        build(upPath, up, n, max, w, top, h);
        fillPath.set(downPath);
        fillPath.lineTo(xs[n - 1], bottom);
        fillPath.lineTo(xs[0], bottom);
        fillPath.close();
        canvas.drawPath(fillPath, fill);
        canvas.drawPath(upPath, upLine);
        canvas.drawPath(downPath, downLine);
        // xs and ys now hold the upload points; the download ones are needed for the dots.
        float newestUpX = xs[n - 1];
        float newestDownY = top + h - h * down[n - 1] / max;
        canvas.drawCircle(newestUpX, newestDownY, (expanded ? 4 : 3) * density, dot);
        if (expanded && touchIndex >= 0 && touchIndex < n) {
            float x = xs[touchIndex];
            canvas.drawLine(x, top, x, bottom, grid);
            canvas.drawCircle(x, top + h - h * down[touchIndex] / max, 4 * density, dot);
            int secondsAgo = (n - 1 - touchIndex) * SECONDS_PER_SAMPLE;
            String ago = secondsAgo == 0 ? getContext().getString(R.string.status_graph_now)
                    : getContext().getString(R.string.status_graph_ago, secondsAgo / 60, secondsAgo % 60);
            label.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(getContext().getString(R.string.status_graph_inspect, Format.bytes((long) down[touchIndex]),
                    Format.bytes((long) up[touchIndex]), ago), w, top - 6 * density, label);
        }
    }

    /** Builds a smooth curve (Catmull-Rom turned into cubic segments) through the samples. */
    private void build(Path p, float[] v, int n, float max, float w, float top, float h) {
        p.reset();
        float step = w / (COUNT - 1);
        for (int i = 0; i < n; i++) {
            xs[i] = w - (n - 1 - i) * step;
            ys[i] = top + h - h * v[i] / max;
        }
        p.moveTo(xs[0], ys[0]);
        for (int i = 0; i < n - 1; i++) {
            int a = Math.max(i - 1, 0);
            int b = Math.min(i + 2, n - 1);
            float c1x = xs[i] + (xs[i + 1] - xs[a]) / 6f;
            float c1y = clamp(ys[i] + (ys[i + 1] - ys[a]) / 6f, top, top + h);
            float c2x = xs[i + 1] - (xs[b] - xs[i]) / 6f;
            float c2y = clamp(ys[i + 1] - (ys[b] - ys[i]) / 6f, top, top + h);
            p.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1]);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int n = Math.min(down.length, up.length);
        if (!expanded || n == 0) {
            return false; // on the card, touches belong to the card (tap, long-press)
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                float step = getWidth() / (float) (COUNT - 1);
                float first = getWidth() - (n - 1) * step;
                touchIndex = Math.max(0, Math.min(n - 1, Math.round((event.getX() - first) / step)));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchIndex = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
