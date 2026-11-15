package hmgapi.mods.wireless;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import hmgapi.hook.ModContext;

/** Actual CarPlay route activity, shared with navigation only for its cluster TBT
 * call. All HUD, lane and trip suppression continues to use the stock policy.
 * Both measured stock apps request DEVICE_POWER; receivers require that privileged
 * sender permission, and every broadcast is restricted to the other package.
 */
public final class ClusterHandoff {
    static final String STATE = "hmgapi.wireless.CLUSTER_ROUTE_STATE";
    static final String QUERY = "hmgapi.wireless.CLUSTER_ROUTE_QUERY";
    static final String PERMISSION = "android.permission.DEVICE_POWER";
    static final String ACTIVE = "active";

    private ClusterHandoff() {}

    public static final class Publisher {
        final Context context;
        boolean active;

        Publisher(Context context) { this.context = context; }

        public void active(boolean value) {
            active = value;
            send();
        }

        void send() {
            context.sendBroadcast(new Intent(STATE).setPackage(Wireless.NAVIGATION).putExtra(ACTIVE, active), PERMISSION);
        }
    }

    /** Register before announcing idle so either app's startup order is supported.
     * The same handler serializes queries and subsequent route transitions. */
    public static Publisher publish(Context context, Handler handler) {
        final Publisher publisher = new Publisher(context);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (QUERY.equals(intent.getAction())) publisher.send();
            }
        }, new IntentFilter(QUERY), PERMISSION, handler);
        publisher.send();
        return publisher;
    }

    static final class NavigationState {
        // Unknown retains the old suppression until a protected reply arrives.
        volatile boolean known, active;
        private Object[] compass;

        synchronized void remember(Object[] args) {
            compass = null;
            if (args == null || args.length != 9 || !Integer.valueOf(8).equals(args[0])) return;
            for (int k = 0; k < args.length; k++) {
                if (k == 2 || k == 3) {
                    if (!(args[k] instanceof Float)) return;
                    float f = (Float) args[k];
                    if (Float.isNaN(f) || Float.isInfinite(f)) return;
                } else if (!(args[k] instanceof Integer)) return;
            }
            float direction = (Float) args[2];
            if (direction < 0f || direction > 360f) return;
            compass = args.clone();
        }

        synchronized Object[] receive(boolean value, boolean androidAutoGuiding) {
            boolean restore = !value && (!known || active) && !androidAutoGuiding;
            active = value;
            known = true;
            return restore && compass != null ? compass.clone() : null;
        }

        boolean phoneOwnsCluster(boolean androidAutoGuiding, boolean carPlayMode) {
            return androidAutoGuiding || carPlayMode && (!known || active);
        }
    }

    static NavigationState listen(final Context context, final ModContext mod) {
        final NavigationState state = new NavigationState();
        final Cluster cluster = Cluster.of(context);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (!STATE.equals(intent.getAction()) || !intent.hasExtra(ACTIVE)) return;
                Object[] compass = state.receive(intent.getBooleanExtra(ACTIVE, true), Car.androidAutoGuiding(context));
                mod.log("PalisadeHUD t=" + android.os.SystemClock.elapsedRealtime()
                        + " cluster-route active=" + state.active + " compassReplay=" + (compass != null));
                if (compass != null && cluster != null) {
                    // Replay the last real stock compass, preserving its heading
                    // and all flags. Never manufacture north or a HUD frame.
                    cluster.tbtInfo((Integer) compass[0], (Integer) compass[1], (Float) compass[2], (Float) compass[3],
                            (Integer) compass[4], (Integer) compass[5], (Integer) compass[6], (Integer) compass[7], (Integer) compass[8]);
                }
            }
        }, new IntentFilter(STATE), PERMISSION, null);
        context.sendBroadcast(new Intent(QUERY).setPackage(Wireless.DIPO), PERMISSION);
        return state;
    }
}
