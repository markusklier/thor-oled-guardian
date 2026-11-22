package dev.thor.oledguardian;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** Einstellungen und Live-Status. Mit Touch und Controller (Steuerkreuz + A) bedienbar. */
public final class MainActivity extends Activity {
    static final int BG = 0xFF07090C, CARD = 0xFF10141A, LINE = 0xFF1C232C, TEXT = 0xFFE8EDF2, MUTED = 0xFF8C96A3,
            ACCENT = 0xFF73D8CD, WARN = 0xFFE0A84B;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout root;
    private float dp;

    // Live-Status
    private TextView serviceLine, probeLine, inputLine, foregroundLine, transferLine, timeoutLine;
    private Button serviceButton, probeButton, timeoutButton;
    private final List<TextView[]> roleLines = new ArrayList<>();
    private LinearLayout appsBox;
    private String appsShown = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = GuardPrefs.get(this);
        Probe.init(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = px(20);
        root.setPadding(pad, px(24), pad, px(40));
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));
        setContentView(scroll);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1_000);
        }
    };

    // ================================================================ Aufbau

    private void build() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View icon = new View(this);
        icon.setBackground(getDrawable(R.drawable.ic_guard));
        head.addView(icon, new LinearLayout.LayoutParams(px(44), px(44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(px(14), 0, 0, 0);
        titles.addView(text("Thor OLED Guardian", 24, TEXT, true));
        titles.addView(text("Schützt beide OLED-Bildschirme vor Einbrennen · " + version(), 13, MUTED, false));
        head.addView(titles);
        root.addView(head);

        // ---- Status
        LinearLayout status = card("Status");
        serviceLine = line(status);
        serviceButton = button(status, "Bedienungshilfe einschalten", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        probeLine = line(status);
        probeButton = button(status, "Shizuku-Freigabe erteilen", v -> Probe.request());
        inputLine = line(status);
        foregroundLine = line(status);
        transferLine = line(status);
        timeoutLine = line(status);
        timeoutButton = button(status, "Android-Timeout auf 30 Minuten stellen", v -> raiseTimeout());
        for (int r = 0; r < GuardianAccessibilityService.ROLES; r++) {
            TextView name = text("", 15, TEXT, true);
            name.setPadding(0, px(14), 0, px(2));
            status.addView(name);
            TextView[] lines = {name, line(status), line(status), line(status)};
            roleLines.add(lines);
        }
        button(status, "Schoner jetzt testen (beide Bildschirme)", v -> {
            GuardianAccessibilityService s = GuardianAccessibilityService.instance();
            if (s != null) s.blackoutNow();
        });

        // ---- Abwesenheit
        LinearLayout away = card("Wenn niemand den Thor bedient");
        hint(away, "Keine Taste, kein Stick, keine Berührung – z. B. während eines Downloads oder einer Installation. "
                + "Der Thor schläft dabei nicht ein; nur die Bildschirme werden geschont.");
        toggle(away, GuardPrefs.ENABLED, "Automatischer Schutz", "Auch über die Schnellkachel „OLED Guardian“", true);
        choice(away, GuardPrefs.C_IDLE);
        choice(away, GuardPrefs.C_GAME_IDLE);
        choice(away, GuardPrefs.C_MOTION, "Spielt außerhalb eines Spiels ein Film oder Video (Android meldet Medienwiedergabe), "
                + "wartet der Guardian. Läuft dabei Musik, gilt das auch – dann kommt der Schoner erst nach dieser Zeit.");
        styleChoice(away, GuardPrefs.STYLE, "Schoner", GuardPrefs.STYLES, GuardPrefs.STYLE_LABELS, GuardPrefs.STYLE_AURORA);
        choice(away, GuardPrefs.C_BLACK);
        choice(away, GuardPrefs.C_FADE);
        toggle(away, GuardPrefs.INFO, "Uhrzeit und Übertragung zeigen", "Klein und gedimmt oben; wandert jede Minute weiter", true);
        toggle(away, GuardPrefs.KEEP_AWAKE, "Thor wach halten", "Android schaltet nicht ab, Downloads laufen weiter", true);
        toggle(away, GuardPrefs.SLEEP_TRANSFER, "Nach Ende der Übertragung schlafen legen",
                "Wenn 2 Minuten lang nichts mehr geladen oder kopiert wird", false);
        choice(away, GuardPrefs.C_SLEEP_AFTER);

        // ---- Einzelner Bildschirm
        LinearLayout single = card("Ein Bildschirm wird nicht benutzt");
        hint(single, "Du spielst oben und berührst den unteren Bildschirm minutenlang nicht (Launcher, Temperaturanzeige, "
                + "Legende): Dann wird nur der untere geschont. „Abdunkeln“ lässt ihn sichtbar und bedienbar – "
                + "eine Berührung holt ihn sofort zurück.");
        choice(single, GuardPrefs.C_UNUSED_SECOND);
        choice(single, GuardPrefs.C_UNUSED_MAIN);
        styleChoice(single, GuardPrefs.UNUSED_STYLE, "Darstellung", GuardPrefs.UNUSED_STYLES, GuardPrefs.UNUSED_STYLE_LABELS,
                GuardPrefs.UNUSED_DIM);
        toggle(single, GuardPrefs.KEYS_SECOND, "Controller zählt auch für unten",
                "Einschalten, wenn du Spiele auf dem unteren Bildschirm mit Controller spielst", false);

        // ---- Spielschutz
        LinearLayout hud = card("Spielschutz (experimentell)");
        hint(hud, "Wie bei OLED-Fernsehern: Bereiche, die hell sind und sich lange nicht ändern (HUD, Lebensleiste, "
                + "Legenden), werden langsam und kaum merklich gedimmt – der Rest bleibt hell. Ändert sich dort etwas, "
                + "ist die Abdunkelung schnell wieder weg. Berührungen gehen immer durch.\n"
                + "Dafür macht der Guardian nur während eines Spiels alle 30 Sekunden ein Bildschirmfoto (sofort auf ein "
                + "grobes Helligkeitsraster verkleinert, nichts wird gespeichert). Ruckelt ein Spiel dadurch, wieder ausschalten.");
        toggle(hud, GuardPrefs.HUD_MAIN, "Oberer Bildschirm", null, false);
        toggle(hud, GuardPrefs.HUD_SECOND, "Unterer Bildschirm", null, false);
        choice(hud, GuardPrefs.C_HUD_AFTER);
        choice(hud, GuardPrefs.C_HUD_STRENGTH);

        // ---- Bedienung
        LinearLayout input = card("Aufwecken");
        toggle(input, GuardPrefs.FIRST_KEY, "Erste Taste weckt nur",
                "Sie wird nicht ans Spiel weitergegeben (Lautstärke und Power ausgenommen)", true);

        // ---- Apps
        LinearLayout apps = card("Apps");
        hint(apps, "Zuletzt geöffnete Apps. „Ausnehmen“ pausiert den Guardian, solange die App vorne ist. "
                + "Spiele bekommen die längere Wartezeit.");
        appsBox = new LinearLayout(this);
        appsBox.setOrientation(LinearLayout.VERTICAL);
        apps.addView(appsBox);
        packagesEditor(apps);

        // ---- Präzisionsmodus und Rechte
        LinearLayout extra = card("Präzisionsmodus und Rechte");
        hint(extra, "Mit Shizuku sieht der Guardian auch Analogsticks und Trigger (wichtig für Spiele, die nur damit "
                + "gesteuert werden), erkennt lokale Kopiervorgänge und kann den Thor nach einem Download schlafen legen. "
                + "Ohne Shizuku funktioniert alles andere genauso.");
        button(extra, "Shizuku öffnen", v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(Probe.SHIZUKU_PACKAGE);
            if (i != null) startActivity(i);
        });
        button(extra, "Einblenden über anderen Apps erlauben (Notlösung)", v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        button(extra, "Geräteadministrator (Sperren ohne Shizuku)", v -> {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, GuardianAdminReceiver.class));
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Nur zum Schlafenlegen nach einem Download.");
            startActivity(i);
        });

        TextView privacy = text("Datenschutz: keine Internet-Berechtigung, keine Speicherung. Tasten werden nicht aufgezeichnet – "
                + "nur der Zeitpunkt der letzten Eingabe. Bildschirmfotos gibt es nur mit eingeschaltetem Spielschutz; sie werden "
                + "sofort auf ein grobes Helligkeitsraster verkleinert und nur im Speicher verglichen. "
                + "Ersetzt nicht die Pixelpflege der AYN-Firmware.", 12, MUTED, false);
        privacy.setPadding(px(4), px(18), px(4), 0);
        root.addView(privacy);
    }

    // ================================================================ Live-Status

    private void refresh() {
        GuardianAccessibilityService s = GuardianAccessibilityService.instance();
        boolean on = s != null;
        serviceLine.setText(on ? "Bedienungshilfe: aktiv" + (GuardPrefs.enabled(this) ? "" : " · automatischer Schutz pausiert")
                : "Bedienungshilfe: aus – ohne sie kann der Guardian nichts erkennen");
        serviceLine.setTextColor(on ? TEXT : WARN);
        serviceButton.setVisibility(on ? View.GONE : View.VISIBLE);

        boolean needGrant = Probe.installed(this) && Probe.running() && !Probe.granted();
        probeLine.setText(on ? s.probeText() : "Präzisionsmodus: " + (Probe.granted() ? "freigegeben" : "nicht aktiv"));
        probeButton.setVisibility(needGrant ? View.VISIBLE : View.GONE);

        if (on) {
            inputLine.setText(s.inputText());
            foregroundLine.setText(s.foregroundText());
            transferLine.setText(s.transferText());
            List<GuardianAccessibilityService.RoleStatus> roles = s.roleStatus();
            for (int i = 0; i < roles.size() && i < roleLines.size(); i++) {
                GuardianAccessibilityService.RoleStatus r = roles.get(i);
                TextView[] l = roleLines.get(i);
                l[0].setText(r.name + ": " + r.mode);
                l[1].setText(r.display);
                l[2].setText(r.analysis);
                l[2].setVisibility(r.analysis.isEmpty() ? View.GONE : View.VISIBLE);
                l[3].setText(r.overlay);
                l[3].setVisibility(r.overlay.isEmpty() ? View.GONE : View.VISIBLE);
            }
            String recent = String.join(",", s.recentApps());
            if (!recent.equals(appsShown)) {
                appsShown = recent;
                buildApps(s.recentApps());
            }
        } else {
            inputLine.setText("");
            foregroundLine.setText("");
            transferLine.setText("");
        }

        int timeout = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0);
        long needed = GuardPrefs.i(this, GuardPrefs.C_IDLE) * 1000L + 30_000;
        boolean shortTimeout = timeout > 0 && timeout < needed;
        timeoutLine.setText("Android-Bildschirm-Timeout: " + (timeout / 60_000 > 0 ? timeout / 60_000 + " min" : timeout / 1000 + " s")
                + (shortTimeout ? " – kürzer als der Guardian: Android schaltet vorher ab" : ""));
        timeoutLine.setTextColor(shortTimeout ? WARN : MUTED);
        timeoutButton.setVisibility(shortTimeout ? View.VISIBLE : View.GONE);
    }

    private void raiseTimeout() {
        final int ms = 30 * 60_000;
        if (Probe.connected()) {
            Executors.newSingleThreadExecutor().execute(() -> Probe.setScreenTimeout(ms));
        } else if (Settings.System.canWrite(this)) {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, ms);
        } else {
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    private void buildApps(List<String> pkgs) {
        appsBox.removeAllViews();
        if (pkgs.isEmpty()) {
            appsBox.addView(text("Noch keine – öffne eine App oder ein Spiel.", 13, MUTED, false));
            return;
        }
        for (String pkg : pkgs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, px(6), 0, px(6));
            LinearLayout names = new LinearLayout(this);
            names.setOrientation(LinearLayout.VERTICAL);
            names.addView(text(GameApps.label(this, pkg), 15, TEXT, false));
            names.addView(text(pkg, 11, MUTED, false));
            row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            boolean excluded = GuardPrefs.isExcluded(this, pkg);
            boolean game = GameApps.isGame(this, pkg);
            row.addView(chip(excluded ? "Ausgenommen" : "Ausnehmen", excluded, v -> {
                GuardPrefs.toggle(this, GuardPrefs.EXCLUDED, pkg, !GuardPrefs.isExcluded(this, pkg));
                appsShown = "";
            }));
            row.addView(chip(game ? "Spiel" : "Kein Spiel", game, v -> {
                boolean now = GameApps.isGame(this, pkg);
                GuardPrefs.toggle(this, now ? GuardPrefs.GAMES : GuardPrefs.NOT_GAMES, pkg, false);
                GuardPrefs.toggle(this, now ? GuardPrefs.NOT_GAMES : GuardPrefs.GAMES, pkg, true);
                appsShown = "";
            }));
            appsBox.addView(row);
        }
    }

    private void packagesEditor(LinearLayout parent) {
        TextView label = text("Ausgenommene Pakete (eins pro Zeile)", 13, MUTED, false);
        label.setPadding(0, px(12), 0, px(4));
        parent.addView(label);
        EditText edit = new EditText(this);
        edit.setText(GuardPrefs.s(this, GuardPrefs.EXCLUDED, ""));
        edit.setTextColor(TEXT);
        edit.setHintTextColor(MUTED);
        edit.setHint("z. B. com.netflix.mediaclient");
        edit.setTextSize(14);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setMinLines(2);
        edit.setBackground(box(LINE, 0));
        edit.setPadding(px(12), px(10), px(12), px(10));
        edit.setOnFocusChangeListener((v, focus) -> {
            if (!focus) prefs.edit().putString(GuardPrefs.EXCLUDED, edit.getText().toString().trim()).apply();
        });
        parent.addView(edit, new LinearLayout.LayoutParams(-1, -2));
    }

    // ================================================================ Bausteine

    private LinearLayout card(String title) {
        TextView t = text(title, 13, ACCENT, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setPadding(px(4), px(26), 0, px(8));
        root.addView(t);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(CARD, 0));
        c.setPadding(px(16), px(10), px(16), px(12));
        root.addView(c, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    private TextView line(LinearLayout parent) {
        TextView t = text("", 14, TEXT, false);
        t.setPadding(0, px(3), 0, px(3));
        parent.addView(t);
        return t;
    }

    private void hint(LinearLayout parent, String s) {
        TextView t = text(s, 13, MUTED, false);
        t.setPadding(0, px(4), 0, px(8));
        t.setLineSpacing(0, 1.15f);
        parent.addView(t);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(BG);
        b.setTextSize(14);
        b.setBackground(focusable(ACCENT, 0xFFFFFFFF));
        b.setPadding(px(14), px(8), px(14), px(8));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px(6);
        lp.bottomMargin = px(6);
        parent.addView(b, lp);
        return b;
    }

    private TextView chip(String label, boolean active, View.OnClickListener l) {
        TextView t = text(label, 13, active ? BG : TEXT, false);
        t.setBackground(focusable(active ? ACCENT : LINE, ACCENT));
        t.setPadding(px(12), px(6), px(12), px(6));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void toggle(LinearLayout parent, String key, String title, String sub, boolean def) {
        LinearLayout row = row(parent);
        LinearLayout texts = texts(row, title, sub);
        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setClickable(false);
        sw.setFocusable(false);
        row.addView(sw);
        row.setOnClickListener(v -> {
            boolean now = !prefs.getBoolean(key, def);
            prefs.edit().putBoolean(key, now).apply();
            sw.setChecked(now);
        });
        texts.setDuplicateParentStateEnabled(true);
    }

    private void choice(LinearLayout parent, GuardPrefs.Choice ch) { choice(parent, ch, null); }

    private void choice(LinearLayout parent, GuardPrefs.Choice ch, String sub) {
        LinearLayout row = row(parent);
        texts(row, ch.title, sub);
        TextView value = text(ch.label(prefs.getInt(ch.key, ch.def)), 14, ACCENT, false);
        row.addView(value);
        row.setOnClickListener(v -> {
            int cur = prefs.getInt(ch.key, ch.def);
            int idx = 0;
            for (int i = 0; i < ch.values.length; i++) if (ch.values[i] == cur) idx = i;
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(ch.title)
                    .setSingleChoiceItems(ch.labels, idx, (d, which) -> {
                        prefs.edit().putInt(ch.key, ch.values[which]).apply();
                        value.setText(ch.labels[which]);
                        d.dismiss();
                    })
                    .setNegativeButton("Abbrechen", null)
                    .show();
        });
    }

    private void styleChoice(LinearLayout parent, String key, String title, String[] values, String[] labels, String def) {
        LinearLayout row = row(parent);
        texts(row, title, null);
        TextView value = text(shortLabel(labels, values, prefs.getString(key, def)), 14, ACCENT, false);
        row.addView(value);
        row.setOnClickListener(v -> {
            String cur = prefs.getString(key, def);
            int idx = 0;
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) idx = i;
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, idx, (d, which) -> {
                        prefs.edit().putString(key, values[which]).apply();
                        value.setText(shortLabel(labels, values, values[which]));
                        d.dismiss();
                    })
                    .setNegativeButton("Abbrechen", null)
                    .show();
        });
    }

    private static String shortLabel(String[] labels, String[] values, String v) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return labels[i].split(" – ")[0];
        return v;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(10), px(10), px(10), px(10));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(focusable(Color.TRANSPARENT, ACCENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = -px(10);
        lp.rightMargin = -px(10);
        parent.addView(row, lp);
        return row;
    }

    private LinearLayout texts(LinearLayout row, String title, String sub) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(title, 15, TEXT, false));
        if (sub != null) {
            TextView s = text(sub, 12, MUTED, false);
            s.setPadding(0, px(2), px(8), 0);
            t.addView(s);
        }
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        return t;
    }

    /** Hintergrund mit deutlichem Fokusrahmen – für die Bedienung mit dem Steuerkreuz. */
    private Drawable focusable(int fill, int focus) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable focused = box(fill == Color.TRANSPARENT ? 0x2273D8CD : fill, 0);
        focused.setStroke(px(2), focus);
        GradientDrawable pressed = box(fill == Color.TRANSPARENT ? 0x3373D8CD : fill, 0);
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, box(fill, 0));
        return s;
    }

    private GradientDrawable box(int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(14));
        if (stroke != 0) g.setStroke(px(1), stroke);
        return g;
    }

    private int px(int v) { return Math.round(v * dp); }

    private String version() {
        try {
            return "Version " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
