package android.os;

public final class SystemClock {
    private SystemClock() {}

    private static volatile long baseTime = System.currentTimeMillis();

    public static long uptimeMillis() {
        return System.currentTimeMillis() - baseTime;
    }

    public static long elapsedRealtime() {
        return System.currentTimeMillis() - baseTime;
    }

    public static long elapsedRealtimeNanos() {
        return (System.currentTimeMillis() - baseTime) * 1_000_000L;
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }
}
