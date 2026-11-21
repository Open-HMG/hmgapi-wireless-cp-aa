package android.content;

/** host stand-in: records broadcasts and the receivers registered */
public class Context {
    public final java.util.List<Intent> sent = new java.util.ArrayList<Intent>();
    public final java.util.List<BroadcastReceiver> receivers = new java.util.ArrayList<BroadcastReceiver>();
    public String packageName = "com.google.android.projection.sink";

    public String getPackageName() { return packageName; }

    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) { receivers.add(r); return null; }

    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, String permission, android.os.Handler h) { receivers.add(r); return null; }

    public void sendBroadcast(Intent i) { sent.add(i); }
}
