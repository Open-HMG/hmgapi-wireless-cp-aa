package android.content;

/** host stand-in: an action, a package and int/long/string extras */
public class Intent {
    public final String action;
    public String pkg;
    public final java.util.Map<String, Object> extras = new java.util.LinkedHashMap<String, Object>();

    public Intent(String action) { this.action = action; }

    public Intent setPackage(String p) { pkg = p; return this; }

    public Intent putExtra(String k, int v) { extras.put(k, v); return this; }

    public Intent putExtra(String k, long v) { extras.put(k, v); return this; }

    public Intent putExtra(String k, String v) { extras.put(k, v); return this; }

    public int getIntExtra(String k, int d) { Object v = extras.get(k); return v instanceof Integer ? (Integer) v : d; }

    public long getLongExtra(String k, long d) { Object v = extras.get(k); return v instanceof Long ? (Long) v : d; }

    public String getStringExtra(String k) { Object v = extras.get(k); return v instanceof String ? (String) v : null; }

    @Override
    public String toString() { return action + " to " + pkg + " " + extras; }
}
