package dev.thor.oledguardian;

import android.content.Context;
import android.content.SharedPreferences;

import dev.thor.oledguardian.engine.GuardEngine;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Alle Einstellungen an einer Stelle: Schlüssel, Standardwerte, Auswahllisten. */
public final class GuardPrefs {
    private GuardPrefs() {}

    public static final String FILE = "guardian";

    // Schlüssel
    public static final String ENABLED = "enabled";
    public static final String IDLE_S = "idle_s";
    public static final String GAME_IDLE_S = "game_idle_s";
    public static final String MOTION_MAX_S = "motion_max_s";
    public static final String STYLE = "style";
    public static final String BLACK_AFTER_S = "black_after_s";
    public static final String FADE_S = "fade_s";
    public static final String INFO = "info";
    public static final String KEEP_AWAKE = "keep_awake";
    public static final String SLEEP_TRANSFER = "sleep_transfer";
    public static final String SLEEP_AFTER_S = "sleep_after_s";
    public static final String UNUSED_MAIN_S = "unused_main_s";
    public static final String UNUSED_SECOND_S = "unused_second_s";
    public static final String UNUSED_STYLE = "unused_style";
    public static final String KEYS_SECOND = "keys_second";
    public static final String HUD_MAIN = "hud_main";
    public static final String HUD_SECOND = "hud_second";
    public static final String HUD_AFTER_S = "hud_after_s";
    public static final String HUD_STRENGTH = "hud_strength";
    public static final String ANALYSIS_S = "analysis_s";
    public static final String FIRST_KEY = "first_key";
    public static final String EXCLUDED = "excluded";
    public static final String GAMES = "games";
    public static final String NOT_GAMES = "not_games";

    // Schoner-Stile
    public static final String STYLE_BLACK = "schwarz";
    public static final String STYLE_AURORA = "aurora";
    public static final String STYLE_GLASS = "glas";
    public static final String STYLE_CLOCK = "uhr";
    // Stil für einen unbenutzten Bildschirm
    public static final String UNUSED_DIM = "dimmen";
    public static final String UNUSED_SAVER = "schoner";

    /** Auswahl: Werte und deutsche Beschriftungen. */
    public static final class Choice {
        public final String key, title;
        public final int[] values;
        public final String[] labels;
        public final int def;
        Choice(String key, String title, int def, int[] values, String[] labels) {
            this.key = key; this.title = title; this.def = def; this.values = values; this.labels = labels;
        }
        public String label(int v) {
            for (int i = 0; i < values.length; i++) if (values[i] == v) return labels[i];
            return String.valueOf(v);
        }
    }

    public static final Choice C_IDLE = new Choice(IDLE_S, "Niemand bedient den Thor", 120,
            new int[]{60, 120, 180, 300, 600}, new String[]{"nach 1 Minute", "nach 2 Minuten", "nach 3 Minuten", "nach 5 Minuten", "nach 10 Minuten"});
    public static final Choice C_GAME_IDLE = new Choice(GAME_IDLE_S, "… während ein Spiel läuft", 600,
            new int[]{180, 300, 600, 1200, 1800, 0}, new String[]{"nach 3 Minuten", "nach 5 Minuten", "nach 10 Minuten", "nach 20 Minuten", "nach 30 Minuten", "nie"});
    public static final Choice C_MOTION = new Choice(MOTION_MAX_S, "Videos und Zwischensequenzen laufen lassen", 2700,
            new int[]{0, 900, 2700, 7200}, new String[]{"nicht beachten", "bis 15 Minuten", "bis 45 Minuten", "bis 2 Stunden"});
    public static final Choice C_BLACK = new Choice(BLACK_AFTER_S, "Danach komplett schwarz", 180,
            new int[]{0, 60, 180, 600, -1}, new String[]{"sofort", "nach 1 Minute", "nach 3 Minuten", "nach 10 Minuten", "nie"});
    public static final Choice C_FADE = new Choice(FADE_S, "Übergang", 6,
            new int[]{3, 6, 10}, new String[]{"kurz (3 s)", "sanft (6 s)", "langsam (10 s)"});
    public static final Choice C_SLEEP_AFTER = new Choice(SLEEP_AFTER_S, "Spätestens schlafen legen", 0,
            new int[]{0, 1800, 3600, 7200}, new String[]{"nie", "nach 30 Minuten", "nach 1 Stunde", "nach 2 Stunden"});
    public static final Choice C_UNUSED_MAIN = new Choice(UNUSED_MAIN_S, "Oberer Bildschirm unbenutzt", 0,
            new int[]{0, 120, 300, 600, 1200}, new String[]{"aus", "nach 2 Minuten", "nach 5 Minuten", "nach 10 Minuten", "nach 20 Minuten"});
    public static final Choice C_UNUSED_SECOND = new Choice(UNUSED_SECOND_S, "Unterer Bildschirm unbenutzt", 300,
            new int[]{0, 120, 300, 600, 1200}, new String[]{"aus", "nach 2 Minuten", "nach 5 Minuten", "nach 10 Minuten", "nach 20 Minuten"});
    public static final Choice C_HUD_AFTER = new Choice(HUD_AFTER_S, "Abdunkeln, wenn unverändert seit", 180,
            new int[]{60, 180, 300, 600}, new String[]{"1 Minute", "3 Minuten", "5 Minuten", "10 Minuten"});
    public static final Choice C_HUD_STRENGTH = new Choice(HUD_STRENGTH, "Stärke", 45,
            new int[]{30, 45, 60}, new String[]{"sanft (30 %)", "mittel (45 %)", "stark (60 %)"});
    public static final Choice C_ANALYSIS = new Choice(ANALYSIS_S, "Bildanalyse", 15,
            new int[]{8, 15, 30}, new String[]{"genau (alle 8 s)", "ausgewogen (alle 15 s)", "sparsam (alle 30 s)"});

    public static final String[] STYLES = {STYLE_AURORA, STYLE_GLASS, STYLE_CLOCK, STYLE_BLACK};
    public static final String[] STYLE_LABELS = {"Aurora – dunkle, fließende Farben", "Glas – Inhalt schimmert gedämpft durch",
            "Uhr – nur Uhrzeit, wandert langsam", "Schwarz – Pixel aus, keine Animation"};
    public static final String[] UNUSED_STYLES = {UNUSED_DIM, UNUSED_SAVER};
    public static final String[] UNUSED_STYLE_LABELS = {"Abdunkeln – bleibt bedienbar", "Schoner – erste Berührung weckt"};

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int i(Context c, Choice ch) { return get(c).getInt(ch.key, ch.def); }
    public static boolean b(Context c, String key, boolean def) { return get(c).getBoolean(key, def); }
    public static String s(Context c, String key, String def) { return get(c).getString(key, def); }

    public static boolean enabled(Context c) { return b(c, ENABLED, true); }
    public static String style(Context c) { return s(c, STYLE, STYLE_AURORA); }
    public static String unusedStyle(Context c) { return s(c, UNUSED_STYLE, UNUSED_DIM); }
    public static boolean info(Context c) { return b(c, INFO, true); }
    public static boolean keepAwake(Context c) { return b(c, KEEP_AWAKE, true); }
    public static boolean firstKeyOnlyWakes(Context c) { return b(c, FIRST_KEY, true); }
    public static boolean hud(Context c, int role) { return b(c, role == 0 ? HUD_MAIN : HUD_SECOND, true); }

    public static GuardEngine.Config config(Context c) {
        GuardEngine.Config g = new GuardEngine.Config();
        g.enabled = enabled(c);
        g.idleMs = i(c, C_IDLE) * 1000L;
        g.gameIdleMs = i(c, C_GAME_IDLE) * 1000L;
        g.motionMaxMs = i(c, C_MOTION) * 1000L;
        g.fadeMs = i(c, C_FADE) * 1000L;
        int black = i(c, C_BLACK);
        // „Schwarz“ als Stil heißt: direkt schwarz.
        g.blackAfterMs = STYLE_BLACK.equals(style(c)) ? 0 : (black < 0 ? -1 : black * 1000L);
        g.unusedMs = new long[]{i(c, C_UNUSED_MAIN) * 1000L, i(c, C_UNUSED_SECOND) * 1000L};
        g.keysCountForSecond = b(c, KEYS_SECOND, false);
        g.sleepAfterTransfer = b(c, SLEEP_TRANSFER, false);
        g.sleepAfterMs = i(c, C_SLEEP_AFTER) * 1000L;
        return g;
    }

    public static Set<String> packages(Context c, String key) {
        Set<String> out = new HashSet<>();
        for (String line : s(c, key, "").split("[\\s,;]+")) {
            String p = line.trim().toLowerCase(Locale.ROOT);
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /** Paket zu einer Liste hinzufügen oder entfernen. */
    public static void toggle(Context c, String key, String pkg, boolean add) {
        Set<String> set = packages(c, key);
        if (add) set.add(pkg); else set.remove(pkg);
        StringBuilder sb = new StringBuilder();
        for (String p : set) sb.append(p).append('\n');
        get(c).edit().putString(key, sb.toString().trim()).apply();
    }

    public static boolean isExcluded(Context c, String pkg) {
        return pkg != null && !pkg.isEmpty() && packages(c, EXCLUDED).contains(pkg.toLowerCase(Locale.ROOT));
    }
}
