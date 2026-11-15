package hmgapi.mods.wireless.dipo;

import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;

/** Separate explicit straight instructions from lane-only direction metadata. */
public final class StraightGuidanceTest {
    static boolean has(String prefix) {
        for(String call:c.calls)if(call.startsWith(prefix))return true;
        return false;
    }
    static void lanes(CpTurns t) { t.lanes(LanePrefetchTest.event(1,1,0)); }
    public static void main(String[] args) {
        hmgapi.hook.Car.unit=hmgapi.hook.Car.DISTANCE_MILES;
        CpTurns t=PalisadeHudTest.fresh();
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1),10,U32,i(100),11,STR,s("300"),
                12,ENUM,i(4),13,U16,i(1,2),16,U16,i(1),18,9,i(1)));
        t.maneuver(maneuver(1,3,0,0,0,"Current",100));
        t.maneuver(maneuver(2,1,0,0,-90,"Following",182));
        lanes(t);
        check("explicit straight maneuver reaches cluster with visible lanes",has("tbt 65 ")&&c.lastLanes[1]==1&&c.lastLanes[19]==1);
        check("explicit straight plus following left reaches HUD with visible lanes",has("hud f2024146"));
        check("straight primary retains between-turn distance",HudDistanceTest.last().equals("2c 3 0 300 2 1 0 0 0 0"));
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),18,9,i(0)));
        check("hiding lanes retains explicit straight instruction",has("tbt 65 ")&&c.lastLanes[1]==0&&!has("hud f2"));

        // Numeric pattern at both owner photo timestamps: selected left=8,
        // 2.7mi countdown, visible straight lanes, no selected straight turn.
        t=PalisadeHudTest.fresh();
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1),11,STR,s("2.7"),12,ENUM,i(1),
                13,U16,i(8),16,U16,i(1),18,9,i(1)));
        t.maneuver(maneuver(8,1,0,0,-90,"Future",4667));lanes(t);
        check("captured singleton selects distant left alongside straight lanes",has("tbt 70 ")&&has("hud f20146")&&c.lastLanes[19]==1);
        check("straight lane geometry alone cannot fabricate an intersection instruction",!has("tbt 65 ")&&!has("hud f20141"));
        c.calls.clear();t.maneuver(maneuver(7,3,0,0,0,"Unselected",100));
        check("prefetched straight detail cannot replace the phone-selected turn",c.calls.isEmpty()&&t.hudCurrent==8);
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(30),11,STR,s("100"),12,ENUM,i(4),13,U16,i(7,8)));
        check("phone selection of that straight detail renders without losing lanes",has("tbt 65 ")&&has("hud f2024146")&&c.lastLanes[19]==1);
        System.exit(fail);
    }
}
