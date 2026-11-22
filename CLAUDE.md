# Thor OLED Guardian – Hinweise für den KI-Agenten

Android-App (Java, ohne Kotlin/AndroidX) für den AYN Thor: schont beide OLED-Bildschirme. Konzept und Bedienung
stehen in `README.md`. Gehört zu Markus (siehe auch Repository `handheld-atlas`, dort `data/setup/selftest.json`
für das, was auf seinem Thor wirklich geht: Shizuku läuft, Display 0 = oben 1920×1080, Display 4 = „Screen-2“ 1240×1080).

## Regeln

- Alle Texte in der App: **Deutsch mit echten Umlauten**.
- Keine Internet-Berechtigung, nichts speichern außer Einstellungen. Bildschirmfotos nur als Helligkeitsraster im Speicher.
- Nie `AccessibilityService.onMotionEvent`/Bewegungsquellen abonnieren: Android nimmt sie dann dem Spiel weg.
  Sticks nur lesend über `/dev/input` im Shizuku-Dienst.
- Overlays immer `FLAG_NOT_FOCUSABLE` (sonst verliert das Spiel den Fokus und pausiert).
- Entscheidungslogik gehört nach `app/src/main/java/.../engine/` (ohne Android-Klassen) und bekommt Tests in
  `app/src/test/`. Lokal prüfbar ohne SDK:
  `javac -cp junit.jar … engine/*.java` + `org.junit.runner.JUnitCore` (junit 4.13.2, hamcrest 1.3 von Maven Central).
  Den Android-Teil kann man lokal gegen `org.robolectric:android-all:13-robolectric-9030017` kompilieren (Stub-`R`).
- „Auf dem Thor getestet“ nur, wenn Markus es sagt. Bis dahin README-Stand „Vorabversion, ungetestet“.

## Build

Die Action `.github/workflows/android.yml` baut bei jedem Push (Tests + APK) und veröffentlicht ein Release
(`main` = neueste Version, sonst Vorabversion). Erst auf einem Branch pushen, nach `main` nur, wenn der Build grün ist.
