package dev.thor.oledguardian.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.thor.oledguardian.engine.GuardEngine.Cause;
import dev.thor.oledguardian.engine.GuardEngine.Mode;
import org.junit.Before;
import org.junit.Test;

public class GuardEngineTest {
    private GuardEngine e;
    private GuardEngine.Config c;
    private GuardEngine.Signals s;
    private long now;

    @Before public void setUp() {
        e = new GuardEngine(2);
        c = new GuardEngine.Config();
        c.idleMs = 120_000;
        c.gameIdleMs = 600_000;
        c.fadeMs = 6_000;
        c.blackAfterMs = 180_000;
        c.unusedMs = new long[]{0, 300_000};
        s = new GuardEngine.Signals(2);
        now = 1_000_000;
        e.reset(now);
    }

    private void run(long ms) {
        long end = now + ms;
        while (now < end) {
            now = Math.min(end, now + 1_000);
            e.update(now, s, c);
        }
    }

    @Test public void downloadWithoutInputDimsBothScreensThenBlack() {
        run(119_000);
        assertEquals(Mode.ACTIVE, e.mode(0));
        run(2_000);
        assertEquals(Mode.FADING, e.mode(0));
        assertEquals(Mode.FADING, e.mode(1));
        assertEquals(Cause.ABSENT, e.cause(0));
        assertTrue(e.holdAwake());
        run(7_000);
        assertEquals(Mode.SAVER, e.mode(0));
        run(181_000);
        assertEquals(Mode.BLACK, e.mode(0));
        assertEquals(Mode.BLACK, e.mode(1));
    }

    @Test public void anyKeyWakesBothAndKeyIsSwallowedOnlyWhenCovered() {
        run(130_000);
        assertEquals(Mode.SAVER, e.mode(0));
        assertTrue(e.shouldSwallowKey());
        s.lastKeyOrStick = now;
        run(1_000);
        assertEquals(Mode.ACTIVE, e.mode(0));
        assertEquals(Mode.ACTIVE, e.mode(1));
        assertFalse(e.shouldSwallowKey());
        assertFalse(e.holdAwake());
    }

    @Test public void videoOrCutsceneKeepsScreenOnButOnlyUpToLimit() {
        c.motionMaxMs = 900_000;
        for (int i = 0; i < 60; i++) {    // 15 min bewegtes Bild, Analyse alle 15 s
            s.lastLively[0] = now;
            run(15_000);
            if (now - 1_000_000 < 890_000) assertEquals("Minute " + i / 4, Mode.ACTIVE, e.mode(0));
        }
        // Grenze erreicht: trotz Bewegung kommt nach weiteren 2 min der Schoner
        for (int i = 0; i < 10; i++) {
            s.lastLively[0] = now;
            run(15_000);
        }
        assertTrue(e.mode(0) != Mode.ACTIVE);
    }

    @Test public void staticBottomScreenWhilePlayingOnTop() {
        // oben wird gespielt (Tasten), unten wird nichts berührt und nichts bewegt sich
        for (int i = 0; i < 301; i++) {
            s.lastKeyOrStick = now;
            run(1_000);
        }
        assertEquals(Mode.ACTIVE, e.mode(0));
        assertTrue(e.mode(1) != Mode.ACTIVE);
        assertEquals(Cause.UNUSED, e.cause(1));
        assertFalse("Spiel-Tasten nicht schlucken", e.shouldSwallowKey());
        assertFalse(e.holdAwake());
        // Tasten wecken den unteren nicht – eine Berührung unten schon
        s.lastKeyOrStick = now;
        run(1_000);
        assertTrue(e.mode(1) != Mode.ACTIVE);
        s.lastTouch[1] = now;
        run(1_000);
        assertEquals(Mode.ACTIVE, e.mode(1));
    }

    @Test public void livelyBottomScreenIsNotTreatedAsUnused() {
        for (int i = 0; i < 400; i++) {
            s.lastKeyOrStick = now;
            if (i % 15 == 0) s.lastLively[1] = now;   // Live-Karte bewegt sich
            run(1_000);
        }
        assertEquals(Mode.ACTIVE, e.mode(1));
    }

    @Test public void gamesGetLongerTimeout() {
        s.gameForeground = true;
        run(300_000);
        assertEquals(Mode.ACTIVE, e.mode(0));
        run(301_000);
        assertTrue(e.mode(0) != Mode.ACTIVE);
    }

    @Test public void excludedAppAndDisabledPauseEverything() {
        s.excluded = true;
        run(1_000_000);
        assertEquals(Mode.ACTIVE, e.mode(0));
        assertEquals(Mode.ACTIVE, e.mode(1));
        s.excluded = false;
        c.enabled = false;
        run(1_000_000);
        assertEquals(Mode.ACTIVE, e.mode(0));
    }

    @Test public void manualBlackoutIgnoresInputsFromTheTileTapItself() {
        e.startManual(now);
        s.lastTouch[0] = now + 500;          // Finger hebt sich von der Kachel
        run(1_000);
        assertTrue(e.mode(0) != Mode.ACTIVE);
        run(2_000);
        assertTrue(e.holdAwake());
        s.lastTouch[1] = now;                 // echte Berührung später
        run(1_000);
        assertEquals(Mode.ACTIVE, e.mode(0));
        assertEquals(Mode.ACTIVE, e.mode(1));
    }

    @Test public void displayTurnedOffWithAynButtonIsIgnored() {
        s.displayOn[1] = false;
        run(130_000);
        assertTrue(e.mode(0) != Mode.ACTIVE);
        assertEquals(Mode.ACTIVE, e.mode(1));
    }

    @Test public void sleepsOnceAfterTransferEnds() {
        c.sleepAfterTransfer = true;
        c.transferQuietMs = 120_000;
        s.transferActive = true;
        run(400_000);
        assertFalse(e.takeSleepRequest());
        s.transferActive = false;
        run(119_000);
        assertFalse(e.takeSleepRequest());
        run(2_000);
        assertTrue(e.takeSleepRequest());
        run(600_000);
        assertFalse("nur einmal je Abwesenheit", e.takeSleepRequest());
    }

    @Test public void noSleepWithoutTransferUnlessTimeLimit() {
        c.sleepAfterTransfer = true;
        run(3_600_000);
        assertFalse(e.takeSleepRequest());
        c.sleepAfterMs = 1_800_000;
        e.reset(now);
        run(1_801_000);
        assertTrue(e.takeSleepRequest());
    }

    @Test public void unusedBecomesAbsentSoAnyKeyWakesLater() {
        run(301_000);   // nach 120 s Abwesenheit sind ohnehin beide geschont
        assertEquals(Cause.ABSENT, e.cause(1));
        s.lastKeyOrStick = now;
        run(1_000);
        assertEquals(Mode.ACTIVE, e.mode(1));
    }

    @Test public void screenOffResetsAndRestartsTimer() {
        run(130_000);
        s.interactive = false;
        run(1_000);
        assertEquals(Mode.ACTIVE, e.mode(0));
        s.interactive = true;
        run(60_000);
        assertEquals(Mode.ACTIVE, e.mode(0));
    }

    @Test public void blackImmediatelyWhenConfigured() {
        c.blackAfterMs = 0;
        run(128_000);
        assertEquals(Mode.BLACK, e.mode(0));
    }
}
