package android.os;

/** Controllable monotonic time; no sleeps in HUD refresh tests. */
public final class SystemClock {
    public static long now;
    public static long elapsedRealtime() { return now; }
}
