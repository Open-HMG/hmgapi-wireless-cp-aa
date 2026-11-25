package hmgapi.hook;

/** Minimal public SDK hook API used while compiling host tests. */
public final class Bridge {
    public static void hookMethod(java.lang.reflect.Member target, MethodHook callback) {}
}
