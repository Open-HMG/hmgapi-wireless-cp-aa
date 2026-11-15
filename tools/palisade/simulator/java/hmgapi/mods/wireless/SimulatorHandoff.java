package hmgapi.mods.wireless;

import android.content.Context;
import android.os.Handler;
import hmgapi.hook.Cluster;
import hmgapi.hook.ModContext;

/** Adapts the production handoff with the existing modeled Android IPC delivery. */
public final class SimulatorHandoff {
    private final ClusterHandoff.NavigationState state;
    private final ClusterHandoff.Publisher publisher;
    public SimulatorHandoff() {
        Context.reset();
        state=ClusterHandoff.listen(new Context(Wireless.NAVIGATION),new ModContext());
        publisher=ClusterHandoff.publish(new Context(Wireless.DIPO),new Handler());
        Context.drain();
    }
    public void active(boolean active) {
        Cluster.instance.record("routeOwnership",active);
        publisher.active(active); Context.drain();
    }
    public void compass(float heading) {
        Object[] args=new Object[]{8,0,heading,0f,0,0,3,0,-1};
        state.remember(args);
        if (!state.phoneOwnsCluster(false,true)) Cluster.instance.tbtInfo(8,0,heading,0f,0,0,3,0,-1);
    }
}
