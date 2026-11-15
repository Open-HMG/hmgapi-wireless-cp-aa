package hmgapi.mods.wireless.dipo;

/** host stand-in: CpTurns only logs through it */
final class Dipo {
    static final java.util.List<String> trace = new java.util.ArrayList<String>();
    static void log(String m) {}
    static void hudTrace(String m) { trace.add(m); }
}
