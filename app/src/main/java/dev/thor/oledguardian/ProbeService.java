package dev.thor.oledguardian;

import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.SystemClock;

import dev.thor.oledguardian.engine.Gamepads;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * „Präzisionsmodus“: läuft in einem eigenen Prozess, den Shizuku mit Shell-Rechten startet (wie „adb shell“,
 * ohne Root). Nur die Guardian-App kann ihn erreichen.
 *
 * Was er tut – und nur das:
 *  - Controller lesen (/dev/input), um zu merken, WANN Sticks/Tasten zuletzt bewegt wurden. Die Ereignisse gehen
 *    unverändert weiter ans Spiel; es wird nichts gespeichert, nichts weitergegeben, keine Tastenfolge gemerkt.
 *  - Summe der gelesenen/geschriebenen Bytes aller Speicher (/proc/diskstats) – erkennt lokale Kopien.
 *  - Ersatz-Bildanalyse mit {@code screencap}, falls Android der Bedienungshilfe kein Bildschirmfoto gibt.
 *  - Feste Befehle: Thor schlafen legen, Android-Bildschirm-Timeout setzen.
 */
public final class ProbeService extends Binder {
    static final String DESCRIPTOR = "dev.thor.oledguardian.Probe";
    static final int TX_STATUS = 1, TX_INPUT = 2, TX_DISK = 3, TX_SLEEP = 4, TX_TIMEOUT = 5, TX_CAPTURE = 6;
    /** Von Shizuku reserviert: Dienst beenden. */
    static final int TX_DESTROY = 16777115;

    private volatile long lastKey = Long.MIN_VALUE / 4;
    private volatile long lastStick = Long.MIN_VALUE / 4;
    private volatile String status = "startet";
    private final Map<String, Gamepads.AxisFilter> watched = new HashMap<>();

    public ProbeService() {
        Thread t = new Thread(this::scanLoop, "guardian-scan");
        t.setDaemon(true);
        t.start();
    }

    @SuppressWarnings("unused")
    public ProbeService(Context context) { this(); }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == TX_DESTROY) {
            System.exit(0);
            return true;
        }
        if (code < TX_STATUS || code > TX_CAPTURE) return false;
        data.enforceInterface(DESCRIPTOR);
        switch (code) {
            case TX_STATUS:
                reply.writeString(status);
                return true;
            case TX_INPUT: {
                long stick = lastStick;
                if (anyHeld()) stick = SystemClock.uptimeMillis();
                reply.writeLong(lastKey);
                reply.writeLong(stick);
                synchronized (watched) { reply.writeInt(watched.size()); }
                return true;
            }
            case TX_DISK:
                reply.writeLong(diskBytes());
                return true;
            case TX_SLEEP:
                reply.writeInt(run(15_000, "input", "keyevent", "223") != null ? 1 : 0);
                return true;
            case TX_TIMEOUT: {
                int ms = Math.max(15_000, Math.min(24 * 3600_000, data.readInt()));
                reply.writeInt(run(5_000, "settings", "put", "system", "screen_off_timeout", String.valueOf(ms)) != null ? 1 : 0);
                return true;
            }
            case TX_CAPTURE: {
                String phys = data.readString();
                int step = Math.max(2, data.readInt());
                capture(phys, step, reply);
                return true;
            }
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- Controller

    private void scanLoop() {
        while (true) {
            try {
                String out = run(8_000, "getevent", "-lp");
                if (out == null) {
                    status = "getevent nicht verfügbar";
                } else {
                    List<Gamepads.Device> devices = Gamepads.parse(out);
                    int pads = 0;
                    for (Gamepads.Device d : devices) {
                        if (!d.gamepad()) continue;
                        pads++;
                        synchronized (watched) {
                            if (watched.containsKey(d.path)) continue;
                            Gamepads.AxisFilter f = new Gamepads.AxisFilter(d.axes);
                            watched.put(d.path, f);
                            Thread r = new Thread(() -> read(d.path, f), "guardian-pad");
                            r.setDaemon(true);
                            r.start();
                        }
                    }
                    status = "bereit · " + pads + (pads == 1 ? " Controller" : " Controller") + " erkannt";
                }
            } catch (Throwable t) {
                status = "Fehler: " + t.getClass().getSimpleName();
            }
            SystemClock.sleep(30_000);   // neue Bluetooth-/USB-Controller
        }
    }

    private void read(String path, Gamepads.AxisFilter filter) {
        int size = android.os.Process.is64Bit() ? 24 : 16;
        byte[] buf = new byte[size * 64];
        try (FileInputStream in = new FileInputStream(path)) {
            while (true) {
                int n = in.read(buf);
                if (n < 0) break;
                ByteBuffer b = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN);
                for (int off = 0; off + size <= n; off += size) {
                    int type = b.getShort(off + size - 8) & 0xFFFF;
                    int code = b.getShort(off + size - 6) & 0xFFFF;
                    int value = b.getInt(off + size - 4);
                    if (type == Gamepads.EV_KEY && value == 1) {
                        lastKey = SystemClock.uptimeMillis();
                    } else if (type == Gamepads.EV_ABS) {
                        boolean counts;
                        synchronized (filter) { counts = filter.onAbs(code, value); }
                        if (counts) lastStick = SystemClock.uptimeMillis();
                    }
                }
            }
        } catch (IOException e) {
            // Controller getrennt oder kein Zugriff
        } finally {
            synchronized (watched) { watched.remove(path); }
        }
    }

    private boolean anyHeld() {
        synchronized (watched) {
            for (Gamepads.AxisFilter f : watched.values()) {
                synchronized (f) { if (f.held()) return true; }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- Speicher

    private static long diskBytes() {
        String s = readFile("/proc/diskstats");
        if (s == null) return -1;
        long sectors = 0;
        for (String line : s.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 10) continue;
            String name = f[2];
            if (!name.matches("sd[a-z]+|mmcblk\\d+")) continue;
            try {
                sectors += Long.parseLong(f[5]) + Long.parseLong(f[9]);
            } catch (NumberFormatException ignored) {
            }
        }
        return sectors * 512L;
    }

    // ---------------------------------------------------------------- Ersatz-Bildanalyse

    private static void capture(String phys, int step, Parcel reply) {
        byte[] raw = null;
        try {
            ProcessBuilder pb = phys == null || phys.isEmpty()
                    ? new ProcessBuilder("screencap") : new ProcessBuilder("screencap", "-d", phys);
            Process p = pb.redirectErrorStream(false).start();
            raw = readAll(p.getInputStream(), 40_000_000);
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Exception e) {
            raw = null;
        }
        if (raw == null || raw.length < 16) {
            reply.writeInt(0);
            reply.writeInt(0);
            reply.writeByteArray(new byte[0]);
            return;
        }
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int w = b.getInt(0), h = b.getInt(4), f = b.getInt(8);
        int bpp = f == 4 ? 2 : (f == 3 ? 3 : 4);
        long pixels = (long) w * h * bpp;
        int header = (int) (raw.length - pixels);
        if (w <= 0 || h <= 0 || (header != 12 && header != 16) || bpp != 4) {
            reply.writeInt(0);
            reply.writeInt(0);
            reply.writeByteArray(new byte[0]);
            return;
        }
        int sw = w / step, sh = h / step;
        byte[] luma = new byte[sw * sh];
        boolean bgra = f == 5;
        for (int y = 0; y < sh; y++) {
            int py = y * step + step / 2;
            for (int x = 0; x < sw; x++) {
                int px = x * step + step / 2;
                int o = header + (py * w + px) * 4;
                int r = raw[o] & 0xFF, g = raw[o + 1] & 0xFF, bl = raw[o + 2] & 0xFF;
                if (bgra) { int t = r; r = bl; bl = t; }
                luma[y * sw + x] = (byte) ((r * 54 + g * 183 + bl * 19) >> 8);
            }
        }
        reply.writeInt(sw);
        reply.writeInt(sh);
        reply.writeByteArray(luma);
    }

    // ---------------------------------------------------------------- Hilfen

    private static String run(int timeoutMs, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final byte[][] out = new byte[1][];
            Thread reader = new Thread(() -> {
                try { out[0] = readAll(p.getInputStream(), 2_000_000); } catch (IOException ignored) { }
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            reader.join(2_000);
            return p.exitValue() == 0 && out[0] != null ? new String(out[0]) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readFile(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            return new String(readAll(in, 1_000_000));
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (out.size() + n > max) break;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
