package dev.thor.oledguardian.engine;

/**
 * Entscheidet je Bildschirm, ob er normal läuft, gerade abblendet, den Schoner zeigt oder schwarz ist.
 * Reine Logik ohne Android-Klassen, damit sie auf dem Desktop testbar ist (GuardEngineTest).
 *
 * Zwei Auslöser:
 *  - ABWESENHEIT: niemand hat irgendetwas bedient (Taste, Stick, Berührung auf irgendeinem Bildschirm).
 *    Ein laufendes Video (Medienwiedergabe außerhalb von Spielen) verschiebt den Start, höchstens bis {@link Config#motionMaxMs}.
 *  - UNBENUTZT: ein einzelner Bildschirm wurde länger nicht berührt (z. B. der untere, während oben gespielt
 *    wird). Nur eine Berührung dieses Bildschirms weckt ihn.
 *
 * Zeiten sind Millisekunden einer monotonen Uhr (SystemClock.uptimeMillis). {@link #NEVER} = nie passiert.
 */
public final class GuardEngine {
    public static final long NEVER = Long.MIN_VALUE / 4;

    public enum Mode { ACTIVE, FADING, SAVER, BLACK }
    public enum Cause { NONE, ABSENT, UNUSED, MANUAL }

    public static final class Config {
        public boolean enabled = true;
        /** Abwesenheit außerhalb von Spielen; 0 = aus. */
        public long idleMs = 120_000;
        /** Abwesenheit, während ein Spiel/Emulator im Vordergrund ist; 0 = nie. */
        public long gameIdleMs = 600_000;
        /** Laufende Videos halten den Schoner höchstens so lange auf; 0 = nicht berücksichtigen. */
        public long motionMaxMs = 1_800_000;
        /** Übergang vom normalen Bild zum Schoner. */
        public long fadeMs = 6_000;
        /** Schoner → komplett schwarz; 0 = sofort schwarz, &lt;0 = nie. */
        public long blackAfterMs = 180_000;
        /** Je Bildschirm (0 = Hauptbildschirm, 1 = zweiter): unbenutzt nach; 0 = aus. */
        public long[] unusedMs = {0, 300_000};
        /** Controller-Eingaben zählen auch als Nutzung des zweiten Bildschirms. */
        public boolean keysCountForSecond = false;
        /** Nach dem Ende einer Übertragung schlafen legen (nur bei Abwesenheit). */
        public boolean sleepAfterTransfer = false;
        /** Spätestens so lange nach Beginn der Abwesenheit schlafen; 0 = nie. */
        public long sleepAfterMs = 0;
        /** So lange ohne Übertragung gilt sie als beendet. */
        public long transferQuietMs = 120_000;
        /** Eingaben direkt nach einem manuellen Start (Kachel) zählen nicht als Aufwecken. */
        public long manualGraceMs = 1_500;
    }

    /** Was der Dienst gerade über das Gerät weiß. */
    public static final class Signals {
        public long lastKeyOrStick = NEVER;
        public long[] lastTouch;
        /** Zuletzt gesehen, dass auf diesem Bildschirm ein Video läuft. */
        public long[] lastLively;
        /** Bildschirm ist eingeschaltet (nicht über den AYN-Knopf abgeschaltet). */
        public boolean[] displayOn;
        public boolean gameForeground;
        public boolean excluded;
        /** Android-Bildschirm an und entsperrt. */
        public boolean interactive = true;
        public boolean transferActive;

        public Signals(int displays) {
            lastTouch = filled(displays);
            lastLively = filled(displays);
            displayOn = new boolean[displays];
            for (int i = 0; i < displays; i++) displayOn[i] = true;
        }

        private static long[] filled(int n) {
            long[] a = new long[n];
            for (int i = 0; i < n; i++) a[i] = NEVER;
            return a;
        }
    }

    private final int count;
    private final Mode[] mode;
    private final Cause[] cause;
    private final long[] since;
    private final long[] wokeAt;
    private long absentWokeAt = NEVER;
    private long manualAt = NEVER;
    private boolean sleptThisPeriod;
    private boolean transferSeen;
    private long lastTransfer = NEVER;
    private boolean sleepRequested;

    public GuardEngine(int displays) {
        count = displays;
        mode = new Mode[displays];
        cause = new Cause[displays];
        since = new long[displays];
        wokeAt = new long[displays];
        for (int i = 0; i < displays; i++) {
            mode[i] = Mode.ACTIVE;
            cause[i] = Cause.NONE;
            since[i] = NEVER;
            wokeAt[i] = NEVER;
        }
    }

    public int count() { return count; }
    public Mode mode(int d) { return mode[d]; }
    public Cause cause(int d) { return cause[d]; }
    public long since(int d) { return since[d]; }

    /** Wurde in diesem Abwesenheits-Zeitraum Schlafen ausgelöst? Der Dienst führt es einmal aus. */
    public boolean takeSleepRequest() {
        boolean r = sleepRequested;
        sleepRequested = false;
        return r;
    }

    /** Fortschritt des Übergangs 0..1 (1 = Schoner voll sichtbar). */
    public float fadeProgress(int d, long now, Config c) {
        if (mode[d] == Mode.ACTIVE) return 0f;
        if (mode[d] != Mode.FADING) return 1f;
        long fade = cause[d] == Cause.MANUAL ? 700 : Math.max(1, c.fadeMs);
        return clamp01((now - since[d]) / (float) fade);
    }

    /** Hält Android wach, solange ein Bildschirm wegen Abwesenheit oder manuell geschont wird. */
    public boolean holdAwake() {
        for (int d = 0; d < count; d++)
            if (mode[d] != Mode.ACTIVE && (cause[d] == Cause.ABSENT || cause[d] == Cause.MANUAL)) return true;
        return false;
    }

    /** Erste Taste nur zum Aufwecken verwenden, wenn der Hauptbildschirm wirklich verdeckt ist. */
    public boolean shouldSwallowKey() {
        return (mode[0] == Mode.SAVER || mode[0] == Mode.BLACK) && cause[0] != Cause.UNUSED;
    }

    /** Kachel „Jetzt schonen“ oder Test: alle eingeschalteten Bildschirme sofort schonen. */
    public void startManual(long now) {
        manualAt = now;
        for (int d = 0; d < count; d++) {
            mode[d] = Mode.FADING;
            cause[d] = Cause.MANUAL;
            since[d] = now;
        }
        sleptThisPeriod = false;
        transferSeen = false;
    }

    /** Alles zurück auf normal (Bildschirm aus, Einstellungen geändert …). */
    public void reset(long now) {
        for (int d = 0; d < count; d++) wake(d, now);
        absentWokeAt = now;
    }

    public void update(long now, Signals s, Config c) {
        if (!s.interactive) {
            reset(now);
            return;
        }
        long lastTouchAny = NEVER;
        for (int d = 0; d < count; d++) lastTouchAny = Math.max(lastTouchAny, s.lastTouch[d]);
        long lastAny = Math.max(s.lastKeyOrStick, lastTouchAny);

        // 1. Aufwecken
        boolean wakeAllAbsent = false;
        for (int d = 0; d < count; d++) {
            if (mode[d] == Mode.ACTIVE) continue;
            long ref = since[d];
            if (cause[d] == Cause.MANUAL) ref = Math.max(ref, manualAt + c.manualGraceMs);
            if (cause[d] == Cause.ABSENT || cause[d] == Cause.MANUAL) {
                if (lastAny > ref) wakeAllAbsent = true;
            } else if (cause[d] == Cause.UNUSED) {
                boolean keysCount = d == 0 || c.keysCountForSecond;
                if (s.lastTouch[d] > ref || (keysCount && s.lastKeyOrStick > ref)) wake(d, now);
            }
        }
        if (wakeAllAbsent) {
            // Wer zurückkommt, bekommt beide Bildschirme zurück – auch einen, der vorher „unbenutzt“ war.
            for (int d = 0; d < count; d++) wake(d, now);
            absentWokeAt = now;
        }

        boolean manualRunning = false;
        for (int d = 0; d < count; d++) if (mode[d] != Mode.ACTIVE && cause[d] == Cause.MANUAL) manualRunning = true;

        long absentLimit = s.gameForeground ? c.gameIdleMs : c.idleMs;
        boolean automatic = c.enabled && !s.excluded;

        for (int d = 0; d < count; d++) {
            if (!s.displayOn[d]) {
                if (cause[d] != Cause.MANUAL) wake(d, now);
                continue;
            }
            if (mode[d] == Mode.ACTIVE && automatic && !manualRunning) {
                long lively = c.motionMaxMs > 0 ? s.lastLively[d] : NEVER;
                long absentStart = Math.max(lastAny, absentWokeAt);
                // Bewegung verschiebt den Start, aber nur bis motionMaxMs nach der letzten echten Eingabe.
                long absentStartWithMotion = Math.max(absentStart, Math.min(lively, absentStart + c.motionMaxMs));
                boolean absentDue = absentLimit > 0 && now - absentStartWithMotion >= absentLimit;

                boolean keysCount = d == 0 || c.keysCountForSecond;
                long unusedStart = Math.max(Math.max(s.lastTouch[d], wokeAt[d]), Math.max(lively, absentWokeAt));
                if (keysCount) unusedStart = Math.max(unusedStart, s.lastKeyOrStick);
                long unusedLimit = d < c.unusedMs.length ? c.unusedMs[d] : 0;
                boolean unusedDue = unusedLimit > 0 && now - unusedStart >= unusedLimit;

                if (absentDue || unusedDue) {
                    mode[d] = Mode.FADING;
                    cause[d] = absentDue ? Cause.ABSENT : Cause.UNUSED;
                    since[d] = now;
                    if (absentDue) {
                        sleptThisPeriod = false;
                        transferSeen = false;
                    }
                }
            } else if (mode[d] != Mode.ACTIVE && cause[d] == Cause.UNUSED && automatic) {
                // Aus „unbenutzt“ wird „abwesend“, sobald auch sonst niemand mehr etwas tut: dann weckt jede Eingabe.
                long absentStart = Math.max(lastAny, absentWokeAt);
                if (absentLimit > 0 && now - absentStart >= absentLimit) cause[d] = Cause.ABSENT;
            } else if (mode[d] != Mode.ACTIVE && cause[d] != Cause.MANUAL && !automatic) {
                wake(d, now);
                continue;
            }
            advance(d, now, c);
        }
        updateSleep(now, s, c, lastAny);
    }

    private void advance(int d, long now, Config c) {
        if (mode[d] == Mode.FADING) {
            long fade = cause[d] == Cause.MANUAL ? 700 : Math.max(1, c.fadeMs);
            if (now - since[d] >= fade) {
                mode[d] = c.blackAfterMs == 0 ? Mode.BLACK : Mode.SAVER;
                since[d] = now;
            }
        } else if (mode[d] == Mode.SAVER) {
            if (c.blackAfterMs > 0 && now - since[d] >= c.blackAfterMs) {
                mode[d] = Mode.BLACK;
                since[d] = now;
            }
        }
    }

    private void updateSleep(long now, Signals s, Config c, long lastAny) {
        boolean allAbsent = true;
        int considered = 0;
        long periodStart;
        for (int d = 0; d < count; d++) {
            if (!s.displayOn[d]) continue;
            considered++;
            if (mode[d] == Mode.ACTIVE || mode[d] == Mode.FADING || cause[d] == Cause.UNUSED) {
                allAbsent = false;
                break;
            }
        }
        if (!allAbsent || considered == 0) {
            transferSeen = false;
            lastTransfer = NEVER;
            return;
        }
        periodStart = Math.max(lastAny, absentWokeAt);
        if (s.transferActive) {
            transferSeen = true;
            lastTransfer = now;
        }
        if (sleptThisPeriod) return;
        boolean due = false;
        if (c.sleepAfterTransfer && transferSeen && now - lastTransfer >= c.transferQuietMs) due = true;
        if (c.sleepAfterMs > 0 && now - periodStart >= c.sleepAfterMs) due = true;
        if (due) {
            sleptThisPeriod = true;
            sleepRequested = true;
        }
    }

    private void wake(int d, long now) {
        if (mode[d] != Mode.ACTIVE) wokeAt[d] = now;
        mode[d] = Mode.ACTIVE;
        cause[d] = Cause.NONE;
        since[d] = NEVER;
    }

    static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
