package com.bridge.filt3r.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/** A tiny line graph of the last 60 samples, scaled to the biggest one. */
public final class Sparkline extends View {

    private static final int COUNT = 60;

    private final float[] samples = new float[COUNT];
    private int size;
    private int next;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final float density;

    public Sparkline(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        int color = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * density);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        fill.setAlpha(0x22);
    }

    public void add(float v) {
        samples[next] = v;
        next = (next + 1) % COUNT;
        if (size < COUNT) {
            size++;
        }
        invalidate();
    }

    public void clear() {
        size = 0;
        next = 0;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(getDefaultSize(0, widthSpec), (int) (40 * density + 0.5f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (size < 2) {
            return;
        }
        float pad = density; // keep the stroke inside the view
        float w = getWidth();
        float h = getHeight() - 2 * pad;
        float max = 1;
        for (int i = 0; i < size; i++) {
            max = Math.max(max, samples[i]);
        }
        // Oldest sample sits at "next" once the ring is full, otherwise at 0.
        int start = size < COUNT ? 0 : next;
        linePath.reset();
        fillPath.reset();
        float step = w / (COUNT - 1);
        // Newest sample at the right edge.
        float x0 = w - (size - 1) * step;
        float lastX = x0;
        for (int i = 0; i < size; i++) {
            float v = samples[(start + i) % COUNT];
            float x = x0 + i * step;
            float y = pad + h - h * v / max;
            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, pad + h);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
            lastX = x;
        }
        fillPath.lineTo(lastX, pad + h);
        fillPath.close();
        canvas.drawPath(fillPath, fill);
        canvas.drawPath(linePath, line);
    }
}
