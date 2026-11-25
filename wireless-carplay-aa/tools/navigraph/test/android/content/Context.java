package android.content;

/** host stand-in: records broadcasts, the receivers registered and the permission each wants of a sender */
public class Context {
    public final java.util.List<Intent> sent = new java.util.ArrayList<Intent>();
    public final java.util.List<BroadcastReceiver> receivers = new java.util.ArrayList<BroadcastReceiver>();
    public final java.util.List<String> permissions = new java.util.ArrayList<String>();
    public String packageName = "com.google.android.projection.sink";

    public String getPackageName() { return packageName; }

    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) { return registerReceiver(r, f, null, null); }

    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, String permission, android.os.Handler h) {
        receivers.add(r);
        permissions.add(permission);
        return null;
    }

    public void unregisterReceiver(BroadcastReceiver r) {
        int i = receivers.indexOf(r);
        if (i < 0) throw new IllegalArgumentException("Receiver not registered");
        receivers.remove(i);
        permissions.remove(i);
    }

    public void sendBroadcast(Intent i) { sent.add(i); }
}
