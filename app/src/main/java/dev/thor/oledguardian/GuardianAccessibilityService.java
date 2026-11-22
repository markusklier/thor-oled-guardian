package dev.thor.oledguardian;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import dev.thor.oledguardian.engine.GuardEngine;
import dev.thor.oledguardian.engine.MaskAnimator;
import dev.thor.oledguardian.engine.StaticMap;
import dev.thor.oledguardian.engine.TransferMeter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Herz des Guardians. Sammelt, was Android ohne Root hergibt (Tasten, Berührungen je Bildschirm, Bildschirmfotos
 * für die Bewegungsanalyse, die App im Vordergrund, Datenverkehr) – optional ergänzt durch den Präzisionsmodus
 * (Shizuku: Sticks, lokale Kopien, Schlafen legen). Daraus entscheidet {@link GuardEngine} je Bildschirm; dieser
 * Dienst zeichnet Schoner und Spielschutz-Abdunkelung als Overlays.
 *
 * Datenschutz: Bildschirmfotos werden sofort auf ein grobes Helligkeitsraster (1 Wert je 8×8 Pixel) verkleinert und
 * nur im Speicher verglichen; nichts wird gespeichert oder gesendet. Es werden keine Texte oder Tasten aufgezeichnet.
 */
public final class GuardianAccessibilityService extends AccessibilityService {
    private static volatile GuardianAccessibilityService instance;
    static GuardianAccessibilityService instance() { return instance; }

    static final int ROLES = 2;
    private static final String[] ROLE_NAMES = {"Oben", "Unten"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "guardian-worker");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final GuardEngine engine = new GuardEngine(ROLES);
    private final GuardEngine.Signals signals = new GuardEngine.Signals(ROLES);
    private final Screen[] screens = {new Screen(0), new Screen(1)};
    private final TransferMeter net = new TransferMeter(64_000);
    private final TransferMeter disk = new TransferMeter(1_500_000);
    private final Set<Integer> swallowedKeys = new HashSet<>();
    private final LinkedHashSet<String> recentApps = new LinkedHashSet<>();

    private GuardEngine.Config config;
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;
    private DisplayManager displays;
    private PowerManager power;
    private KeyguardManager keyguard;
    private AudioManager audio;
    private BroadcastReceiver screenReceiver;

    private long a11yKey = GuardEngine.NEVER;
    private volatile long probeKey = GuardEngine.NEVER, probeStick = GuardEngine.NEVER;
    private volatile int probePads = -1;
    private volatile long probeDisk = -1;
    private volatile String probeStatus;
    private long lastProbePoll, lastDiskPoll;
    private boolean probeBusy;
    private String foreground = "";
    private String sleepNote = "";
    private long lastTick;
    private boolean tickScheduled;

    /** Alles, was zu einem Bildschirm gehört. */
    final class Screen {
        final int role;
        Display display;
        Context ctx;
        WindowManager wm;
        int strategy = -1;
        String overlayError = "";
        View watcher;
        SaverView saver;
        int saverFlags;
        MaskView mask;
        WindowManager.LayoutParams maskLp;
        int[] maskBounds;
        boolean maskDirty = true;
        final StaticMap map = new StaticMap();
        final MaskAnimator anim = new MaskAnimator();
        long lastCapture = GuardEngine.NEVER;
        long lastCaptureOk = GuardEngine.NEVER;
        boolean captureBusy;
        int captureFails;
        String captureNote = "noch keine Analyse";
        float motion = -1f;
        boolean dark;
        String physicalId = "";
        int widthPx, heightPx;

        Screen(int role) { this.role = role; }
    }

    // ================================================================ Lebenszyklus

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefs = GuardPrefs.get(this);
        config = GuardPrefs.config(this);
        displays = getSystemService(DisplayManager.class);
        power = getSystemService(PowerManager.class);
        keyguard = getSystemService(KeyguardManager.class);
        audio = getSystemService(AudioManager.class);
        Probe.init(this);
        engine.reset(SystemClock.uptimeMillis());

        prefListener = (sp, key) -> handler.post(this::settingsChanged);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        displays.registerDisplayListener(displayListener, handler);
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                    engine.reset(SystemClock.uptimeMillis());
                    render(SystemClock.uptimeMillis());
                } else {
                    engine.reset(SystemClock.uptimeMillis());
                }
                tickSoon();
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, f);
        assignDisplays();
        tickSoon();
    }

    @Override public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        handler.removeCallbacksAndMessages(null);
        for (Screen s : screens) removeAll(s);
        if (prefs != null && prefListener != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) { }
            screenReceiver = null;
        }
        worker.shutdownNow();
        if (instance == this) instance = null;
    }

    private void settingsChanged() {
        config = GuardPrefs.config(this);
        for (Screen s : screens) {
            if (!GuardPrefs.hud(this, s.role) || !config.enabled) s.anim.hideNow();
            removeSaver(s);   // Stil/Flags neu aufbauen
        }
        tickSoon();
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { assignDisplays(); tickSoon(); }
        @Override public void onDisplayRemoved(int id) { assignDisplays(); tickSoon(); }
        @Override public void onDisplayChanged(int id) { assignDisplays(); tickSoon(); }
    };

    // ================================================================ Öffentliche Aktionen (UI, Kacheln)

    /** „Jetzt schonen“: beide Bildschirme sofort abdunkeln; die nächste Eingabe weckt. */
    void blackoutNow() {
        engine.startManual(SystemClock.uptimeMillis());
        tickSoon();
    }

    void collapseShade() {
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
    }

    void wakeAll() {
        engine.reset(SystemClock.uptimeMillis());
        tickSoon();
    }

    // ================================================================ Eingaben

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_UP) return swallowedKeys.remove(code);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        boolean swallow = e.getRepeatCount() == 0 && engine.shouldSwallowKey() && GuardPrefs.firstKeyOnlyWakes(this)
                && !systemKey(code);
        a11yKey = SystemClock.uptimeMillis();
        if (swallow) swallowedKeys.add(code);
        tickSoon();
        return swallow;   // sonst unverändert ans Spiel
    }

    private static boolean systemKey(int code) {
        return code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_SLEEP || code == KeyEvent.KEYCODE_WAKEUP
                || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_HOME
                || code == KeyEvent.KEYCODE_APP_SWITCH;
    }

    private void touched(int role) {
        signals.lastTouch[role] = SystemClock.uptimeMillis();
        tickSoon();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        CharSequence p = e.getPackageName();
        String pkg = p == null ? "" : p.toString();
        if (pkg.isEmpty() || pkg.equals(getPackageName())) return;
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Nur der Paketname – keine Fensterinhalte, keine Texte.
            if (pkg.equals("com.android.systemui") || pkg.startsWith("com.google.android.inputmethod")) return;
            if (!pkg.equals(foreground)) {
                foreground = pkg;
                recentApps.remove(pkg);
                recentApps.add(pkg);
                while (recentApps.size() > 8) recentApps.remove(recentApps.iterator().next());
            }
        } else if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED
                || e.getEventType() == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            a11yKey = SystemClock.uptimeMillis();
        }
    }

    @Override public void onInterrupt() { }

    // ================================================================ Takt

    private void tickSoon() {
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    private final Runnable ticker = this::tick;

    private void tick() {
        long now = SystemClock.uptimeMillis();
        handler.removeCallbacks(ticker);
        boolean interactive = power != null && power.isInteractive() && (keyguard == null || !keyguard.isKeyguardLocked());
        signals.interactive = interactive;
        if (config == null) config = GuardPrefs.config(this);

        pollProbe(now);
        if (now - lastTick >= 900) {
            net.feed(TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes(), now);
            if (probeDisk >= 0) disk.feed(probeDisk, now);
            lastTick = now;
        }
        signals.transferActive = net.active() || disk.active();
        signals.lastKeyOrStick = Math.max(a11yKey, Math.max(probeKey, probeStick));
        signals.gameForeground = GameApps.isGame(this, foreground);
        signals.excluded = GuardPrefs.isExcluded(this, foreground);
        for (Screen s : screens) {
            signals.displayOn[s.role] = s.display != null && isOn(s.display);
            if (s.role == 0 && audioFallback(s, now)) signals.lastLively[0] = now;
        }

        engine.update(now, signals, config);
        if (engine.takeSleepRequest()) sleepDevice();
        render(now);
        if (interactive) scheduleCaptures(now);

        handler.postDelayed(ticker, interactive ? 1_000 : 3_000);
    }

    private static boolean isOn(Display d) {
        int st = d.getState();
        return d.isValid() && (st == Display.STATE_ON || st == Display.STATE_VR || st == Display.STATE_ON_SUSPEND
                || st == Display.STATE_UNKNOWN);
    }

    /** Ohne Bildanalyse: Ton außerhalb von Spielen = vermutlich Video. Bei geschütztem (schwarzem) Video ebenso. */
    private boolean audioFallback(Screen s, long now) {
        boolean noAnalysis = now - s.lastCaptureOk > 60_000 || s.dark;
        return noAnalysis && audio != null && audio.isMusicActive() && (!signals.gameForeground || s.dark);
    }

    // ================================================================ Präzisionsmodus

    private void pollProbe(long now) {
        if (probeBusy || now - lastProbePoll < 1_000) return;
        if (!Probe.granted()) {
            probePads = -1;
            return;
        }
        lastProbePoll = now;
        probeBusy = true;
        boolean withDisk = now - lastDiskPoll >= 4_000;
        if (withDisk) lastDiskPoll = now;
        try {
            worker.execute(() -> {
                try {
                    Probe.ensure();
                    long[] in = Probe.input();
                    if (in != null) {
                        probeKey = in[0];
                        probeStick = in[1];
                        probePads = (int) in[2];
                    } else {
                        probePads = -1;
                    }
                    if (withDisk) probeDisk = Probe.diskBytes();
                    if (probeStatus == null || withDisk) probeStatus = Probe.status();
                } finally {
                    probeBusy = false;
                }
            });
        } catch (RuntimeException e) {
            probeBusy = false;
        }
    }

    private void sleepDevice() {
        worker.execute(() -> {
            boolean ok = Probe.connected() && Probe.sleep();
            if (ok) {
                sleepNote = "Schlafen gelegt (Präzisionsmodus)";
                return;
            }
            handler.post(() -> {
                DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
                ComponentName admin = new ComponentName(this, GuardianAdminReceiver.class);
                if (dpm != null && dpm.isAdminActive(admin)) {
                    try {
                        dpm.lockNow();
                        sleepNote = "Gesperrt (Geräteadministrator)";
                    } catch (SecurityException e) {
                        sleepNote = "Sperren nicht erlaubt";
                    }
                } else {
                    sleepNote = "Schlafen nicht möglich: Präzisionsmodus oder Geräteadministrator fehlt";
                }
            });
        });
    }

    // ================================================================ Bildschirme

    private void assignDisplays() {
        if (displays == null) return;
        Display main = displays.getDisplay(Display.DEFAULT_DISPLAY);
        Display second = null;
        long best = -1;
        for (Display d : displays.getDisplays()) {
            if (d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY || !d.isValid()) continue;
            if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
            String n = d.getName() == null ? "" : d.getName().toLowerCase(Locale.ROOT);
            if (n.contains("virtual") || n.contains("overlay") || n.contains("cast") || n.contains("record")) continue;
            Point size = new Point();
            d.getRealSize(size);
            long area = (long) size.x * size.y;
            if (area > best) { best = area; second = d; }
        }
        setDisplay(screens[0], main);
        setDisplay(screens[1], second);
    }

    private void setDisplay(Screen s, Display d) {
        boolean same = s.display != null && d != null && s.display.getDisplayId() == d.getDisplayId();
        if (!same) {
            removeAll(s);
            s.display = d;
            s.ctx = null;
            s.wm = null;
            s.strategy = -1;
            s.map.clear();
            s.anim.hideNow();
            s.physicalId = d == null ? "" : physicalId(d);
        }
        if (d != null) {
            Point size = new Point();
            d.getRealSize(size);
            if (size.x != s.widthPx || size.y != s.heightPx) {
                s.widthPx = size.x;
                s.heightPx = size.y;
                s.map.clear();
                s.anim.hideNow();
                removeSaver(s);
                removeMask(s);
            }
        }
    }

    private static final Pattern UNIQUE = Pattern.compile("uniqueId \"local:(\\d+)\"");

    private static String physicalId(Display d) {
        Matcher m = UNIQUE.matcher(String.valueOf(d));
        return m.find() ? m.group(1) : "";
    }

    // ================================================================ Overlays

    private WindowManager.LayoutParams params(int w, int h, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return lp;
    }

    private Context viewContext(Screen s) {
        if (s.ctx == null) s.ctx = s.role == 0 && s.display.getDisplayId() == Display.DEFAULT_DISPLAY
                ? this : createDisplayContext(s.display);
        return s.ctx;
    }

    /** Fügt ein Fenster hinzu; probiert die Wege, die Android für zweite Bildschirme anbietet. */
    @SuppressLint("NewApi")
    private boolean add(Screen s, View v, WindowManager.LayoutParams lp, String title) {
        if (s.display == null) return false;
        lp.setTitle("OLED Guardian " + title + " " + ROLE_NAMES[s.role]);
        int start = s.strategy >= 0 ? s.strategy : 0;
        StringBuilder errors = new StringBuilder();
        for (int k = start; k < 3; k++) {
            try {
                WindowManager wm;
                if (k == 0) {
                    wm = viewContext(s).getSystemService(WindowManager.class);
                    lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
                } else if (k == 1) {
                    if (Build.VERSION.SDK_INT < 31) continue;
                    wm = createWindowContext(s.display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                            .getSystemService(WindowManager.class);
                    lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
                } else {
                    if (!Settings.canDrawOverlays(this)) { errors.append(" · Einblenden über Apps nicht erlaubt"); continue; }
                    wm = createDisplayContext(s.display)
                            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                            .getSystemService(WindowManager.class);
                    lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                }
                if (wm == null) continue;
                wm.addView(v, lp);
                s.wm = wm;
                s.strategy = k;
                s.overlayError = "";
                return true;
            } catch (RuntimeException e) {
                errors.append(" · Weg ").append(k + 1).append(": ").append(e.getClass().getSimpleName());
            }
        }
        s.overlayError = "Overlay nicht möglich" + errors;
        return false;
    }

    private void remove(Screen s, View v) {
        if (v == null || s.wm == null) return;
        try { s.wm.removeViewImmediate(v); } catch (RuntimeException ignored) { }
    }

    private void removeSaver(Screen s) { remove(s, s.saver); s.saver = null; }
    private void removeMask(Screen s) { remove(s, s.mask); s.mask = null; s.maskBounds = null; }

    private void removeAll(Screen s) {
        removeSaver(s);
        removeMask(s);
        remove(s, s.watcher);
        s.watcher = null;
    }

    private void render(long now) {
        for (Screen s : screens) {
            if (s.display == null || !signals.displayOn[s.role] || !signals.interactive) {
                removeSaver(s);
                removeMask(s);
                if (!signals.interactive) { remove(s, s.watcher); s.watcher = null; }
                continue;
            }
            ensureWatcher(s);
            renderSaver(s, now);
            renderMask(s);
        }
    }

    /** Unsichtbarer 1×1-Fühler: meldet jede Berührung auf diesem Bildschirm, ohne sie abzufangen. */
    @SuppressLint("ClickableViewAccessibility")
    private void ensureWatcher(Screen s) {
        if (s.watcher != null) return;
        View v = new View(viewContext(s));
        final int role = s.role;
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_OUTSIDE || a == MotionEvent.ACTION_DOWN) touched(role);
            return false;
        });
        WindowManager.LayoutParams lp = params(1, 1, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        if (add(s, v, lp, "Fühler")) s.watcher = v;
    }

    private void renderSaver(Screen s, long now) {
        GuardEngine.Mode m = engine.mode(s.role);
        if (m == GuardEngine.Mode.ACTIVE) {
            removeSaver(s);
            return;
        }
        GuardEngine.Cause cause = engine.cause(s.role);
        boolean unused = cause == GuardEngine.Cause.UNUSED;
        String style = unused && GuardPrefs.UNUSED_DIM.equals(GuardPrefs.unusedStyle(this))
                ? GuardPrefs.UNUSED_DIM : GuardPrefs.style(this);
        if (cause == GuardEngine.Cause.MANUAL && GuardPrefs.STYLE_GLASS.equals(style)) style = GuardPrefs.STYLE_BLACK;
        boolean dim = GuardPrefs.UNUSED_DIM.equals(style);
        int flags = 0;
        if (dim) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (!unused && GuardPrefs.keepAwake(this)) flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

        if (s.saver != null && s.saverFlags != flags) removeSaver(s);
        if (s.saver == null) {
            final int role = s.role;
            SaverView v = new SaverView(viewContext(s), () -> touched(role));
            WindowManager.LayoutParams lp = params(s.widthPx, s.heightPx, flags);
            if (!add(s, v, lp, "Schoner")) return;
            s.saver = v;
            s.saverFlags = flags;
            v.markShown();
        }
        long fadeStart, fadeMs;
        if (m == GuardEngine.Mode.FADING) {
            fadeStart = engine.since(s.role);
            fadeMs = cause == GuardEngine.Cause.MANUAL ? 700 : config.fadeMs;
        } else {
            fadeStart = 0;
            fadeMs = 1;
        }
        boolean black = m == GuardEngine.Mode.BLACK;
        boolean info = GuardPrefs.info(this) && s.role == 0;
        String transfer = "";
        if (signals.transferActive) {
            double rate = Math.max(net.bytesPerSec(), disk.bytesPerSec());
            transfer = (disk.bytesPerSec() > net.bytesPerSec() ? "Kopieren " : "Download ") + TransferMeter.format(rate);
        }
        s.saver.configure(style, fadeStart, fadeMs, black, info, transfer);
    }

    // ================================================================ Spielschutz (statische Bereiche)

    private float cellPx(Screen s) {
        int sw = Math.max(1, s.widthPx / StaticMap.STEP);
        return s.widthPx / (float) sw * StaticMap.CELL;
    }

    private void renderMask(Screen s) {
        boolean allowed = config.enabled && !signals.excluded && GuardPrefs.hud(this, s.role)
                && engine.mode(s.role) == GuardEngine.Mode.ACTIVE;
        if (!allowed) {
            s.anim.hideNow();
            removeMask(s);
            return;
        }
        if (!s.anim.visible() && !s.anim.animating()) {
            removeMask(s);
            return;
        }
        int[] b = s.anim.bounds();
        if (b == null) {
            removeMask(s);
            return;
        }
        float cell = cellPx(s);
        int x = Math.round(b[0] * cell), y = Math.round(b[1] * cell);
        int w = Math.min(s.widthPx - x, Math.round((b[2] - b[0]) * cell));
        int h = Math.min(s.heightPx - y, Math.round((b[3] - b[1]) * cell));
        if (w <= 0 || h <= 0) { removeMask(s); return; }
        if (s.mask == null) {
            MaskView v = new MaskView(viewContext(s));
            WindowManager.LayoutParams lp = params(w, h, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            lp.x = x;
            lp.y = y;
            if (!add(s, v, lp, "Spielschutz")) return;
            s.mask = v;
            s.maskLp = lp;
            s.maskBounds = b;
            s.maskDirty = true;
        } else if (!java.util.Arrays.equals(b, s.maskBounds)) {
            s.maskLp.x = x;
            s.maskLp.y = y;
            s.maskLp.width = w;
            s.maskLp.height = h;
            try { s.wm.updateViewLayout(s.mask, s.maskLp); } catch (RuntimeException e) { removeMask(s); return; }
            s.maskBounds = b;
            s.maskDirty = true;
        }
        if (!s.maskDirty) return;   // nur neu zeichnen, wenn sich die Abdunkelung geändert hat
        s.maskDirty = false;
        s.mask.update(s.anim.current(), s.anim.cols(), s.anim.rows(), cell, x, y);
    }

    private final Runnable maskTicker = new Runnable() {
        private long last = SystemClock.uptimeMillis();
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            long dt = Math.min(1_000, now - last);
            last = now;
            boolean again = false;
            float strength = GuardPrefs.i(GuardianAccessibilityService.this, GuardPrefs.C_HUD_STRENGTH) / 100f;
            for (Screen s : screens) {
                if (s.anim.step(dt, strength)) {
                    s.maskDirty = true;
                    renderMask(s);
                }
                again |= s.anim.animating();
            }
            if (again) handler.postDelayed(this, 250);
        }
    };

    private void kickMaskAnimation() {
        handler.removeCallbacks(maskTicker);
        handler.postDelayed(maskTicker, 250);
    }

    // ================================================================ Bildanalyse

    private void scheduleCaptures(long now) {
        int interval = GuardPrefs.i(this, GuardPrefs.C_ANALYSIS) * 1000;
        long lastAny = signals.lastKeyOrStick;
        for (long t : signals.lastTouch) lastAny = Math.max(lastAny, t);
        long absentLimit = signals.gameForeground ? config.gameIdleMs : config.idleMs;
        for (Screen s : screens) {
            if (s.display == null || !signals.displayOn[s.role] || s.captureBusy) continue;
            if (engine.mode(s.role) != GuardEngine.Mode.ACTIVE) continue;
            if (!config.enabled || signals.excluded) continue;
            boolean hud = GuardPrefs.hud(this, s.role);
            long unused = s.role < config.unusedMs.length ? config.unusedMs[s.role] : 0;
            boolean nearAbsent = config.motionMaxMs > 0 && absentLimit > 0 && now - lastAny > absentLimit - 3L * interval;
            if (!hud && unused == 0 && !nearAbsent) continue;   // gerade nichts zu entscheiden: kein Foto
            long every = s.anim.visible() ? Math.min(interval, 6_000) : interval;
            if (now - s.lastCapture < every) continue;
            // nie zwei Fotos im selben Takt (Android begrenzt die Rate)
            Screen other = screens[1 - s.role];
            if (now - other.lastCapture < 1_100) continue;
            capture(s, now);
            break;
        }
    }

    @SuppressLint("NewApi")
    private void capture(Screen s, long now) {
        s.lastCapture = now;
        s.captureBusy = true;
        final float[] maskNow = s.anim.cols() == s.map.cols() && s.anim.rows() == s.map.rows()
                ? s.anim.current().clone() : null;
        final int displayId = s.display.getDisplayId();
        boolean useProbe = s.captureFails >= 3 && Probe.connected();
        if (useProbe) {
            worker.execute(() -> {
                int[] size = new int[2];
                byte[] luma = Probe.capture(s.physicalId, StaticMap.STEP, size);
                handler.post(() -> analysed(s, luma, size[0], size[1], maskNow, luma == null ? "screencap fehlgeschlagen" : "per Präzisionsmodus"));
            });
            return;
        }
        try {
            takeScreenshot(displayId, worker, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    int[] size = new int[2];
                    byte[] luma = null;
                    HardwareBuffer hb = result.getHardwareBuffer();
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                        if (hw != null) luma = sample(hw, size);
                    } catch (RuntimeException e) {
                        luma = null;
                    } finally {
                        hb.close();
                    }
                    final byte[] out = luma;
                    handler.post(() -> analysed(s, out, size[0], size[1], maskNow, out == null ? "Bild nicht lesbar" : ""));
                }

                @Override public void onFailure(int errorCode) {
                    handler.post(() -> {
                        s.captureFails++;
                        analysed(s, null, 0, 0, null, "Bildschirmfoto abgelehnt (Code " + errorCode + ")");
                    });
                }
            });
        } catch (RuntimeException e) {
            s.captureFails++;
            analysed(s, null, 0, 0, null, "Bildschirmfoto nicht verfügbar: " + e.getClass().getSimpleName());
        }
    }

    /** Verkleinert auf 1 Wert je 8×8 Pixel und rechnet in Helligkeit um. Läuft im Hintergrund. */
    private static byte[] sample(Bitmap hw, int[] size) {
        int sw = Math.max(1, hw.getWidth() / StaticMap.STEP), sh = Math.max(1, hw.getHeight() / StaticMap.STEP);
        Bitmap small = null;
        try {
            small = Bitmap.createScaledBitmap(hw, sw, sh, true);
            if (small.getConfig() == Bitmap.Config.HARDWARE) {
                Bitmap soft = small.copy(Bitmap.Config.ARGB_8888, false);
                small.recycle();
                small = soft;
            }
        } catch (RuntimeException e) {
            Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
            if (soft == null) return null;
            small = Bitmap.createScaledBitmap(soft, sw, sh, true);
            if (small != soft) soft.recycle();
        }
        if (small == null) return null;
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        small.recycle();
        hw.recycle();
        byte[] luma = new byte[sw * sh];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            luma[i] = (byte) ((r * 54 + g * 183 + b * 19) >> 8);
        }
        size[0] = sw;
        size[1] = sh;
        return luma;
    }

    private void analysed(Screen s, byte[] luma, int w, int h, float[] mask, String note) {
        s.captureBusy = false;
        long now = SystemClock.uptimeMillis();
        if (luma == null) {
            s.captureNote = note;
            return;
        }
        if (engine.mode(s.role) != GuardEngine.Mode.ACTIVE) return;   // Schoner kam dazwischen: Foto zeigt ihn selbst
        s.captureFails = 0;
        s.lastCaptureOk = now;
        StaticMap.Result r = s.map.feed(luma, w, h, now, mask);
        s.anim.resize(s.map.cols(), s.map.rows());
        s.motion = r.motion;
        s.dark = r.dark;
        s.captureNote = note;
        if (r.motion >= StaticMap.LIVELY && !r.dark) signals.lastLively[s.role] = now;
        if (GuardPrefs.hud(this, s.role)) {
            long after = GuardPrefs.i(this, GuardPrefs.C_HUD_AFTER) * 1000L;
            float strength = GuardPrefs.i(this, GuardPrefs.C_HUD_STRENGTH) / 100f;
            s.anim.setTargets(s.map.targets(after, strength));
        } else {
            s.anim.clearTargets();
        }
        if (s.anim.animating()) kickMaskAnimation();
    }

    // ================================================================ Zustand für die Einstellungen

    static final class RoleStatus {
        String name, display, mode, analysis, overlay;
        int width, height;
        StaticMap map;
        MaskAnimator anim;
    }

    List<RoleStatus> roleStatus() {
        long now = SystemClock.uptimeMillis();
        List<RoleStatus> out = new ArrayList<>();
        for (Screen s : screens) {
            RoleStatus r = new RoleStatus();
            r.name = ROLE_NAMES[s.role];
            r.map = s.map;
            r.anim = s.anim;
            r.width = s.widthPx;
            r.height = s.heightPx;
            if (s.display == null) {
                r.display = "nicht gefunden";
                r.mode = "–";
                r.analysis = "";
                r.overlay = "";
                out.add(r);
                continue;
            }
            r.display = "„" + s.display.getName() + "“ · ID " + s.display.getDisplayId() + " · " + s.widthPx + "×" + s.heightPx
                    + (signals.displayOn[s.role] ? "" : " · ausgeschaltet");
            r.mode = modeText(s.role);
            if (s.lastCaptureOk == GuardEngine.NEVER) r.analysis = s.captureNote;
            else {
                String motion = s.dark ? "dunkel" : (s.motion >= StaticMap.LIVELY ? "bewegt" : "ruhig");
                long ago = (now - s.lastCaptureOk) / 1000;
                r.analysis = "Bild " + motion + String.format(Locale.GERMANY, " (%.0f %% verändert)", Math.max(0, s.motion) * 100)
                        + " · vor " + ago + " s" + (s.captureNote.isEmpty() ? "" : " · " + s.captureNote);
            }
            String[] ways = {"Bedienungshilfe", "Bedienungshilfe (Fensterkontext)", "Über anderen Apps"};
            r.overlay = s.overlayError.isEmpty() ? (s.strategy >= 0 ? "Overlay: " + ways[s.strategy] : "") : s.overlayError;
            out.add(r);
        }
        return out;
    }

    private String modeText(int role) {
        GuardEngine.Mode m = engine.mode(role);
        GuardEngine.Cause c = engine.cause(role);
        String why = c == GuardEngine.Cause.UNUSED ? " (unbenutzt)" : c == GuardEngine.Cause.MANUAL ? " (von Hand)" : "";
        switch (m) {
            case FADING: return "blendet ab" + why;
            case SAVER: return "Schoner" + why;
            case BLACK: return "schwarz" + why;
            default:
                Screen s = screens[role];
                return s.anim.visible() ? "aktiv · Spielschutz dunkelt statische Bereiche ab" : "aktiv";
        }
    }

    String inputText() {
        long now = SystemClock.uptimeMillis();
        StringBuilder sb = new StringBuilder("Letzte Eingabe: ");
        long key = Math.max(a11yKey, probeKey);
        sb.append("Taste ").append(ago(now, key));
        if (probePads >= 0) sb.append(" · Stick ").append(ago(now, probeStick));
        sb.append(" · Berührung oben ").append(ago(now, signals.lastTouch[0]));
        sb.append(" · unten ").append(ago(now, signals.lastTouch[1]));
        return sb.toString();
    }

    String foregroundText() {
        if (foreground.isEmpty()) return "Vordergrund: noch unbekannt";
        String label = GameApps.label(this, foreground);
        return "Vordergrund: " + label + (signals.gameForeground ? " (Spiel)" : "") + (signals.excluded ? " · ausgenommen" : "");
    }

    String transferText() {
        String t = "Netz " + TransferMeter.format(net.bytesPerSec());
        if (probeDisk >= 0) t += " · Speicher " + TransferMeter.format(disk.bytesPerSec());
        return "Übertragung: " + t + (signals.transferActive ? " – läuft" : "") + (sleepNote.isEmpty() ? "" : " · " + sleepNote);
    }

    String probeText() {
        if (!Probe.installed(this)) return "Präzisionsmodus: Shizuku nicht installiert";
        if (!Probe.running()) return "Präzisionsmodus: Shizuku läuft nicht";
        if (!Probe.granted()) return "Präzisionsmodus: Freigabe fehlt";
        if (probePads < 0) return "Präzisionsmodus: verbindet …" + (Probe.lastError.isEmpty() ? "" : " (" + Probe.lastError + ")");
        return "Präzisionsmodus: " + (probeStatus == null ? "aktiv" : probeStatus);
    }

    List<String> recentApps() {
        List<String> l = new ArrayList<>(recentApps);
        java.util.Collections.reverse(l);
        return l;
    }

    private static String ago(long now, long t) {
        if (t <= GuardEngine.NEVER / 2) return "–";
        long s = (now - t) / 1000;
        if (s < 60) return "vor " + s + " s";
        return "vor " + (s / 60) + " min";
    }
}
