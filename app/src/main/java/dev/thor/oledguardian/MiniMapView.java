package dev.thor.oledguardian;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import dev.thor.oledguardian.engine.MaskAnimator;
import dev.thor.oledguardian.engine.StaticMap;

/** Kleine Karte der Bildanalyse eines Bildschirms in den Einstellungen – zum Ausprobieren auf dem Gerät. */
final class MiniMapView extends View {
    static final int DARK = 0xFF15191F, CALM = 0xFF3A4350, MOVING = 0xFF2FA597, STATIC = 0xFFE0A84B, DIMMED = 0xFFE0564B;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private StaticMap map;
    private MaskAnimator anim;
    private float aspect = 16f / 9f;

    MiniMapView(Context c) { super(c); }

    void set(StaticMap map, MaskAnimator anim, int widthPx, int heightPx) {
        this.map = map;
        this.anim = anim;
        if (widthPx > 0 && heightPx > 0) aspect = widthPx / (float) heightPx;
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int height = Math.round(width / aspect);
        setMeasuredDimension(width, Math.min(height, Math.round(240 * getResources().getDisplayMetrics().density)));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        paint.setColor(0xFF0B0E12);
        r.set(0, 0, w, h);
        c.drawRoundRect(r, 12, 12, paint);
        if (map == null || !map.ready()) return;
        int cols = map.cols(), rows = map.rows();
        float cw = w / cols, ch = h / rows;
        float[] mask = anim != null && anim.cols() == cols ? anim.current() : null;
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++) {
                int i = y * cols + x;
                int color;
                if (mask != null && mask[i] > 0.02f) color = DIMMED;
                else if (map.changedLast(i)) color = MOVING;
                else if (map.cellMax(i) < StaticMap.BRIGHT) color = DARK;
                else if (map.staticMs(i) >= 30_000) color = STATIC;
                else color = CALM;
                paint.setColor(color);
                r.set(x * cw + 1, y * ch + 1, (x + 1) * cw - 1, (y + 1) * ch - 1);
                c.drawRect(r, paint);
            }
    }
}
