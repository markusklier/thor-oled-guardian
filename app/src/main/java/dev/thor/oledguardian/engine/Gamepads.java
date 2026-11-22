package dev.thor.oledguardian.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Erkennt Controller in der Ausgabe von {@code getevent -lp} und bewertet Stick-/Trigger-Bewegungen.
 * Läuft im Shizuku-Dienst (Shell-Rechte); hier ohne Android-Klassen, damit es testbar ist.
 * Es werden nur Zeitpunkte gemerkt, nie welche Taste gedrückt wurde.
 */
public final class Gamepads {
    public static final int EV_KEY = 0x01;
    public static final int EV_ABS = 0x03;

    public static final class Axis {
        public final int code, min, max;
        Axis(int code, int min, int max) { this.code = code; this.min = min; this.max = max; }
    }

    public static final class Device {
        public String path = "";
        public String name = "";
        public final Set<String> keys = new HashSet<>();
        public final Map<Integer, Axis> axes = new HashMap<>();
        public boolean accelerometer;

        public boolean touchscreen() { return axes.containsKey(0x35) || axes.containsKey(0x2f); }

        public boolean gamepad() {
            if (touchscreen() || accelerometer) return false;
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("accel") || n.contains("gyro") || n.contains("sensor") || n.contains("imu")) return false;
            boolean buttons = keys.contains("BTN_A") || keys.contains("BTN_SOUTH") || keys.contains("BTN_GAMEPAD")
                    || keys.contains("BTN_THUMBL") || keys.contains("0130") || keys.contains("013d");
            boolean sticks = axes.containsKey(0x00) || axes.containsKey(0x03) || axes.containsKey(0x10);
            return buttons && sticks;
        }
    }

    private static final Map<String, Integer> ABS = new HashMap<>();
    static {
        String[] names = {"ABS_X", "ABS_Y", "ABS_Z", "ABS_RX", "ABS_RY", "ABS_RZ", "ABS_THROTTLE", "ABS_RUDDER",
                "ABS_WHEEL", "ABS_GAS", "ABS_BRAKE"};
        for (int i = 0; i < names.length; i++) ABS.put(names[i], i);
        for (int i = 0; i < 8; i++) ABS.put("ABS_HAT" + (i / 2) + (i % 2 == 0 ? "X" : "Y"), 0x10 + i);
        ABS.put("ABS_MT_SLOT", 0x2f);
        ABS.put("ABS_MT_POSITION_X", 0x35);
        ABS.put("ABS_MT_POSITION_Y", 0x36);
    }

    private static final Pattern ADD = Pattern.compile("^add device \\d+: (\\S+)");
    private static final Pattern NAME = Pattern.compile("^\\s*name:\\s+\"(.*)\"");
    private static final Pattern SECTION = Pattern.compile("^\\s*([A-Z]+) \\(([0-9a-fA-F]{4})\\):(.*)$");
    private static final Pattern AXIS = Pattern.compile("(\\S+)\\s*: value -?\\d+, min (-?\\d+), max (-?\\d+)");

    public static List<Device> parse(String out) {
        List<Device> list = new ArrayList<>();
        if (out == null) return list;
        Device cur = null;
        String section = "";
        for (String raw : out.split("\n")) {
            String line = raw.replace("\r", "");
            Matcher m = ADD.matcher(line);
            if (m.find()) {
                cur = new Device();
                cur.path = m.group(1);
                list.add(cur);
                section = "";
                continue;
            }
            if (cur == null) continue;
            m = NAME.matcher(line);
            if (m.find()) { cur.name = m.group(1); continue; }
            String body;
            m = SECTION.matcher(line);
            if (m.find()) {
                section = m.group(1);
                body = m.group(3);
            } else if (line.trim().startsWith("input props:")) {
                section = "PROPS";
                continue;
            } else if (line.trim().startsWith("events:")) {
                section = "";
                continue;
            } else {
                body = line;
            }
            switch (section) {
                case "KEY":
                    for (String t : body.trim().split("\\s+")) if (!t.isEmpty()) cur.keys.add(t);
                    break;
                case "ABS":
                    Matcher a = AXIS.matcher(body);
                    while (a.find()) {
                        Integer code = code(a.group(1));
                        if (code != null) cur.axes.put(code, new Axis(code, Integer.parseInt(a.group(2)), Integer.parseInt(a.group(3))));
                    }
                    break;
                case "PROPS":
                    String t = body.trim();
                    if (t.contains("INPUT_PROP_ACCELEROMETER") || t.equals("0006")) cur.accelerometer = true;
                    break;
                default:
                    break;
            }
        }
        return list;
    }

    private static Integer code(String label) {
        Integer c = ABS.get(label);
        if (c != null) return c;
        if (label.matches("[0-9a-fA-F]{4}")) return Integer.parseInt(label, 16);
        return null;
    }

    /** Bewertet die Achsen eines Controllers: Zittern in Ruhe zählt nicht, echte Bewegung schon. */
    public static final class AxisFilter {
        private final Map<Integer, Axis> axes;
        private final Map<Integer, Integer> lastCounted = new HashMap<>();
        private final Map<Integer, Integer> current = new HashMap<>();
        /** Anteil des Wertebereichs, ab dem eine Änderung als Eingabe zählt. */
        public float threshold = 0.12f;

        public AxisFilter(Map<Integer, Axis> axes) { this.axes = axes; }

        /** @return true, wenn dieses Achsenereignis als Eingabe zählt. */
        public boolean onAbs(int code, int value) {
            current.put(code, value);
            if (code >= 0x10 && code <= 0x17) return value != 0;   // Steuerkreuz als Achse
            Axis ax = axes.get(code);
            if (ax == null || code > 0x0a) return false;
            int range = Math.max(1, ax.max - ax.min);
            Integer last = lastCounted.get(code);
            if (last == null) {
                lastCounted.put(code, value);
                return false;
            }
            if (Math.abs(value - last) >= range * threshold) {
                lastCounted.put(code, value);
                return true;
            }
            return false;
        }

        /** Ein Stick wird gerade deutlich ausgelenkt gehalten (z. B. Dauerlauf, Gas gedrückt). */
        public boolean held() {
            for (Map.Entry<Integer, Integer> e : current.entrySet()) {
                Axis ax = axes.get(e.getKey());
                if (ax == null || e.getKey() > 0x0a) continue;
                boolean trigger = e.getKey() == 0x02 || e.getKey() == 0x05 || e.getKey() == 0x09 || e.getKey() == 0x0a;
                float half = (ax.max - ax.min) / 2f;
                if (half <= 0) continue;
                float rest = trigger && ax.min >= 0 ? ax.min : (ax.max + ax.min) / 2f;
                float span = trigger && ax.min >= 0 ? ax.max - ax.min : half;
                if (Math.abs(e.getValue() - rest) > span * 0.5f) return true;
            }
            return false;
        }
    }
}
