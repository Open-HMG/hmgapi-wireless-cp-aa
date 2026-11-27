package android.content;

import android.content.pm.PackageManager;

/** what the hooks use: receivers recorded, a package manager that knows a set of activities */
public class Context {
    public BroadcastReceiver receiver;
    public IntentFilter filter;
    public String permission;
    public final PackageManager pm = new PackageManager();
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, String permission, Object handler) {
        receiver = r;
        filter = f;
        this.permission = permission;
        return null;
    }
    public PackageManager getPackageManager() { return pm; }
}
