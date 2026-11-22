package dev.thor.oledguardian.engine;

/**
 * Datenrate aus einem fortlaufenden Byte-Zähler (Netzwerk über TrafficStats, Speicher über /proc/diskstats).
 * Glättet über die letzten Messungen, damit kurze Pausen eines Downloads nicht als „fertig“ gelten.
 */
public final class TransferMeter {
    private final long thresholdBytesPerSec;
    private long lastBytes = -1;
    private long lastAt;
    private double rate;

    public TransferMeter(long thresholdBytesPerSec) {
        this.thresholdBytesPerSec = thresholdBytesPerSec;
    }

    /** Neuer Zählerstand; negative Werte (unbekannt) setzen die Messung zurück. */
    public void feed(long totalBytes, long now) {
        if (totalBytes < 0) {
            lastBytes = -1;
            rate = 0;
            return;
        }
        if (lastBytes >= 0 && now > lastAt) {
            long delta = totalBytes - lastBytes;
            if (delta < 0) delta = 0; // Zähler zurückgesetzt (z. B. Schnittstelle neu)
            double current = delta * 1000.0 / (now - lastAt);
            rate = rate * 0.3 + current * 0.7;
        }
        lastBytes = totalBytes;
        lastAt = now;
    }

    public double bytesPerSec() { return rate; }
    public boolean active() { return rate >= thresholdBytesPerSec; }

    /** „12,3 MB/s“ */
    public static String format(double bytesPerSec) {
        if (bytesPerSec >= 1_000_000) return String.format(java.util.Locale.GERMANY, "%.1f MB/s", bytesPerSec / 1_000_000);
        if (bytesPerSec >= 1_000) return String.format(java.util.Locale.GERMANY, "%.0f kB/s", bytesPerSec / 1_000);
        return String.format(java.util.Locale.GERMANY, "%.0f B/s", bytesPerSec);
    }
}
