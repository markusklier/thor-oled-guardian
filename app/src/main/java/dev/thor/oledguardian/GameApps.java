package dev.thor.oledguardian;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Ist die App im Vordergrund ein Spiel oder Emulator? Dann gilt die längere Wartezeit.
 * Quellen: Android-Kategorie „Spiel“, eine Liste bekannter Emulatoren (aus Handheld Atlas) und eigene Listen.
 */
final class GameApps {
    private GameApps() {}

    private static final Set<String> KNOWN = new HashSet<>(Arrays.asList(
            "dev.eden.eden_emulator", "dev.eden.eden_nightly", "org.citron.citron_emu", "app.gamenative", "gamehub.lite",
            "com.winlator", "com.droiddeck.launcher", "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold",
            "org.dolphinemu.dolphinemu", "org.azahar_emu.azahar", "com.github.stenzek.duckstation",
            "xyz.aethersx2.android", "com.armsx2", "com.armsx2.nightly", "org.vita3k.emulator", "me.magnum.melonds",
            "me.magnum.melonds.nightly", "com.flycast.emulator", "info.cemu.cemu", "com.retroarch.aarch64",
            "com.retroarch", "com.izzy2lost.x1box", "xendroid.compose", "com.dsemu.drastic",
            "org.mupen64plusae.v3.fzurita", "org.citra.citra_emu", "org.citra.emu", "io.github.lime3ds.android",
            "skyline.emu", "org.yuzu.yuzu_emu"));
    private static final String[] PREFIXES = {"com.explusalpha.", "com.epicgames.", "com.valvesoftware."};

    private static final Map<String, Boolean> cache = new HashMap<>();

    static boolean isGame(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (GuardPrefs.packages(c, GuardPrefs.NOT_GAMES).contains(pkg)) return false;
        if (GuardPrefs.packages(c, GuardPrefs.GAMES).contains(pkg)) return true;
        Boolean hit = cache.get(pkg);
        if (hit != null) return hit;
        boolean game = KNOWN.contains(pkg);
        for (String p : PREFIXES) if (pkg.startsWith(p)) game = true;
        if (!game) {
            try {
                ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
                game = ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
            } catch (PackageManager.NameNotFoundException ignored) {
                // nicht sichtbar oder deinstalliert
            }
        }
        cache.put(pkg, game);
        return game;
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
