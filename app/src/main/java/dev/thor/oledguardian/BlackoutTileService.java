package dev.thor.oledguardian;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Schnellkachel „Bildschirme aus“: beide Bildschirme sofort schwarz, der Thor bleibt wach (Download läuft weiter).
 * Ersetzt „unteren Bildschirm per Knopf aus + Blacker oben“. Die nächste Taste oder Berührung weckt.
 */
public final class BlackoutTileService extends TileService {
    @Override public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean service = GuardianAccessibilityService.instance() != null;
        t.setState(service ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        t.setLabel("Bildschirme aus");
        t.setSubtitle(service ? "Thor bleibt wach" : "Bedienungshilfe aus");
        t.updateTile();
    }

    @Override public void onClick() {
        GuardianAccessibilityService s = GuardianAccessibilityService.instance();
        if (s == null) {
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(i);
            return;
        }
        s.blackoutNow();
        s.collapseShade();   // Schnelleinstellungen schließen, damit der Schoner sofort sichtbar ist
    }
}
