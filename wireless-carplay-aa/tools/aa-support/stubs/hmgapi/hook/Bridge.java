package hmgapi.hook;

/** Records the public SDK hook API without loading Dalvik's native bridge. */
public final class Bridge {
    public static java.lang.reflect.Member member;
    public static MethodHook hook;
    public static void hookMethod(java.lang.reflect.Member target, MethodHook callback) {
        if (hook != null) throw new AssertionError("unexpected extra direct hook");
        member = target;
        hook = callback;
    }
    public static void reset() { member = null; hook = null; }
}
