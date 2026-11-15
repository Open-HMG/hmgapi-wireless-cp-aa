package hmgapi.mods.wireless.dipo;

import com.tcc.iap2.Iap2NotiMsg;
import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;

/** Lane-event indices are independent of maneuver indices and visibility. */
public final class LanePrefetchTest {
    static void select(CpTurns t,int component,int index,boolean shown) {
        t.routeGuidance(msg(0,U16,i(component),1,ENUM,i(1),16,U16,i(index),18,9,i(shown?1:0)));
    }
    static Iap2NotiMsg event(int component,int index,int angle) {
        Iap2NotiMsg m=new Iap2NotiMsg();
        Iap2NotiMsg.Iap2ObjGroup lane=m.new Iap2ObjGroup();
        lane.add(1,ENUM,i(2)).add(2,U16,i(angle)).add(3,U16,i(angle));
        m.getItemGroup().add(0,U16,i(component)).add(1,U16,i(index))
            .add(2,GROUPS,new Iap2NotiMsg.Iap2ObjGroup[]{lane});return m;
    }
    static Iap2NotiMsg empty(int component,int index) {
        Iap2NotiMsg m=new Iap2NotiMsg();
        m.getItemGroup().add(0,U16,i(component)).add(1,U16,i(index))
            .add(2,GROUPS,new Iap2NotiMsg.Iap2ObjGroup[0]);return m;
    }
    static boolean arrow(int icon) {return c.lastLanes[1]==1&&c.lastLanes[19]==icon;}
    public static void main(String[] args) {
        CpTurns t=LaneVisibilityTest.fresh();select(t,5,2,false);c.calls.clear();
        t.lanes(event(5,2,-90));t.lanes(event(5,3,90));
        check("prefetch while hidden emits no arrows",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        select(t,5,2,true);check("visibility selects the exact prefetched event",arrow(7));
        t.lanes(event(5,4,0));check("later prefetch cannot replace current event",arrow(7));
        select(t,5,3,true);check("lane index advance selects cached next event",arrow(3));
        select(t,5,9,true);check("missing selected event clears rather than borrowing",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        t.lanes(event(5,2,-90));check("late older event cannot fill a different index",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        select(t,5,3,true);t.lanes(empty(5,4));check("empty future event does not clear active event",arrow(3));
        t.lanes(empty(5,3));check("empty active event clears arrows",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        select(t,5,4,true);check("empty cached event cannot resurrect old arrows",!LaneVisibilityTest.nonblankFrame(c.lastLanes));

        t=LaneVisibilityTest.fresh();t.lanes(event(5,17,-90));
        t.routeGuidance(msg(0,U16,i(5),1,ENUM,i(1),18,9,i(1)));
        check("unknown selector cannot expose an arbitrary prefetched event",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        select(t,5,17,true);check("prefetch before route metadata survives",arrow(7));
        select(t,5,17,false);check("visibility false clears without discarding cache",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        select(t,5,17,true);check("same event can reappear when phone shows it again",arrow(7));
        t.routeGuidance(msg(0,U16,i(5),16,U16,i(),18,9,i(1)));
        check("explicit empty selector clears instead of selecting zero",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        select(t,5,17,true);
        for(int k=100;k<140;k++)t.lanes(event(5,k,90));
        check("cache pressure preserves the active event",arrow(7));
        select(t,5,139,true);check("cache retains newest future event",arrow(3));
        select(t,5,100,true);check("evicted event has no fallback",!LaneVisibilityTest.nonblankFrame(c.lastLanes));

        t=LaneVisibilityTest.fresh();t.lanes(event(5,8,-90));t.lanes(event(7,8,90));
        select(t,5,8,true);check("same lane index remains isolated by component",arrow(7));
        select(t,7,8,true);check("other component selects its own event",arrow(3));
        t.disconnected();select(t,7,8,true);check("disconnect invalidates indexed cache",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        t.lanes(event(7,8,90));t.stop();select(t,7,8,true);check("stop invalidates indexed cache",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        t.lanes(event(7,8,90));t.routeGuidance(msg(0,U16,i(7),1,ENUM,i(0)));
        select(t,7,8,true);check("route reset invalidates indexed cache",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        System.exit(fail);
    }
}
