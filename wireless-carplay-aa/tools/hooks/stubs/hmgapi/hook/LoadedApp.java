package hmgapi.hook;

public final class LoadedApp {
    public final String packageName, processName;
    public final ClassLoader classLoader = LoadedApp.class.getClassLoader();
    public final android.content.Context context = new android.content.Context();
    public LoadedApp(String name) { packageName = name; processName = name; }
}
