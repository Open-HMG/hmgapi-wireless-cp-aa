package android.content;

import java.util.HashMap;
import java.util.Map;

public class Intent {
    private final String action;
    private ComponentName component;
    private final Map<String, Object> extras = new HashMap<String, Object>();
    public Intent(String action) { this.action = action; }
    public String getAction() { return action; }
    public ComponentName getComponent() { return component; }
    public Intent setComponent(ComponentName c) { component = c; return this; }
    public Intent setClassName(String pkg, String cls) { component = new ComponentName(pkg, cls); return this; }
    public Intent putExtra(String k, Object v) { extras.put(k, v); return this; }
    public int getIntExtra(String k, int d) { Object v = extras.get(k); return v instanceof Integer ? (Integer) v : d; }
    public String getStringExtra(String k) { return (String) extras.get(k); }
    @SuppressWarnings("unchecked")
    public <T> T getParcelableExtra(String k) { return (T) extras.get(k); }
}
