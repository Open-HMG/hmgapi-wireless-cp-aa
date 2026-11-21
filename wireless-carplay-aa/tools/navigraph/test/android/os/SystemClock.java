package android.os;

/** host stand-in */
public final class SystemClock {
    public static long elapsedRealtime() { return System.nanoTime() / 1000000; }
}
