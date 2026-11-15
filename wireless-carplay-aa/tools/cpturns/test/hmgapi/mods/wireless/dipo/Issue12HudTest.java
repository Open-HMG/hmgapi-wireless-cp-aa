package hmgapi.mods.wireless.dipo;

import java.nio.file.*;
import java.util.Arrays;
import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;

/** Phone-selected current/following indices, including actual repeated SDK fields. */
public final class Issue12HudTest {
    static CpTurns t;
    static void fresh(){t=PalisadeHudTest.fresh();}
    static void pair(int a,int b){t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1),13,U16,i(a),13,U16,i(b)));}
    static void detail(int n,int type,int angle){t.maneuver(maneuver(n,type,0,0,angle,"",0));}
    static boolean hud(String prefix){return PalisadeHudTest.hasHud(prefix);}
    static void nativeReplay(Path p)throws Exception {
        fresh();
        t.routeGuidance(NativeLanePipelineTest.read(p.resolve("pair-10-20.parcel")));
        check("native repeated route indices preserve current and following",Arrays.equals(t.updates.get(5).list,i(10,20)));
        t.maneuver(NativeLanePipelineTest.read(p.resolve("right-10.parcel")));
        t.maneuver(NativeLanePipelineTest.read(p.resolve("left-20.parcel")));
        check("native right then left reaches two-icon HUD frame",hud("hud f2024346"));
        t.maneuver(NativeLanePipelineTest.read(p.resolve("straight-30.parcel")));c.calls.clear();
        t.routeGuidance(NativeLanePipelineTest.read(p.resolve("promote-20-30.parcel")));
        check("native promotion advances left then straight",hud("hud f2024641"));c.calls.clear();
        t.routeGuidance(NativeLanePipelineTest.read(p.resolve("single-30.parcel")));
        check("native single instruction removes previous following icon",hud("hud f20141")&&!hud("hud f202"));c.calls.clear();
        t.routeGuidance(NativeLanePipelineTest.read(p.resolve("reroute-40-50.parcel")));
        check("native reroute clears old instructions while waiting for details",hud("hud f2")&&!hud("hud f201"));c.calls.clear();
        t.maneuver(NativeLanePipelineTest.read(p.resolve("left-50.parcel")));
        check("native following detail alone cannot revive old primary",!hud("hud f201")&&!hud("hud f202"));c.calls.clear();
        t.maneuver(NativeLanePipelineTest.read(p.resolve("right-40.parcel")));
        check("native late primary renders new pair",hud("hud f2024346"));c.calls.clear();
        t.routeGuidance(NativeLanePipelineTest.read(p.resolve("cancel.parcel")));
        check("native cancellation clears guidance",hud("hud f2"));
    }
    public static void main(String[]args)throws Exception {
        fresh();pair(10,20);
        check("repeated scalar display-list fields retain phone order",Arrays.equals(t.updates.get(1).list,i(10,20)));
        check("raw route diagnostics retain repeated item order before merging",Dipo.trace.toString().contains("13:5:1[10] 13:5:1[20]"));
        CpTurns.traceRouteIndices(msg(0,STR,s("private-component"),13,STR,s("private-maneuver"),19,STR,s("private-source"),3,STR,s("private-road")));
        String raw=Dipo.trace.get(Dipo.trace.size()-1);
        check("raw diagnostics expose field metadata without string payloads",raw.equals("route-input items=4 0:10:1 13:10:1")&&!raw.contains("private"));
        CpTurns.traceRouteIndices(msg(0,U16,i(5),1,ENUM,i(0),2,ENUM,i(3),15,9,i(0),18,9,i(0)));
        check("incoming stop and visibility flags are retained before reset",Dipo.trace.get(Dipo.trace.size()-1).equals("route-input items=5 0:5:1[5] 1:8:1[0] 2:8:1[3] 15:9:1[0] 18:9:1[0]"));
        CpTurns.traceRouteIndices(msg(10,U32,i(100),11,STR,s("PRIVATE"),12,ENUM,i(4)));
        check("distance diagnostics retain raw metres and units without text payload",Dipo.trace.get(Dipo.trace.size()-1).equals("route-input items=3 10:6:1[100] 11:10:1 12:8:1[4]"));
        com.tcc.iap2.Iap2NotiMsg large=msg();
        for(int k=0;k<20;k++)large.getItemGroup().add(13,U16,i(0,1,2,3,4,5,6,7,8,9));
        CpTurns.traceRouteIndices(large);raw=Dipo.trace.get(Dipo.trace.size()-1);
        check("raw diagnostics bound output and mark truncated items and arrays",raw.length()<2048&&raw.contains(",...")&&raw.endsWith(" more-fields"));
        detail(10,2,90);detail(20,1,-90);
        check("both selected maneuvers render without guessing cached order",hud("hud f2024346"));c.calls.clear();
        t.routeGuidance(msg(0,U16,i(1),11,STR,s("0.2")));
        check("partial route update preserves both display indices",Arrays.equals(t.updates.get(1).list,i(10,20)));
        t.routeGuidance(msg(0,U16,i(1),13,U16,i(20,30)));
        check("new array-form list replaces rather than appends old indices",Arrays.equals(t.updates.get(1).list,i(20,30)));
        check("existing array-form components remain supported",Arrays.equals(CpTurns.componentIds(msg(0,U16,i(5,7))),i(5,7)));
        check("repeated scalar components retain all recipients",Arrays.equals(CpTurns.componentIds(msg(0,U16,i(5),0,U16,i(7))),i(5,7)));
        fresh();t.routeGuidance(msg(0,U16,i(5),0,U16,i(7),1,ENUM,i(1),13,U16,i(10),13,U16,i(20)));
        t.maneuver(msg(0,U16,i(5),0,U16,i(7),1,U16,i(10),3,ENUM,i(2),11,U8,i(90)));
        check("repeated maneuver components update both caches",t.maneuvers.get(5)!=null&&t.maneuvers.get(7)!=null&&t.maneuvers.get(5).containsKey(10)&&t.maneuvers.get(7).containsKey(10));
        c.calls.clear();t.routeGuidance(msg(1,ENUM,i(0)));
        check("broadcast cancellation resets all components and cached maneuvers",t.updates.get(5).state==0&&t.updates.get(7).state==0&&t.maneuvers.get(5).isEmpty()&&t.maneuvers.get(7).isEmpty()&&t.laneFrames.isEmpty()&&t.hudCurrent==-1&&hud("hud f2"));
        fresh();pair(10,20);detail(10,2,90);detail(20,1,-90);c.calls.clear();
        t.routeGuidance(msg(0,U16,i(1),13,U16,i()));
        check("empty active list clears stale two-turn HUD",hud("hud f2")&&!hud("hud f201")&&!hud("hud f202"));
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),11,STR,s("0.1")));
        check("already empty list does not spam HUD clears",!hud("hud f2"));
        fresh();pair(10,20);detail(10,2,90);detail(20,1,-90);c.calls.clear();pair(40,50);
        check("unknown rerouted primary clears the old pair",hud("hud f2")&&!hud("hud f202"));
        detail(50,1,-90);c.calls.clear();detail(40,2,90);
        check("late reroute details render the selected new pair",hud("hud f2024346"));c.calls.clear();
        detail(40,0,0);check("unsupported primary clears stale arrows",hud("hud f2")&&!hud("hud f201")&&!hud("hud f202"));
        fresh();pair(10,20);detail(10,2,90);detail(20,1,-90);detail(30,3,0);
        t.maneuver(msg(0,U16,i(1),1,U16,i(10),5,U32,i(100)));c.calls.clear();
        t.routeGuidance(msg(0,U16,i(1),13,U16,i(10)));
        check("partial primary angle cannot retain a removed following turn",hud("hud f20143")&&!hud("hud f202"));c.calls.clear();
        pair(10,30);
        check("partial primary angle still permits new selected following turn",hud("hud f2024341"));
        fresh();pair(10,20);detail(10,2,90);detail(20,1,-90);c.calls.clear();detail(10,2,91);
        check("explicit unsupported primary angle is not treated as omitted",hud("hud f2")&&!hud("hud f202"));
        fresh();pair(10,20);detail(10,2,90);detail(20,1,-90);c.calls.clear();
        t.maneuver(msg(0,U16,i(1),1,U16,i(10),3,ENUM,i(1)));
        check("changed maneuver type uses upstream left fallback rather than the previous right icon",hud("hud f20246")&&!hud("hud f20243"));
        fresh();pair(10,20);detail(10,2,90);detail(20,1,-90);c.calls.clear();
        t.maneuver(msg(0,U16,i(1),1,U16,i(10),9,ENUM,i(1)));
        check("changed junction cannot reuse the previous primary icon",hud("hud f2")&&!hud("hud f202"));
        c.calls.clear();t.maneuver(maneuver(10,2,0,1,0,"",0));
        check("explicit zero-degree roundabout exit remains a valid primary",hud("hud f2026046"));
        fresh();pair(10,20);detail(10,2,90);detail(20,1,-90);t.stop();c.calls.clear();
        detail(20,1,-90);
        check("native navigation takeover cannot be undone by late maneuver details",!hud("hud f201")&&!hud("hud f202"));
        t.routeGuidance(msg(0,U16,i(1),11,STR,s("0.1")));
        check("partial route update cannot revive stopped guidance",!hud("hud f201")&&!hud("hud f202"));c.calls.clear();
        pair(10,20);
        check("fresh route cannot reuse the stopped primary cache",!hud("hud f201")&&!hud("hud f202"));
        detail(10,2,90);
        check("fresh route resumes after its primary details arrive",hud("hud f20143"));c.calls.clear();detail(20,1,-90);
        check("fresh following details restore the two-turn pair",hud("hud f2024346"));
        fresh();pair(10,20);detail(10,2,90);detail(20,1,-90);
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(0)));c.calls.clear();pair(10,20);
        check("cancelled route indices cannot revive a previous route",!hud("hud f201")&&!hud("hud f202"));
        fresh();pair(10,20);detail(10,12,0);t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(2)));c.calls.clear();
        detail(10,2,90);
        check("late maneuver details do not revive an arrived route",!hud("hud f201")&&!hud("hud f202"));
        fresh();pair(10,20);detail(10,2,90);detail(20,1,-90);
        t.routeGuidance(msg(0,U16,i(1),16,U16,i(0),18,9,i(1)));t.lanes(LaneVisibilityTest.lanes(1));
        pair(40,50);
        check("turn-only reroute clear retains pending visible lane frame",c.lastLanes[1]==1&&t.laneFrames.containsKey(1));
        fresh();pair(10,20);detail(10,2,90);detail(20,1,-90);
        t.routeGuidance(msg(0,U16,i(1),19,STR,s("Maps")));
        t.routeGuidance(msg(0,U16,i(1),19,STR,s("Waze")));c.calls.clear();pair(10,20);
        check("source change discards previous source's maneuver cache",!hud("hud f201")&&!hud("hud f202"));
        // Numeric index/type/angle pattern from the 2.2.0 vehicle return.
        fresh();t.routeGuidance(msg(0,U16,i(5),1,ENUM,i(1),13,U16,i(1)));
        t.maneuver(msg(0,U16,i(5),1,U16,i(1),3,ENUM,i(2),11,U8,i(90)));
        t.maneuver(msg(0,U16,i(5),1,U16,i(2),3,ENUM,i(2),11,U8,i(0)));
        t.maneuver(msg(0,U16,i(5),1,U16,i(3),3,ENUM,i(2),11,U8,i(90)));
        check("vehicle singleton selection does not invent a following turn from prefetched details",t.maneuvers.get(5).size()==3&&Arrays.equals(t.updates.get(5).list,i(1))&&t.hudFollowing==-1&&t.hudNext==0&&hud("hud f20143")&&!hud("hud f202"));
        for(String p:args)nativeReplay(Paths.get(p));
        System.exit(fail);
    }
}
