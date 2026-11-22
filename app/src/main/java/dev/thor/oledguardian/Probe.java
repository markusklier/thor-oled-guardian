package dev.thor.oledguardian;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;

import rikka.shizuku.Shizuku;

/**
 * Verbindung zum Präzisionsmodus ({@link ProbeService}) über Shizuku. Alles ist optional: Ohne Shizuku arbeitet der
 * Guardian mit der Bedienungshilfe allein (Tasten, Berührungen, Bildanalyse), nur Sticks und lokale Kopien fehlen.
 * Aufrufe blockieren kurz – nie auf dem Hauptthread verwenden.
 */
final class Probe {
    private Probe() {}

    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final int REQUEST = 7311;

    private static volatile IBinder binder;
    private static volatile boolean binding;
    private static boolean listening;
    private static Shizuku.UserServiceArgs args;
    static volatile String lastError = "";

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service != null && service.pingBinder() ? service : null;
            binding = false;
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            binder = null;
            binding = false;
        }
    };

    static synchronized void init(Context c) {
        if (listening) return;
        listening = true;
        try {
            Shizuku.addBinderDeadListener(() -> { binder = null; binding = false; });
        } catch (Throwable ignored) {
            // Shizuku-Bibliothek hier nicht nutzbar
        }
        long version = 1;
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        args = new Shizuku.UserServiceArgs(new ComponentName(c.getPackageName(), ProbeService.class.getName()))
                .daemon(false).processNameSuffix("probe").debuggable(false).version((int) version);
    }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean running() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    static boolean granted() {
        try {
            return running() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void request() {
        try {
            if (running() && !granted()) Shizuku.requestPermission(REQUEST);
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName();
        }
    }

    static boolean connected() {
        IBinder b = binder;
        return b != null && b.pingBinder();
    }

    /** Verbindet bei Bedarf; kehrt sofort zurück. */
    static void ensure() {
        if (connected() || binding || args == null || !granted()) return;
        binding = true;
        try {
            Shizuku.bindUserService(args, CONNECTION);
        } catch (Throwable t) {
            binding = false;
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    static String status() {
        Parcel r = call(ProbeService.TX_STATUS, null);
        if (r == null) return null;
        try { return r.readString(); } finally { r.recycle(); }
    }

    /** {letzte Taste, letzte Stickbewegung, Zahl der Controller} oder null. */
    static long[] input() {
        Parcel r = call(ProbeService.TX_INPUT, null);
        if (r == null) return null;
        try { return new long[]{r.readLong(), r.readLong(), r.readInt()}; } finally { r.recycle(); }
    }

    static long diskBytes() {
        Parcel r = call(ProbeService.TX_DISK, null);
        if (r == null) return -1;
        try { return r.readLong(); } finally { r.recycle(); }
    }

    static boolean sleep() {
        Parcel r = call(ProbeService.TX_SLEEP, null);
        if (r == null) return false;
        try { return r.readInt() == 1; } finally { r.recycle(); }
    }

    static boolean setScreenTimeout(int ms) {
        Parcel r = call(ProbeService.TX_TIMEOUT, p -> p.writeInt(ms));
        if (r == null) return false;
        try { return r.readInt() == 1; } finally { r.recycle(); }
    }

    /** Helligkeitswerte (je step×step Pixel) über screencap: {w, h} in size, Werte als Rückgabe. */
    static byte[] capture(String physicalId, int step, int[] size) {
        Parcel r = call(ProbeService.TX_CAPTURE, p -> { p.writeString(physicalId); p.writeInt(step); });
        if (r == null) return null;
        try {
            size[0] = r.readInt();
            size[1] = r.readInt();
            byte[] luma = r.createByteArray();
            return size[0] > 0 && luma != null && luma.length >= size[0] * size[1] ? luma : null;
        } finally {
            r.recycle();
        }
    }

    private interface Writer { void write(Parcel p); }

    private static Parcel call(int code, Writer w) {
        IBinder b = binder;
        if (b == null) { ensure(); return null; }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProbeService.DESCRIPTOR);
            if (w != null) w.write(data);
            if (!b.transact(code, data, reply, 0)) {
                reply.recycle();
                return null;
            }
            return reply;
        } catch (Exception e) {
            binder = null;
            lastError = e.getClass().getSimpleName();
            reply.recycle();
            return null;
        } finally {
            data.recycle();
        }
    }
}
