package dev.thor.oledguardian;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.View;

import java.nio.ByteBuffer;

/**
 * Spielschutz: dunkelt nur die Bereiche ab, die hell sind und sich lange nicht verändert haben (HUD, Legenden,
 * Statusanzeigen) – ähnlich der Logo-Abdunkelung von OLED-Fernsehern. Die Zellen sind eine winzige Bitmap, die
 * weich (bilinear) auf Bildschirmgröße gezogen wird; dadurch gibt es keine harten Kanten.
 * Das Fenster lässt jede Berührung durch und deckt nur das Rechteck um die abgedunkelten Zellen ab.
 */
@SuppressLint("ViewConstructor")
final class MaskView extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private Bitmap bitmap;
    private ByteBuffer buffer;
    private int cols, rows;
    private float cellPx = 64;
    private int offsetX, offsetY;

    MaskView(Context c) {
        super(c);
        paint.setColor(Color.BLACK);
        paint.setFilterBitmap(true);
    }

    /** @param offsetX/offsetY Fensterposition auf dem Bildschirm in Pixeln */
    void update(float[] alpha, int cols, int rows, float cellPx, int offsetX, int offsetY) {
        if (bitmap == null || cols != this.cols || rows != this.rows) {
            bitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ALPHA_8);
            buffer = ByteBuffer.allocate(bitmap.getRowBytes() * rows);
            this.cols = cols;
            this.rows = rows;
        }
        int stride = bitmap.getRowBytes();
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++)
                buffer.put(y * stride + x, (byte) Math.round(255 * Math.max(0, Math.min(0.9f, alpha[y * cols + x]))));
        buffer.rewind();
        bitmap.copyPixelsFromBuffer(buffer);
        buffer.rewind();
        this.cellPx = cellPx;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (bitmap == null) return;
        matrix.setScale(cellPx, cellPx);
        matrix.postTranslate(-offsetX, -offsetY);
        canvas.drawBitmap(bitmap, matrix, paint);
    }
}
