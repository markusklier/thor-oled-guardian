package dev.thor.oledguardian.engine;

/**
 * Bildanalyse eines Bildschirms aus stark verkleinerten Helligkeitswerten (ein Wert je {@link #STEP}×{@link #STEP}
 * Pixel). Liefert zwei Dinge:
 *  - Bewegung: welcher Anteil des Bildes sich seit der letzten Analyse verändert hat (Video vs. Download-Fenster).
 *  - Einbrenn-Karte: Zellen ({@link #CELL}×{@link #CELL} Werte), die hell sind und sich lange nicht verändert
 *    haben (HUD, Statusanzeigen, Legenden). Für sie schlägt die Karte eine Abdunkelung vor.
 *
 * Die eigene Abdunkelung ist im Bildschirmfoto mit drin. {@link #feed} rechnet sie heraus: gemessener Wert / (1 − Deckkraft),
 * mit derselben bilinearen Verteilung, mit der die Maske gezeichnet wird.
 */
public final class StaticMap {
    /** Pixel je Messwert (der Dienst verkleinert das Bildschirmfoto um diesen Faktor). */
    public static final int STEP = 8;
    /** Messwerte je Zelle in jeder Richtung → eine Zelle = 64×64 Pixel. */
    public static final int CELL = 8;
    /** Ab dieser Helligkeit (0–255) gilt eine Zelle als einbrenngefährdet. */
    public static final int BRIGHT = 40;
    /** Ab diesem Anteil veränderter Messwerte gilt der Bildschirm als bewegt (Video, Zwischensequenz). */
    public static final float LIVELY = 0.12f;

    public static final class Result {
        /** Anteil veränderter Messwerte 0..1, oder −1 beim ersten Bild / nach Größenwechsel. */
        public float motion = -1f;
        /** Fast alles schwarz: Bildschirm leer oder geschützter Inhalt (DRM-Video) – Bewegung unbekannt. */
        public boolean dark;
        public int brightStaticCells;
    }

    private int sw, sh, cols, rows;
    private int[] prev;
    private long prevAt;
    private long[] staticMs;
    private int[] cellMax;
    private boolean[] cellChanged;

    public int cols() { return cols; }
    public int rows() { return rows; }

    /** Wie lange Zelle i schon unverändert ist (ms). */
    public long staticMs(int i) { return staticMs == null ? 0 : staticMs[i]; }
    public int cellMax(int i) { return cellMax == null ? 0 : cellMax[i]; }
    public boolean changedLast(int i) { return cellChanged != null && cellChanged[i]; }
    public boolean ready() { return prev != null; }

    public void clear() { prev = null; staticMs = null; cellMax = null; cellChanged = null; }

    /**
     * @param luma   Helligkeit je Messwert, Zeilen von oben nach unten, Länge w*h
     * @param maskAlpha aktuelle Deckkraft der eigenen Abdunkelung je Zelle (oder null)
     */
    public Result feed(byte[] luma, int w, int h, long now, float[] maskAlpha) {
        Result r = new Result();
        if (luma == null || w <= 0 || h <= 0 || luma.length < w * h) return r;
        if (w != sw || h != sh || prev == null) {
            sw = w;
            sh = h;
            cols = (w + CELL - 1) / CELL;
            rows = (h + CELL - 1) / CELL;
            prev = new int[w * h];
            staticMs = new long[cols * rows];
            cellMax = new int[cols * rows];
            cellChanged = new boolean[cols * rows];
            fill(luma, maskAlpha, prev);
            computeMax(prev);
            prevAt = now;
            r.dark = isDark(prev);
            return r;
        }
        int[] cur = new int[w * h];
        fill(luma, maskAlpha, cur);
        long dt = Math.max(0, now - prevAt);
        int changed = 0;
        java.util.Arrays.fill(cellChanged, false);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int cy = y / CELL;
            for (int x = 0; x < w; x++) {
                int a = prev[row + x], b = cur[row + x];
                int tol = 10 + (a * 8) / 100;
                if (Math.abs(a - b) > tol) {
                    changed++;
                    cellChanged[cy * cols + x / CELL] = true;
                }
            }
        }
        float motion = changed / (float) (w * h);
        // Jede Zelle für sich: eine bewegte Spielszene setzt nur ihre eigenen Zellen zurück, das HUD daneben nicht.
        for (int i = 0; i < staticMs.length; i++) {
            if (cellChanged[i]) staticMs[i] = 0;
            else staticMs[i] += dt;
        }
        prev = cur;
        prevAt = now;
        computeMax(cur);
        r.motion = motion;
        r.dark = isDark(cur);
        int bs = 0;
        for (int i = 0; i < staticMs.length; i++) if (cellMax[i] >= BRIGHT && staticMs[i] > 0) bs++;
        r.brightStaticCells = bs;
        return r;
    }

    /** Vorgeschlagene Deckkraft je Zelle: hell + seit {@code afterMs} unverändert → {@code strength}. */
    public float[] targets(long afterMs, float strength) {
        float[] t = new float[cols * rows];
        if (staticMs == null) return t;
        for (int i = 0; i < t.length; i++) {
            if (cellMax[i] >= BRIGHT && staticMs[i] >= afterMs) {
                // Sehr helle Zellen etwas stärker, mittelhelle etwas schwächer – nie über strength.
                float k = 0.7f + 0.3f * Math.min(1f, (cellMax[i] - BRIGHT) / 120f);
                t[i] = strength * k;
            }
        }
        return t;
    }

    private void fill(byte[] luma, float[] mask, int[] out) {
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int v = luma[y * sw + x] & 0xFF;
                if (mask != null && mask.length == cols * rows) {
                    float a = alphaAt(mask, x, y);
                    if (a > 0.003f) v = Math.min(255, Math.round(v / (1f - Math.min(a, 0.9f))));
                }
                out[y * sw + x] = v;
            }
        }
    }

    /** Bilineare Deckkraft am Messwert (x, y) – wie die skaliert gezeichnete Masken-Bitmap (Texelmitte = Zellmitte). */
    public float alphaAt(float[] mask, int x, int y) {
        float u = (x + 0.5f) / CELL - 0.5f;
        float v = (y + 0.5f) / CELL - 0.5f;
        int x0 = (int) Math.floor(u), y0 = (int) Math.floor(v);
        float fx = u - x0, fy = v - y0;
        float a00 = cell(mask, x0, y0), a10 = cell(mask, x0 + 1, y0);
        float a01 = cell(mask, x0, y0 + 1), a11 = cell(mask, x0 + 1, y0 + 1);
        return (a00 * (1 - fx) + a10 * fx) * (1 - fy) + (a01 * (1 - fx) + a11 * fx) * fy;
    }

    private float cell(float[] mask, int cx, int cy) {
        cx = Math.max(0, Math.min(cols - 1, cx));
        cy = Math.max(0, Math.min(rows - 1, cy));
        return mask[cy * cols + cx];
    }

    private void computeMax(int[] img) {
        java.util.Arrays.fill(cellMax, 0);
        for (int y = 0; y < sh; y++) {
            int cy = y / CELL;
            for (int x = 0; x < sw; x++) {
                int i = cy * cols + x / CELL;
                int v = img[y * sw + x];
                if (v > cellMax[i]) cellMax[i] = v;
            }
        }
    }

    private boolean isDark(int[] img) {
        int bright = 0;
        for (int v : img) if (v > 12) bright++;
        return bright < img.length / 50;
    }
}
