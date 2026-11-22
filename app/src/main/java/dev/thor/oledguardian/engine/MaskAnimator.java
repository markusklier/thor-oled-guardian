package dev.thor.oledguardian.engine;

/**
 * Weiche Abdunkelung statischer Zellen: einblenden sehr langsam (man soll es beim Spielen nicht bemerken),
 * ausblenden schnell (wenn sich dort wieder etwas bewegt).
 */
public final class MaskAnimator {
    private float[] cur = new float[0];
    private float[] target = new float[0];
    private int cols, rows;
    /** Volle Stärke nach so vielen ms. */
    public long fadeInMs = 20_000;
    public long fadeOutMs = 600;

    public void resize(int cols, int rows) {
        if (cols == this.cols && rows == this.rows) return;
        this.cols = cols;
        this.rows = rows;
        cur = new float[cols * rows];
        target = new float[cols * rows];
    }

    public int cols() { return cols; }
    public int rows() { return rows; }
    public float[] current() { return cur; }

    public void setTargets(float[] t) {
        if (t == null || t.length != target.length) return;
        System.arraycopy(t, 0, target, 0, t.length);
    }

    public void clearTargets() { java.util.Arrays.fill(target, 0f); }

    /** Sofort alles aus (z. B. Szenenwechsel, Schoner übernimmt). */
    public void hideNow() {
        java.util.Arrays.fill(cur, 0f);
        java.util.Arrays.fill(target, 0f);
    }

    /** @return true, wenn sich etwas verändert hat (neu zeichnen). */
    public boolean step(long dtMs, float strength) {
        boolean moved = false;
        float up = strength * dtMs / (float) Math.max(1, fadeInMs);
        float down = Math.max(strength, 0.3f) * dtMs / (float) Math.max(1, fadeOutMs);
        for (int i = 0; i < cur.length; i++) {
            float c = cur[i], t = target[i];
            if (c < t) c = Math.min(t, c + up);
            else if (c > t) c = Math.max(t, c - down);
            if (c != cur[i]) {
                cur[i] = c;
                moved = true;
            }
        }
        return moved;
    }

    public boolean animating() {
        for (int i = 0; i < cur.length; i++) if (cur[i] != target[i]) return true;
        return false;
    }

    public boolean visible() {
        for (float c : cur) if (c > 0.004f) return true;
        return false;
    }

    /** Umgebendes Rechteck der sichtbaren Zellen, um eine Zelle erweitert: {x0, y0, x1, y1} (exklusiv) oder null. */
    public int[] bounds() {
        int x0 = cols, y0 = rows, x1 = -1, y1 = -1;
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++)
                if (cur[y * cols + x] > 0.004f || target[y * cols + x] > 0.004f) {
                    x0 = Math.min(x0, x); y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x); y1 = Math.max(y1, y);
                }
        if (x1 < 0) return null;
        return new int[]{Math.max(0, x0 - 1), Math.max(0, y0 - 1), Math.min(cols, x1 + 2), Math.min(rows, y1 + 2)};
    }
}
