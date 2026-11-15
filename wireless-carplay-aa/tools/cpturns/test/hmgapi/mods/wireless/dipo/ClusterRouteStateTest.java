package hmgapi.mods.wireless.dipo;

import java.util.ArrayList;
import java.util.Arrays;
import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;

/** Reproduce idle -> calculation -> route -> cancellation, without claiming the
 * cluster's alive/operation status or changing the existing HUD clear output. */
public final class ClusterRouteStateTest {
    public static void main(String[] args) {
        final ArrayList<Boolean> events = new ArrayList<Boolean>();
        final ArrayList<String> outputAtTransition = new ArrayList<String>();
        c = new hmgapi.hook.Cluster();
        CpTurns t = new CpTurns(new android.content.Context(), c, null, new CpTurns.RouteStateSink() {
            @Override public void active(boolean value) {
                events.add(value);outputAtTransition.add(c.calls.toString());
            }
        });
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(0)));
        check("no route does not claim the cluster",events.isEmpty()&&!t.routeActive);
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(3)));
        check("actual Loading owns cluster before maneuver metadata arrives",events.equals(Arrays.asList(true)));
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1),13,U16,i(10)));
        t.maneuver(maneuver(10,2,0,0,90,"Primary",100));
        check("active route renders normal turn without duplicate ownership changes",events.size()==1&&c.calls.toString().contains("tbt 67 "));
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(5)));
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(4)));
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(6)));
        check("rerouting locating and proceed-to-route retain phone ownership",events.size()==1&&t.routeActive);
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(0)));
        check("cancellation releases actual route ownership",events.equals(Arrays.asList(true,false)));
        check("idle handoff adds no synthetic cluster heading or HUD output",c.calls.size()==4&&!c.calls.toString().contains("tbt "));
        t.maneuver(maneuver(10,2,0,0,90,"Late",100));
        t.routeGuidance(msg(0,U16,i(1),11,STR,s("100")));
        check("late detail and partial countdown cannot reacquire stopped route",events.size()==2&&!t.routeActive);
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1)));
        t.disconnected();
        check("session disconnect releases ownership",events.equals(Arrays.asList(true,false,true,false)));
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(3)));
        t.stop("stock-proxy");
        check("native takeover releases ownership",events.equals(Arrays.asList(true,false,true,false,true,false)));
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1),19,STR,s("Maps")));
        t.routeGuidance(msg(0,U16,i(1),19,STR,s("Other")));
        check("source reset releases ownership until fresh route state",!t.routeActive&&!events.get(events.size()-1));
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1)));
        t.routeGuidance(msg(0,U16,i(1),13,U16,i(20)));
        t.maneuver(maneuver(20,12,0,0,0,"Arrival",0));
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(2)));
        check("arrival releases cluster ownership after final turn output",!t.routeActive&&!events.get(events.size()-1)
                &&outputAtTransition.get(outputAtTransition.size()-1).contains("tbt 3 "));
        System.exit(fail);
    }
}
