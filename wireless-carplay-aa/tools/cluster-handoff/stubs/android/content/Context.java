package android.content;
import java.util.ArrayList;
import java.util.List;
import android.os.Handler;
public class Context {
    public static final List<Registration> receivers = new ArrayList<Registration>();
    public static final List<Intent> sent = new ArrayList<Intent>();
    public static final List<Runnable> queue = new ArrayList<Runnable>();
    public static final String POWER="android.permission.DEVICE_POWER";
    public final String name;
    public final boolean privileged;
    public Context(String name) { this(name,true); }
    public Context(String name, boolean privileged) { this.name=name;this.privileged=privileged; }
    static class Registration {
        Context context;BroadcastReceiver receiver;String action,permission;Handler handler;
        Registration(Context c,BroadcastReceiver r,IntentFilter f,String p,Handler h) {
            context=c;receiver=r;action=f.action;permission=p;handler=h;
        }
    }
    public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String permission,Handler handler) {
        if (!POWER.equals(permission)) throw new AssertionError("receiver missing privileged sender permission");
        receivers.add(new Registration(this,r,f,permission,handler));return null;
    }
    public void sendBroadcast(final Intent intent,String permission) {
        if (intent.target==null || !POWER.equals(permission)) throw new AssertionError("broadcast scope/permission");
        sent.add(intent);
        for (final Registration r : new ArrayList<Registration>(receivers)) {
            if (!r.context.name.equals(intent.target) || !r.action.equals(intent.action)) continue;
            if (!privileged || !r.context.privileged) continue;
            queue.add(new Runnable() { public void run() {
                Runnable delivery=new Runnable() { public void run() { r.receiver.onReceive(r.context,intent); } };
                if(r.handler==null) delivery.run();else r.handler.post(delivery);
            }});
        }
    }
    public static void drain() {
        while(!queue.isEmpty()) queue.remove(0).run();
    }
    public static void reset() { receivers.clear();sent.clear();queue.clear(); }
}
