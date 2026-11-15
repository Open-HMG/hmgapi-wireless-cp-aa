package hmgapi.mods.wireless;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import hmgapi.hook.ModContext;
import java.util.Arrays;

public final class ClusterHandoffTest {
    static int checks;
    static void check(String name, boolean ok) {
        if(!ok) throw new AssertionError(name);
        checks++;System.out.println("ok    "+name);
    }
    static Object[] compass(float heading) { return new Object[]{8,0,heading,0f,0,0,3,0,-1}; }
    static void reset() { Context.reset();Car.aa=false;Cluster.instance.calls.clear(); }

    public static void main(String[] args) {
        for(boolean navigationFirst:new boolean[]{true,false}) {
            reset();
            Context navi=new Context(Wireless.NAVIGATION),dipo=new Context(Wireless.DIPO);
            ClusterHandoff.NavigationState state;
            ClusterHandoff.Publisher publisher;
            if(navigationFirst) {
                state=ClusterHandoff.listen(navi,new ModContext());
                check("unknown owner retains suppression",state.phoneOwnsCluster(false,true));
                state.remember(compass(337.5f));
                publisher=ClusterHandoff.publish(dipo,new Handler());
            } else {
                publisher=ClusterHandoff.publish(dipo,new Handler());
                publisher.active(true);Context.drain();
                state=ClusterHandoff.listen(navi,new ModContext());
                state.remember(compass(337.5f));
            }
            Context.drain();
            check("startup order obtains current protected route state "+navigationFirst,state.known&&state.active==!navigationFirst);
            check("initial idle restores real compass only when no route",Cluster.instance.calls.size()==(navigationFirst?1:0));
            publisher.active(true);Context.drain();
            check("active phone route suppresses native compass",state.phoneOwnsCluster(false,true));
            int before=Cluster.instance.calls.size();
            Object[] real=compass(315f);state.remember(real);real[2]=0f;
            publisher.active(false);Context.drain();
            check("route cancellation releases cluster despite retained CarPlay mode",!state.phoneOwnsCluster(false,true));
            check("cancellation restores all original stock compass fields",Cluster.instance.calls.size()==before+1&&Arrays.equals(Cluster.instance.calls.get(before),compass(315f)));
            publisher.active(false);Context.drain();
            check("repeated idle does not replay or spam compass",Cluster.instance.calls.size()==before+1);
            check("Android Auto retains cluster priority",state.phoneOwnsCluster(true,true));
            publisher.active(true);Context.drain();
            Car.aa=true;before=Cluster.instance.calls.size();
            publisher.active(false);Context.drain();
            check("CarPlay release cannot overwrite Android Auto",Cluster.instance.calls.size()==before);
            Car.aa=false;
            publisher.active(true);Context.drain();
            check("native takeover is honored when DiPO mode ends",!state.phoneOwnsCluster(false,false));
            state.remember(new Object[]{67,0,90f,100f,0,0,0,0,0});
            publisher.active(false);Context.drain();
            check("new native turn invalidates old compass replay",Cluster.instance.calls.size()==before);
            publisher.active(true);Context.drain();
            new Context("untrusted",false).sendBroadcast(new Intent(ClusterHandoff.STATE)
                    .setPackage(Wireless.NAVIGATION).putExtra(ClusterHandoff.ACTIVE,false),ClusterHandoff.PERMISSION);
            Context.drain();check("unprivileged sender cannot release cluster",state.active);
            dipo.sendBroadcast(new Intent(ClusterHandoff.STATE).setPackage(Wireless.NAVIGATION),ClusterHandoff.PERMISSION);
            Context.drain();check("missing route-state field cannot release cluster",state.active);
        }
        ClusterHandoff.NavigationState state=new ClusterHandoff.NavigationState();
        state.remember(compass(Float.NaN));
        check("nonfinite heading is never replayed",state.receive(false,false)==null);
        state.receive(true,false);state.remember(compass(361f));
        check("out-of-range heading is never replayed",state.receive(false,false)==null);
        state.receive(true,false);state.remember(null);
        check("missing compass never manufactures heading",state.receive(false,false)==null);
        System.out.println("PASS "+checks+" cluster handoff checks (Android IPC modeled)");
    }
}
