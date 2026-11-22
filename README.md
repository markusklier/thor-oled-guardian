# Thor OLED Guardian

Schützt **beide OLED-Bildschirme des AYN Thor** vor Einbrennen und Nachbildern. Er arbeitet unabhängig davon, ob
gerade der Launcher, eine App oder ein Emulator läuft, und fühlt sich an wie eine Funktion des Geräts. Kein Root nötig.

> **Stand 0.2 – Vorabversion, noch nicht auf dem Thor getestet.** Die Entscheidungslogik ist mit 22 Tests geprüft;
> wie sich Overlays, Bildschirmfotos und Berührungs-Erkennung auf dem zweiten Bildschirm des Thor verhalten, zeigt
> erst der Gerätetest (Checkliste unten).

## Das Problem

- Bei Downloads und Installationen dreht sich irgendwo ein Kreis oder eine Zahl zählt hoch. Deshalb hält der Thor
  das Bild nicht für statisch, und sein eigener Schutz greift nicht. Bisherige Notlösung: unteren Bildschirm per
  Knopf aus, oben „Blacker“.
- Den Thor schlafen zu lassen ist keine Lösung, denn dann bricht der Download ab.
- Beim Spielen auf dem oberen Bildschirm steht unten oft minutenlang dasselbe: Launcher, AYN-Temperaturanzeige,
  Legende oder HUD eines Dual-Screen-Spiels. Auch das brennt ein.
- Ein Schoner darf **nicht** mitten in einer Zwischensequenz oder einem Film anspringen.

## Wie der Guardian entscheidet

Er wertet drei Signale aus, alle ohne Root:

| Signal | Woher | Wofür |
|---|---|---|
| **Eingaben**: Tasten, Berührungen je Bildschirm, mit Shizuku auch Sticks und Trigger | Bedienungshilfe (Tastenfilter, unsichtbarer 1-Pixel-Berührungsfühler je Bildschirm), Shizuku liest `/dev/input` | Ist jemand da? Welcher Bildschirm wird benutzt? |
| **Bildanalyse**: Was hat sich seit dem letzten Mal verändert? | Bildschirmfoto der Bedienungshilfe alle 15 s, sofort verkleinert auf 1 Helligkeitswert je 8×8 Pixel | Film oder Zwischensequenz (viel Bewegung) vs. Download-Fenster (fast alles steht still); welche hellen Bereiche stehen seit Minuten still? |
| **Übertragung** | Netzwerkzähler von Android, mit Shizuku auch die Speicherzugriffe (lokales Kopieren) | Anzeige „Download 12,3 MB/s“ im Schoner; auf Wunsch nach dem Ende schlafen legen |

Daraus ergeben sich drei Schutzstufen:

### 1. Niemand bedient den Thor (beide Bildschirme)
Keine Taste, kein Stick, keine Berührung für **2 Minuten**, in Spielen und Emulatoren für **10 Minuten**:

1. Sanfter Übergang (6 s) in den gewählten **Schoner**:
   - **Aurora**: dunkle, langsam fließende Farbflächen, bei denen kein Pixel stillsteht
   - **Glas**: der Inhalt schimmert gedämpft durch, darüber wandert ein weicher Lichtschein
   - **Uhr**: nur die Uhrzeit (und die Übertragung), die jede Minute weiterwandert
   - **Schwarz**: Pixel aus, keine Animation
2. Nach **3 Minuten** komplett schwarz: Die OLED-Pixel sind aus, es läuft keine Animation mehr. Nur eine kleine, gedimmte
   Zeile mit Uhrzeit und Datenrate wandert jede Minute an eine neue Stelle.
3. Der Thor **bleibt wach**, damit Downloads weiterlaufen. Wer will, lässt ihn nach dem Ende der Übertragung schlafen.
4. **Bewegte Inhalte** wie Filme oder Zwischensequenzen halten den Schoner auf, standardmäßig bis 45 Minuten.
5. Jede Eingabe weckt beide Bildschirme. Die erste Taste weckt nur und erreicht das Spiel nicht.

### 2. Ein Bildschirm wird nicht benutzt
Du spielst oben, unten wurde seit **5 Minuten** nichts berührt, und dort bewegt sich nichts. Dann wird **nur der
untere** geschont. Standard ist **Abdunkeln**: Der Bildschirm bleibt bedienbar, und eine Berührung holt ihn sofort
zurück. Eine Live-Karte, die sich bewegt, bleibt an. Controller-Tasten wecken den unteren Bildschirm nicht, denn die
gehören zum Spiel oben (lässt sich umstellen).

### 3. Spielschutz: statische Bereiche abdunkeln
Wie die Logo-Abdunkelung moderner OLED-Fernseher. Zellen von 64×64 Pixeln, die **hell sind und sich seit 3 Minuten
nicht verändert haben**, werden über 20 Sekunden kaum merklich um 30–60 % gedimmt. Das betrifft HUD, Lebensleiste,
Minimap-Rahmen, Legenden und die AYN-Temperaturanzeige. Der Rest des Bildes bleibt voll hell. Sobald sich in einer
Zelle etwas ändert, ist ihre Abdunkelung in einer halben Sekunde weg. Berührungen gehen immer durch, und das Overlay
deckt nur das Rechteck um die gedimmten Zellen ab. Die eigene Abdunkelung wird bei der nächsten Analyse
herausgerechnet, damit sie nicht selbst als „statisch“ zählt.

### Ressourcen
- 1 Takt pro Sekunde, sonst nichts.
- Ein Bildschirmfoto alle 15 s, und nur wenn es gebraucht wird: Spielschutz an, ein Bildschirm ist unbenutzt, oder
  die Abwesenheit steht kurz bevor. Läuft ein Schoner, wird nicht fotografiert.
- Animationen laufen nur in der Aurora- und der Glas-Phase, mit 15 Bildern/s; im Schwarz-Zustand gibt es keine.

## Installieren

1. APK aus dem neuesten [Release](https://github.com/markusklier/thor-oled-guardian/releases) laden.
   Alternativ in **Obtainium** dieses Repository eintragen und „Vorabversionen einbeziehen“ einschalten.
2. Installieren. Weil die App nicht aus dem Play Store kommt, sperrt Android 13 zunächst die Bedienungshilfe:
   **Einstellungen → Apps → Thor OLED Guardian → ⋮ → Eingeschränkte Einstellungen zulassen**.
3. In der App auf **Bedienungshilfe einschalten** tippen und „Thor OLED Guardian“ aktivieren.
4. **Präzisionsmodus**: Shizuku läuft auf dem Thor bereits (wie für Handheld Atlas). In der App
   **Shizuku-Freigabe erteilen** antippen. Dann erkennt der Guardian auch Analogsticks und Trigger. Das ist wichtig für
   Spiele, die nur damit gesteuert werden, zum Beispiel Rennspiele.
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
| 5 | Status → Bildanalyse unten | „Bild ruhig/bewegt … vor x s“, Karte füllt sich; bei „abgelehnt“ den Code notieren |
| 6 | Download starten, 2 min nichts tun | Schoner mit „Download x MB/s“; nach 3 min schwarz; der Download läuft weiter |
| 7 | Video 5 min ohne Eingabe | Kein Schoner (Bild „bewegt“) |
| 8 | Spiel oben, unten Launcher, 5 min nur Controller | Unten dunkelt ab; Berührung unten holt ihn zurück; oben stört nichts |
| 9 | Spiel mit HUD 5 min | HUD-Bereiche werden in der Karte orange, dann rot, und sind auf dem Gerät leicht gedimmt |
| 10 | Rennspiel nur mit Stick/Trigger (Präzisionsmodus) | Kein Schoner, solange gefahren wird |
| 11 | Ruckler? | Bei „genau (8 s)“ und „sparsam (30 s)“ vergleichen und melden, falls ein Bildschirmfoto spürbar ruckelt |

## Grenzen

- **Schwarz ist nicht ausgeschaltet**: Die Pixel leuchten nicht mehr, das Panel bleibt aber an (wie bei „Blacker“).
  Für das Einbrennen ist das gleichwertig, Strom spart es etwas weniger.
- Geschützte Videos (DRM, zum Beispiel Netflix) erscheinen im Bildschirmfoto schwarz. Dann entscheidet der Ton: Läuft
  außerhalb eines Spiels Ton, gilt das Bild als Video.
- Ohne Shizuku sieht der Guardian keine Stickbewegungen. Spiele, die nur mit Stick gespielt werden, also ausnehmen oder
  die Spiel-Wartezeit verlängern.
- Statusleiste und AYN-Overlays liegen vermutlich unter dem Overlay der Bedienungshilfe. Ob das auf dem Thor immer gilt,
  zeigt der Test.

## Datenschutz

Keine Internet-Berechtigung, kein Speicherzugriff. Bildschirmfotos werden sofort auf ein grobes Helligkeitsraster
verkleinert und nur im Arbeitsspeicher verglichen; nichts wird gespeichert oder gesendet. Tasten werden nicht
aufgezeichnet, nur der Zeitpunkt der letzten Eingabe. Fensterinhalte und Texte liest der Dienst nicht
(`canRetrieveWindowContent=false`).

## Entwicklung

- Die APK baut die Action **Android-App** bei jedem Push: auf `main` als Release, auf anderen Branches als
  Vorabversion. Android Studio ist nicht nötig.
- Lokal: `./gradlew :app:testReleaseUnitTest :app:assembleRelease` (Android SDK nötig).
- Aufbau:
  - `engine/` ist reine Logik mit Tests: `GuardEngine` (Zustände je Bildschirm), `StaticMap` (Bildanalyse),
    `MaskAnimator`, `TransferMeter`, `Gamepads`.
  - `GuardianAccessibilityService`: Eingaben, Overlays, Bildschirmfotos
  - `ProbeService`/`Probe`: Präzisionsmodus über Shizuku
  - `SaverView`, `MaskView`, `MainActivity`, Kacheln
- Signatur: Vorab-Schlüssel `guardian-dev.keystore` im Repository, damit Updates ohne Deinstallieren gehen. Ein eigener
  Schlüssel lässt sich über die Secrets `GUARDIAN_KEYSTORE_B64` und `GUARDIAN_KEYSTORE_PASSWORD` einsetzen.

MIT-Lizenz. Keine Verbindung zu AYN.
