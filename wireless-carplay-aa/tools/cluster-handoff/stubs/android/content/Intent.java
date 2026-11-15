package android.content;
import java.util.HashMap;
public class Intent {
    public final String action;
    public String target;
    final HashMap<String,Boolean> extras = new HashMap<String,Boolean>();
    public Intent(String action) { this.action=action; }
    public Intent setPackage(String target) { this.target=target;return this; }
    public Intent putExtra(String key, boolean value) { extras.put(key,value);return this; }
    public String getAction() { return action; }
    public boolean hasExtra(String key) { return extras.containsKey(key); }
    public boolean getBooleanExtra(String key, boolean fallback) { return hasExtra(key)?extras.get(key):fallback; }
}
