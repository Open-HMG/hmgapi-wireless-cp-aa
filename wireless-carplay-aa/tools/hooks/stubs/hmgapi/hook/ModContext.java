package hmgapi.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** records what the mod asks of HMG-API; an error fails the test */
public final class ModContext {
    public final Map<String, Boolean> settings = new LinkedHashMap<String, Boolean>();
    public final Map<String, MethodHook> hooks = new LinkedHashMap<String, MethodHook>();
    public final List<String> logs = new ArrayList<String>();
    public int dipoStarts;
    public boolean settingBoolean(String key, boolean fallback) {
        return settings.containsKey(key) ? settings.get(key) : fallback;
    }
    public java.lang.reflect.Member hook(ClassLoader cl, String point, MethodHook hook) {
        if (hooks.put(point, hook) != null) throw new AssertionError("hooked twice: " + point);
        return null;
    }
    public void publish(String service, Object value) {}
    public Class<?> appPart(LoadedApp app, String name) {
        if (!"hmgapi.mods.wireless.dipo.Dipo".equals(name)) throw new AssertionError(name);
        return DipoPart.class;
    }
    public void log(String message) { logs.add(message); }
    public void error(String message, Throwable error) { throw new AssertionError(message, error); }
    public static final class DipoPart {
        public static void start(ModContext mod, LoadedApp app) { mod.dipoStarts++; }
    }
}
