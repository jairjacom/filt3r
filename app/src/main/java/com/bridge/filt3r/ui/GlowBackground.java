package com.bridge.filt3r.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.os.SystemClock;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/**
 * Two soft blobs of the theme colour drifting slowly behind the Status screen (an AGSL shader).
 * It draws only while {@link #setRunning} is true, about 30 frames a second, so it costs nothing
 * when the effect is off or a panel covers it.
 */
public final class GlowBackground extends View {

    private static final String SOURCE =
            "uniform float2 iResolution;\n"
            + "uniform float iTime;\n"
            + "layout(color) uniform half4 iColor;\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float aspect = iResolution.x / iResolution.y;\n"
            + "    float2 uv = float2(fragCoord.x / iResolution.y, fragCoord.y / iResolution.y);\n"
            + "    float2 p1 = float2(aspect * (0.3 + 0.2 * sin(iTime * 0.30)), 0.35 + 0.15 * cos(iTime * 0.23));\n"
            + "    float2 p2 = float2(aspect * (0.7 + 0.2 * cos(iTime * 0.27)), 0.65 + 0.15 * sin(iTime * 0.31));\n"
            + "    float g1 = 1.0 - smoothstep(0.0, 0.6, distance(uv, p1));\n"
            + "    float g2 = 1.0 - smoothstep(0.0, 0.5, distance(uv, p2));\n"
            + "    half a = half(g1 * 0.5 + g2 * 0.4) * 0.22;\n"
            + "    return half4(iColor.rgb * a, a);\n"
            + "}\n";

    private final RuntimeShader shader;
    private final Paint paint = new Paint();
    private boolean running;
    private long start;

    public GlowBackground(Context context) {
        super(context);
        RuntimeShader s = null;
        try {
            s = new RuntimeShader(SOURCE);
            paint.setShader(s);
        } catch (RuntimeException e) {
            s = null; // a shader that does not compile just means no glow
        }
        shader = s;
        setVisibility(GONE);
    }

    /** Starts or stops the animation. Does nothing when the shader could not be built. */
    public void setRunning(boolean on) {
        boolean next = on && shader != null;
        if (next == running) {
            return;
        }
        running = next;
        setVisibility(next ? VISIBLE : GONE);
        if (next) {
            start = SystemClock.uptimeMillis();
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!running) {
            return;
        }
        shader.setFloatUniform("iResolution", getWidth(), getHeight());
        shader.setFloatUniform("iTime", (SystemClock.uptimeMillis() - start) / 1000f);
        shader.setColorUniform("iColor",
                MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        postInvalidateDelayed(33);
    }
}
