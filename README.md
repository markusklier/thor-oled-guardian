# Thor OLED Guardian

Schützt **beide OLED-Bildschirme des AYN Thor** vor Einbrennen und Nachbildern. Er arbeitet unabhängig davon, ob
gerade der Launcher, eine App oder ein Emulator läuft, und fühlt sich an wie eine Funktion des Geräts. Kein Root nötig.

> **Stand 0.3 – Vorabversion, noch nicht auf dem Thor getestet.** Die Entscheidungslogik ist mit 22 Tests geprüft;
> wie sich Overlays und Berührungs-Erkennung auf dem zweiten Bildschirm des Thor verhalten, zeigt erst der
> Gerätetest (Checkliste unten).

## Das Problem

- Bei Downloads und Installationen dreht sich irgendwo ein Kreis oder eine Zahl zählt hoch. Deshalb hält der Thor
  das Bild nicht für statisch, und sein eigener Schutz greift nicht. Bisherige Notlösung: unteren Bildschirm per
  Knopf aus, oben „Blacker“.
- Den Thor schlafen zu lassen ist keine Lösung, denn dann bricht der Download ab.
- Beim Spielen auf dem oberen Bildschirm steht unten oft minutenlang dasselbe: Launcher, AYN-Temperaturanzeige,
  Legende oder HUD eines Dual-Screen-Spiels. Auch das brennt ein.
- Ein Schoner darf **nicht** mitten in einer Zwischensequenz oder einem Film anspringen.

## Wie der Guardian entscheidet

Er schaut, **was gerade passiert**, nicht, was angezeigt wird. Bildschirmfotos braucht er dafür nicht.

| Signal | Woher |
|---|---|
| **Eingaben**: Tasten, Berührungen je Bildschirm, mit Shizuku auch Sticks und Trigger | Bedienungshilfe (Tastenfilter, unsichtbarer 1-Pixel-Berührungsfühler je Bildschirm), Shizuku liest `/dev/input` |
| **Spiel oder App** im Vordergrund | Paketname aus der Bedienungshilfe, Android-Kategorie „Spiel“, Liste bekannter Emulatoren |
| **Medienwiedergabe** (Film, Video, Musik) | Android meldet laufende Wiedergabe, ohne Zusatzrecht |
| **Übertragung** | Netzwerkzähler von Android, mit Shizuku auch Speicherzugriffe (lokales Kopieren) |

| Situation | Erkennung | Verhalten |
|---|---|---|
| **Download / Installation** | keine Eingabe, kein Medienton | nach 2 min Schoner, 3 min später schwarz, Thor bleibt wach; Anzeige „Download 12 MB/s“ |
| **Film, YouTube** | Medienwiedergabe außerhalb eines Spiels | kein Schoner, erst nach 30 min ohne Eingabe (einstellbar) |
| **Spiel mit Zwischensequenz** | Spiel oder Emulator im Vordergrund | erst nach 10 min ohne Taste |
| **Spiel pausiert, du gehst weg** | Spiel + 10 min nichts | Schoner |
| **Unten statisch, oben wird gespielt** | 5 min keine Berührung unten | unten nur abdunkeln, bleibt sichtbar und bedienbar; eine Berührung holt ihn zurück |

### Der Schoner
1. Sanfter Übergang (6 s) in den gewählten Stil:
   - **Aurora**: dunkle, langsam fließende Farbflächen, bei denen kein Pixel stillsteht
   - **Glas**: der Inhalt schimmert gedämpft durch, darüber wandert ein weicher Lichtschein
   - **Uhr**: nur die Uhrzeit (und die Übertragung), die jede Minute weiterwandert
   - **Schwarz**: Pixel aus, keine Animation
2. Nach **3 Minuten** komplett schwarz, ohne Animation. Nur eine kleine, gedimmte Zeile mit Uhrzeit und Datenrate
   wandert jede Minute weiter.
3. Der Thor **bleibt wach**, damit Downloads weiterlaufen. Wer will, lässt ihn nach dem Ende der Übertragung schlafen.
4. Jede Eingabe weckt beide Bildschirme. Die erste Taste weckt nur und erreicht das Spiel nicht.
5. Kachel **„Bildschirme aus“**: beide Bildschirme sofort schwarz, der Thor bleibt wach.

### Spielschutz (experimentell, standardmäßig aus)
Wie die Logo-Abdunkelung moderner OLED-Fernseher. Helle Bereiche, die sich seit 3 Minuten nicht verändert haben (HUD,
Lebensleiste, Legenden), werden kaum merklich um 30–60 % gedimmt; der Rest bleibt hell. Dafür ist ein Blick aufs Bild
nötig: Der Guardian macht dann, **nur während ein Spiel läuft**, alle 30 s ein Bildschirmfoto (alle 10 s, solange etwas
gedimmt ist), verkleinert es sofort auf 1 Helligkeitswert je 8×8 Pixel und vergleicht es nur im Speicher. Ruckelt ein
Spiel dadurch, den Spielschutz wieder ausschalten.

### Ressourcen
Ohne Spielschutz macht der Dienst im Hintergrund einen Takt pro Sekunde und sonst nichts. Animationen laufen nur in der
Aurora- und der Glas-Phase, mit 15 Bildern/s; im Schwarz-Zustand gibt es keine.

### Brauche ich Shizuku?
Nein. Ohne Shizuku fehlen nur Analogsticks und analoge Trigger, lokale Kopiervorgänge und das Schlafenlegen über
Shizuku. Das Schlafenlegen geht auch über den Geräteadministrator. Kritisch ist nur, wenn man in einem Spiel über
10 Minuten ausschließlich mit dem Stick spielt, ohne eine Taste zu drücken. Wird Shizuku später gestartet, verbindet
sich der Guardian von selbst. Shizuku ab 13.6 kann unter Android 13 im vertrauenswürdigen WLAN ohne Root automatisch
starten.

## Installieren

1. APK aus dem neuesten [Release](https://github.com/markusklier/thor-oled-guardian/releases) laden.
   Alternativ in **Obtainium** dieses Repository eintragen und „Vorabversionen einbeziehen“ einschalten.
2. Installieren. Weil die App nicht aus dem Play Store kommt, sperrt Android 13 zunächst die Bedienungshilfe:
   **Einstellungen → Apps → Thor OLED Guardian → ⋮ → Eingeschränkte Einstellungen zulassen**.
3. In der App auf **Bedienungshilfe einschalten** tippen und „Thor OLED Guardian“ aktivieren.
4. Optional **Präzisionsmodus**: Läuft Shizuku, in der App **Shizuku-Freigabe erteilen** antippen. Dann erkennt der
   Guardian auch Analogsticks und Trigger.
5. Die Schnellkacheln hinzufügen (Schnelleinstellungen → Bearbeiten):
   - **OLED Guardian**: automatischen Schutz pausieren oder fortsetzen
   - **Bildschirme aus**: beide Bildschirme sofort schwarz, der Thor bleibt wach; die nächste Eingabe weckt
6. Ist der **Android-Bildschirm-Timeout** kürzer als die Wartezeit des Guardians, zeigt die App eine Warnung und
   einen Knopf, um ihn auf 30 Minuten zu stellen. Sonst schaltet Android vorher ab und der Download bricht ab.

Den AYN-eigenen Schutz würde ich vorerst eingeschaltet lassen. Der Guardian ersetzt nicht die Pixelpflege der Firmware.

## Gerätetest – bitte einmal durchgehen

| # | Test | Erwartung |
|---|---|---|
| 1 | App öffnen, Status | Beide Bildschirme werden erkannt (oben ID 0, 1920×1080, unten „Screen-2“ 1240×1080) |
| 2 | „Schoner jetzt testen“ | Beide Bildschirme blenden ab; Taste oder Berührung weckt beide |
| 3 | Kachel „Bildschirme aus“ | Wie 2, Schnelleinstellungen schließen sich |
| 4 | Status → „Overlay“ je Bildschirm | Weg 1 oder 2; Weg 3 nur mit Erlaubnis „über anderen Apps“ |
| 5 | Download starten, 2 min nichts tun | Schoner mit „Download x MB/s“; nach 3 min schwarz; der Download läuft weiter |
| 6 | YouTube-Video 5 min ohne Eingabe | Kein Schoner; Status zeigt „Medienwiedergabe läuft“ |
| 7 | Spiel oben, unten Launcher, 5 min nur Controller | Unten dunkelt ab; Berührung unten holt ihn zurück; oben stört nichts |
| 8 | Spiel pausieren, 10 min weggehen | Schoner auf beiden; erste Taste weckt, ohne im Spiel etwas auszulösen |
| 9 | Optional: Spielschutz an, Spiel mit HUD 5 min | Status zeigt „x Bereiche abgedunkelt“, HUD leicht gedimmt; auf Ruckler achten |

## Grenzen

- **Schwarz ist nicht ausgeschaltet**: Die Pixel leuchten nicht mehr, das Panel bleibt aber an (wie bei „Blacker“).
  Für das Einbrennen ist das gleichwertig, Strom spart es etwas weniger.
- Medienwiedergabe heißt für Android auch Musik. Läuft während eines Downloads Musik, kommt der Schoner erst nach der
  Video-Wartezeit (30 min). Welche App spielt, verrät Android ohne Zusatzrechte nicht.
- Zwischensequenzen über 10 Minuten ohne Taste werden unterbrochen. Dann die Spiel-Wartezeit hochstellen.
- Ohne Shizuku sieht der Guardian keine Stickbewegungen. Spiele, die lange nur mit Stick gespielt werden, also
  ausnehmen oder die Spiel-Wartezeit verlängern.
- Statusleiste und AYN-Overlays liegen vermutlich unter dem Overlay der Bedienungshilfe. Ob das auf dem Thor immer gilt,
  zeigt der Test.

## Datenschutz

Keine Internet-Berechtigung, kein Speicherzugriff. Tasten werden nicht aufgezeichnet, nur der Zeitpunkt der letzten
Eingabe. Bildschirmfotos gibt es nur mit eingeschaltetem Spielschutz; sie werden sofort auf ein grobes
Helligkeitsraster verkleinert und nur im Arbeitsspeicher verglichen, nichts wird gespeichert oder gesendet. Fensterinhalte und Texte liest der Dienst nicht
(`canRetrieveWindowContent=false`).

## Entwicklung

- Die APK baut die Action **Android-App** bei jedem Push: auf `main` als Release, auf anderen Branches als
  Vorabversion. Android Studio ist nicht nötig.
- Lokal: `./gradlew :app:testReleaseUnitTest :app:assembleRelease` (Android SDK nötig).
- Aufbau:
  - `engine/` ist reine Logik mit Tests: `GuardEngine` (Zustände je Bildschirm), `StaticMap` (Spielschutz-Analyse),
    `MaskAnimator`, `TransferMeter`, `Gamepads`.
  - `GuardianAccessibilityService`: Eingaben, Medienwiedergabe, Overlays, Spielschutz
  - `ProbeService`/`Probe`: Präzisionsmodus über Shizuku
  - `SaverView`, `MaskView`, `MainActivity`, Kacheln
- Signatur: Vorab-Schlüssel `guardian-dev.keystore` im Repository, damit Updates ohne Deinstallieren gehen. Ein eigener
  Schlüssel lässt sich über die Secrets `GUARDIAN_KEYSTORE_B64` und `GUARDIAN_KEYSTORE_PASSWORD` einsetzen.

MIT-Lizenz. Keine Verbindung zu AYN.
