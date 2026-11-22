package dev.thor.oledguardian;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Schnellkachel „OLED Guardian“: automatischen Schutz ein- und ausschalten (z. B. vor einem Film). */
public final class GuardianTileService extends TileService {
    @Override public void onStartListening() { refresh(); }

    @Override public void onClick() {
        boolean on = !GuardPrefs.enabled(this);
        GuardPrefs.get(this).edit().putBoolean(GuardPrefs.ENABLED, on).apply();
        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = GuardPrefs.enabled(this);
        boolean service = GuardianAccessibilityService.instance() != null;
        t.setState(on && service ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("OLED Guardian");
        t.setSubtitle(!service ? "Bedienungshilfe aus" : (on ? "Schützt" : "Pausiert"));
        t.updateTile();
    }
}
