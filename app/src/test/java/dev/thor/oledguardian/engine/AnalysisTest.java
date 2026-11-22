package dev.thor.oledguardian.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.Test;

public class AnalysisTest {
    private static final int W = 240, H = 135;   // 1920×1080 / 8

    private static byte[] frame(int seed, boolean moving) {
        byte[] f = new byte[W * H];
        Random r = new Random(seed);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int v = 20;
                if (x < 32 && y < 16) v = 220;                           // HUD oben links, statisch und hell
                else if (moving && x >= 40) v = r.nextInt(256);         // Spielszene bewegt sich
                f[y * W + x] = (byte) v;
            }
        return f;
    }

    @Test public void staticHudIsFoundWhileSceneMoves() {
        StaticMap m = new StaticMap();
        long t = 0;
        assertEquals(-1f, m.feed(frame(0, true), W, H, t, null).motion, 0.0001f);
        StaticMap.Result r = null;
        for (int i = 1; i <= 14; i++) r = m.feed(frame(i, true), W, H, t += 15_000, null);
        assertTrue("Szene gilt als bewegt", r.motion > StaticMap.LIVELY);
        float[] target = m.targets(180_000, 0.5f);
        assertTrue("HUD-Zelle wird abgedunkelt", target[0] > 0.3f);
        assertTrue("HUD-Zelle rechts unten im HUD", target[1 * m.cols() + 3] > 0.3f);
        assertEquals("bewegte Szene bleibt hell", 0f, target[10 * m.cols() + 20], 0.0001f);
        assertEquals("dunkler Hintergrund bleibt", 0f, target[16 * m.cols() + 4], 0.0001f);
    }

    @Test public void downloadScreenHasLittleMotion() {
        StaticMap m = new StaticMap();
        byte[] a = frame(0, false);
        m.feed(a, W, H, 0, null);
        byte[] b = a.clone();
        for (int x = 100; x < 110; x++) b[60 * W + x] = (byte) 200;   // Fortschrittsbalken wächst
        StaticMap.Result r = m.feed(b, W, H, 15_000, null);
        assertTrue(r.motion < StaticMap.LIVELY);
        assertTrue(r.motion > 0f);
    }

    @Test public void ownMaskIsCompensated() {
        StaticMap m = new StaticMap();
        byte[] a = frame(0, false);
        m.feed(a, W, H, 0, null);
        float[] mask = new float[m.cols() * m.rows()];
        for (int i = 0; i < 8; i++) mask[i] = 0.5f;                      // eigene Abdunkelung oben links
        byte[] dimmed = a.clone();
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                float al = m.alphaAt(mask, x, y);
                dimmed[y * W + x] = (byte) Math.round((a[y * W + x] & 0xFF) * (1 - al));
            }
        StaticMap.Result r = m.feed(dimmed, W, H, 15_000, mask);
        assertEquals("Maske erzeugt keine Scheinbewegung", 0f, r.motion, 0.002f);
    }

    @Test public void blackFrameIsDark() {
        StaticMap m = new StaticMap();
        StaticMap.Result r = m.feed(new byte[W * H], W, H, 0, null);
        assertTrue(r.dark);
    }

    @Test public void maskFadesInSlowlyAndOutFast() {
        MaskAnimator a = new MaskAnimator();
        a.resize(4, 2);
        float[] t = new float[8];
        t[0] = 0.5f;
        a.setTargets(t);
        a.step(1_000, 0.5f);
        assertTrue(a.current()[0] > 0f && a.current()[0] < 0.05f);
        for (int i = 0; i < 25; i++) a.step(1_000, 0.5f);
        assertEquals(0.5f, a.current()[0], 0.0001f);
        int[] b = a.bounds();
        assertNotNull(b);
        assertEquals(0, b[0]);
        assertEquals(2, b[2]);
        a.clearTargets();
        a.step(600, 0.5f);
        assertEquals(0f, a.current()[0], 0.0001f);
        assertNull(a.bounds());
        assertFalse(a.visible());
    }

    @Test public void transferMeter() {
        TransferMeter t = new TransferMeter(64_000);
        t.feed(0, 0);
        t.feed(10_000_000, 1_000);
        t.feed(20_000_000, 2_000);
        assertTrue(t.active());
        t.feed(20_000_000, 3_000);
        t.feed(20_000_000, 4_000);
        t.feed(20_000_000, 5_000);
        t.feed(20_000_000, 6_000);
        t.feed(20_000_000, 7_000);
        t.feed(20_000_000, 8_000);
        assertFalse(t.active());
        assertEquals("12,3 MB/s", TransferMeter.format(12_300_000));
    }

    private static final String GETEVENT =
            "add device 1: /dev/input/event5\n" +
            "  name:     \"Thor Gamepad\"\n" +
            "  events:\n" +
            "    KEY (0001): KEY_BACK              BTN_A                 BTN_B                 BTN_X\n" +
            "                BTN_Y                 BTN_TL                BTN_TR                BTN_THUMBL\n" +
            "    ABS (0003): ABS_X                 : value 0, min -32768, max 32767, fuzz 16, flat 128, resolution 0\n" +
            "                ABS_Y                 : value 0, min -32768, max 32767, fuzz 16, flat 128, resolution 0\n" +
            "                ABS_GAS               : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0\n" +
            "                ABS_HAT0X             : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0\n" +
            "  input props:\n" +
            "    <none>\n" +
            "add device 2: /dev/input/event2\n" +
            "  name:     \"fts_ts\"\n" +
            "  events:\n" +
            "    KEY (0001): BTN_TOUCH\n" +
            "    ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0\n" +
            "                ABS_MT_POSITION_X     : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0\n" +
            "  input props:\n" +
            "    INPUT_PROP_DIRECT\n" +
            "add device 3: /dev/input/event7\n" +
            "  name:     \"Gamepad Motion Sensors\"\n" +
            "  events:\n" +
            "    ABS (0003): 0000  : value 12, min -32768, max 32767, fuzz 0, flat 0, resolution 0\n" +
            "  input props:\n" +
            "    INPUT_PROP_ACCELEROMETER\n";

    @Test public void parsesGeteventAndFindsOnlyTheController() {
        List<Gamepads.Device> d = Gamepads.parse(GETEVENT);
        assertEquals(3, d.size());
        assertTrue(d.get(0).gamepad());
        assertEquals("/dev/input/event5", d.get(0).path);
        assertEquals(-32768, d.get(0).axes.get(0x00).min);
        assertTrue(d.get(1).touchscreen());
        assertFalse(d.get(1).gamepad());
        assertTrue(d.get(2).accelerometer);
        assertFalse(d.get(2).gamepad());
    }

    @Test public void stickJitterIsIgnoredRealMovementCounts() {
        Gamepads.Device pad = Gamepads.parse(GETEVENT).get(0);
        Gamepads.AxisFilter f = new Gamepads.AxisFilter(pad.axes);
        assertFalse(f.onAbs(0x00, 120));
        assertFalse("Zittern", f.onAbs(0x00, -300));
        assertFalse(f.onAbs(0x00, 800));
        assertTrue("echte Bewegung", f.onAbs(0x00, 20_000));
        assertTrue("gehalten", f.held());
        assertTrue("Steuerkreuz", f.onAbs(0x10, 1));
        assertFalse(f.onAbs(0x10, 0));
        f.onAbs(0x00, 0);
        assertFalse(f.held());
        f.onAbs(0x09, 0);
        f.onAbs(0x09, 250);
        assertTrue("Gas gehalten", f.held());
    }
}
