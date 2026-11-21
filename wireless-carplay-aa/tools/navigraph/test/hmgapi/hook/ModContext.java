package hmgapi.hook;

/** host stand-in: what NaviGraph uses of HMG-API's ModContext */
public final class ModContext {
    public final java.util.List<String> logs = new java.util.ArrayList<String>();

    public static String md5(String path) { return null; }

    public boolean enabled() { return false; }

    public void log(String m) { logs.add(m); }

    public void error(String m, Throwable t) { logs.add("error " + m); }
}
