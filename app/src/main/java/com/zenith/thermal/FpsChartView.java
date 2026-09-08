package com.zenith.thermal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/* Chart view for benchmark FPS line graph.
 * Separated from BenchmarkResultsActivity so it can be referenced from XML. */
class FpsChartView extends View {

    private static final int ACCENT = Color.rgb(0x5E, 0xA7, 0xFF);
    private static final int TEXT   = Color.rgb(0xF2, 0xF5, 0xF6);
    private static final int MUTED  = Color.rgb(0xB8, 0xC6, 0xCA);

    private final Paint linePaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<FpsSample> data      = new ArrayList<>();

    public FpsChartView(Context ctx) {
        super(ctx);
        init();
    }

    public FpsChartView(Context ctx, android.util.AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f * d);
        linePaint.setColor(ACCENT);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(ACCENT);
        textPaint.setColor(TEXT);
        textPaint.setTextSize(12f * d);
        textPaint.setTextAlign(Paint.Align.LEFT);
        gridPaint.setColor(0x30FFFFFF);
        gridPaint.setStrokeWidth(1f);
        gridTextPaint.setColor(MUTED);
        gridTextPaint.setTextSize(10f * d);
        gridTextPaint.setTextAlign(Paint.Align.RIGHT);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setData(List<FpsSample> samples) {
        data = new ArrayList<>(samples);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float d = getResources().getDisplayMetrics().density;
        float padL = 38 * d, padR = 10 * d, padT = 8 * d, padB = 20 * d;
        float w = getWidth() - padL - padR;
        float h = getHeight() - padT - padB;

        if (data.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText("Start benchmark to see FPS chart",
                    getWidth() / 2f, getHeight() / 2f, textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        double maxFps = 0;
        for (FpsSample s : data) if (s.fps > maxFps) maxFps = s.fps;
        maxFps = Math.max(maxFps, 60) * 1.1;
        if (maxFps < 1) maxFps = 1;

        // Grid lines
        for (int i = 1; i <= 4; i++) {
            float y = padT + h * (1f - i / 4f);
            c.drawLine(padL, y, padL + w, y, gridPaint);
            c.drawText(String.valueOf((int) Math.round(maxFps * i / 4)),
                    padL - 4 * d, y + 4 * d, gridTextPaint);
        }

        int n = data.size();
        if (n == 1) {
            float x = padL + w / 2f;
            float y = padT + h * (float)(1 - data.get(0).fps / maxFps);
            c.drawCircle(x, y, 5 * d, dotPaint);
            return;
        }

        // FPS line + area fill
        android.graphics.Path path     = new android.graphics.Path();
        android.graphics.Path fillPath = new android.graphics.Path();
        for (int i = 0; i < n; i++) {
            float x = padL + w * i / (n - 1);
            float y = padT + h * (float)(1 - data.get(i).fps / maxFps);
            if (i == 0) { path.moveTo(x, y); fillPath.moveTo(x, y); }
            else        { path.lineTo(x, y); fillPath.lineTo(x, y); }
        }
        fillPath.lineTo(padL + w, padT + h);
        fillPath.lineTo(padL, padT + h);
        fillPath.close();
        fillPaint.setColor(0x405EA7FF);
        c.drawPath(fillPath, fillPaint);
        c.drawPath(path, linePaint);

        // Latest dot + label
        float lx = padL + w;
        float ly = padT + h * (float)(1 - data.get(n - 1).fps / maxFps);
        c.drawCircle(lx, ly, 4 * d, dotPaint);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(String.format(Locale.US, "%.1f", data.get(n - 1).fps),
                lx - 6 * d, ly - 6 * d, textPaint);

        // Bottom point count
        textPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(String.format(Locale.US, "%d points", n),
                padL, getHeight() - 2, textPaint);
    }
}

class FpsSample {
    final long tsSec;
    final double fps;
    FpsSample(long tsSec, double fps) { this.tsSec = tsSec; this.fps = fps; }
}
