package dev.thor.oledguardian;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Der Schoner eines Bildschirms. Zeichnet selbst den Übergang (aus der Startzeit berechnet, damit er auch bei
 * seltener Aktualisierung flüssig bleibt) und hält im Schwarz-Zustand still – nur die kleine Info wandert einmal
 * pro Minute weiter, damit auch sie nirgends einbrennt.
 */
@SuppressLint("ViewConstructor")
final class SaverView extends View {
    interface Listener { void onTouched(); }

    private final Listener listener;
    private final Random random = new Random();
    private final Paint fill = new Paint();
    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.GERMANY);
    private final SimpleDateFormat date = new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY);

    private String style = GuardPrefs.STYLE_AURORA;
    private long fadeStart, fadeMs = 6_000;
    private boolean black;
    private boolean showInfo;
    private String transfer = "";
    private long shownAt;
    private float infoX = 0.5f, infoY = 0.8f;
    private long infoMovedAt;
    private RadialGradient[] blobs;
    private int blobsW, blobsH;
    private LinearGradient sheen;

    private static final int[] AURORA = {0xFF0F5A52, 0xFF2E1A66, 0xFF0D3470, 0xFF4A1450};
    private static final float[][] PATH = {{0.21f, 0.34f, 47f, 61f}, {0.73f, 0.28f, 71f, 53f},
            {0.42f, 0.71f, 59f, 83f}, {0.82f, 0.77f, 89f, 67f}};

    SaverView(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        fill.setColor(Color.BLACK);
        blob.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        text.setColor(0xFF4C4C4C);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        clock.setColor(0xFF5A5A5A);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        shownAt = SystemClock.uptimeMillis();
    }

    /** @param fadeStart Beginn des Übergangs (uptime); fadeMs = 0 → sofort voll */
    void configure(String style, long fadeStart, long fadeMs, boolean black, boolean showInfo, String transfer) {
        boolean changed = !style.equals(this.style) || this.black != black || this.fadeStart != fadeStart
                || this.showInfo != showInfo || !transfer.equals(this.transfer);
        this.style = style;
        this.fadeStart = fadeStart;
        this.fadeMs = Math.max(1, fadeMs);
        this.black = black;
        this.showInfo = showInfo;
        this.transfer = transfer;
        if (changed) invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && listener != null) listener.onTouched();
        return true;   // erste Berührung gehört dem Schoner, nicht der App darunter
    }

    private float progress(long now) {
        float p = (now - fadeStart) / (float) fadeMs;
        p = p < 0 ? 0 : Math.min(1, p);
        return p * p * (3 - 2 * p);   // weich ein- und auslaufen
    }

    @Override protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        int w = getWidth(), h = getHeight();
        float p = progress(now);
        boolean dim = GuardPrefs.UNUSED_DIM.equals(style);
        boolean animate = p < 1f;

        if (dim) {
            // Unbenutzter Bildschirm: nur gedämpft, bleibt bedienbar
            fill.setAlpha((int) (255 * p * (black ? 0.82f : 0.62f)));
            canvas.drawRect(0, 0, w, h, fill);
        } else if (black || GuardPrefs.STYLE_BLACK.equals(style)) {
            fill.setAlpha((int) (255 * p));
            canvas.drawRect(0, 0, w, h, fill);
        } else if (GuardPrefs.STYLE_GLASS.equals(style)) {
            fill.setAlpha((int) (255 * p * 0.86f));
            canvas.drawRect(0, 0, w, h, fill);
            drawSheen(canvas, w, h, now, p);
            animate = true;
        } else {
            fill.setAlpha((int) (255 * p));
            canvas.drawRect(0, 0, w, h, fill);
            if (GuardPrefs.STYLE_AURORA.equals(style)) {
                drawAurora(canvas, w, h, now, p);
                animate = true;
            }
        }

        boolean clockStyle = GuardPrefs.STYLE_CLOCK.equals(style) && !black;
        if ((showInfo || clockStyle) && !dim && p > 0.5f) drawInfo(canvas, w, h, now, clockStyle, p);
        if (!dim && p >= 1f && now - shownAt < 5_000 && now - fadeStart < fadeMs + 5_000) {
            drawHint(canvas, w, h, now);
            animate = true;
        }

        if (animate) postInvalidateDelayed(p < 1f ? 33 : 66);       // Übergang 30 fps, Animation 15 fps
        else postInvalidateDelayed(Math.max(1_000, 60_000 - (now - infoMovedAt)));  // nur die Info weiterschieben
    }

    private void drawAurora(Canvas c, int w, int h, long now, float p) {
        if (blobs == null || blobsW != w || blobsH != h) {
            blobs = new RadialGradient[AURORA.length];
            float r = Math.max(w, h) * 0.55f;
            for (int i = 0; i < AURORA.length; i++)
                blobs[i] = new RadialGradient(0, 0, r, AURORA[i], 0x00000000, Shader.TileMode.CLAMP);
            blobsW = w;
            blobsH = h;
        }
        float t = now / 1000f;
        blob.setAlpha((int) (255 * p));
        for (int i = 0; i < blobs.length; i++) {
            float[] q = PATH[i];
            float x = w * (q[0] + 0.22f * (float) Math.sin(t * 2 * Math.PI / q[2] + i));
            float y = h * (q[1] + 0.20f * (float) Math.cos(t * 2 * Math.PI / q[3] + i * 1.7f));
            float s = 0.85f + 0.25f * (float) Math.sin(t * 2 * Math.PI / (q[2] + q[3]) + i);
            matrix.setScale(s, s * 0.75f);
            matrix.postTranslate(x, y);
            blobs[i].setLocalMatrix(matrix);
            blob.setShader(blobs[i]);
            c.drawRect(0, 0, w, h, blob);
        }
        blob.setShader(null);
    }

    private void drawSheen(Canvas c, int w, int h, long now, float p) {
        if (sheen == null || blobsW != w) {
            sheen = new LinearGradient(0, 0, w * 0.35f, h * 0.35f,
                    new int[]{0x00FFFFFF, 0x0EFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
            blobsW = w;
        }
        float t = (now % 24_000) / 24_000f;
        matrix.setTranslate(-w * 0.4f + t * w * 1.6f, -h * 0.4f + t * h * 1.6f);
        sheen.setLocalMatrix(matrix);
        blob.setShader(sheen);
        blob.setAlpha((int) (255 * p));
        c.drawRect(0, 0, w, h, blob);
        blob.setShader(null);
    }

    private void drawInfo(Canvas c, int w, int h, long now, boolean big, float p) {
        if (now - infoMovedAt >= 60_000) {
            infoX = 0.2f + random.nextFloat() * 0.6f;
            infoY = 0.25f + random.nextFloat() * 0.55f;
            infoMovedAt = now;
        }
        int alpha = (int) (255 * Math.min(1f, (p - 0.5f) * 2));
        Date d = new Date();
        float scale = getResources().getDisplayMetrics().density;
        if (big) {
            clock.setTextSize(64 * scale);
            clock.setAlpha(alpha);
            float cw = clock.measureText(hhmm.format(d));
            float x = Math.max(16 * scale, Math.min(w - cw - 16 * scale, infoX * w - cw / 2));
            float y = Math.max(80 * scale, Math.min(h - 60 * scale, infoY * h));
            c.drawText(hhmm.format(d), x, y, clock);
            text.setTextSize(15 * scale);
            text.setAlpha(alpha);
            c.drawText(date.format(d), x + 4 * scale, y + 30 * scale, text);
            if (!transfer.isEmpty()) c.drawText(transfer, x + 4 * scale, y + 54 * scale, text);
        } else {
            String line = hhmm.format(d) + (transfer.isEmpty() ? "" : "  ·  " + transfer);
            text.setTextSize(15 * scale);
            text.setAlpha(alpha);
            float tw = text.measureText(line);
            float x = Math.max(16 * scale, Math.min(w - tw - 16 * scale, infoX * w - tw / 2));
            float y = Math.max(40 * scale, Math.min(h - 24 * scale, infoY * h));
            c.drawText(line, x, y, text);
        }
    }

    private void drawHint(Canvas c, int w, int h, long now) {
        float scale = getResources().getDisplayMetrics().density;
        long age = now - Math.max(shownAt, fadeStart + fadeMs);
        float a = age < 0 ? 0 : (age < 3_500 ? 1f : Math.max(0, 1 - (age - 3_500) / 1_500f));
        text.setTextSize(14 * scale);
        text.setAlpha((int) (200 * a));
        String hint = "Berühren oder Taste drücken zum Aufwecken";
        c.drawText(hint, (w - text.measureText(hint)) / 2, h - 36 * scale, text);
    }

    void markShown() { shownAt = SystemClock.uptimeMillis(); }
}
