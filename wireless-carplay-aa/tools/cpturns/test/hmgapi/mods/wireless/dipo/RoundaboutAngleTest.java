package hmgapi.mods.wireless.dipo;

import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;

/** Returned #7 failures, emitted display calls, and angle-sector boundaries. */
public final class RoundaboutAngleTest {
    static CpTurns start(int angle) {
        CpTurns t = ManeuverValidationTest.selected();
        t.maneuver(maneuver(10,29,0,1,angle,"",100));
        return t;
    }

    public static void main(String[] args) {
        for (int[] returned : new int[][] {{-87,105},{-15,97}}) {
            int angle = returned[0], icon = returned[1];
            CpTurns t = start(angle);
            check("returned " + angle + " degree roundabout reaches cluster and HUD",
                    ManeuverValidationTest.has("tbt 32 ") && t.hudType == icon);
            c.calls.clear();
            t.routeGuidance(msg(0,U16,i(1),11,STR,s("250")));
            check("returned " + angle + " degree primary countdown continues",
                    ManeuverValidationTest.has("tbt 32 0 " + ((angle+360)%360) + ".0 250.0 ")
                    && HudDistanceTest.last().startsWith("2c 3 0 250 "));
            c.calls.clear();
            t.maneuver(msg(0,U16,i(1),1,U16,i(10),5,U32,i(60)));
            check("distance-only detail preserves returned roundabout icon " + angle,
                    t.hudType == icon && !ManeuverValidationTest.has("hud f2"));
            t.maneuver(maneuver(20,2,0,0,90,"",100));
            t.routeGuidance(msg(0,U16,i(1),13,U16,i(20),11,STR,s("100")));
            check("ordinary turn replaces returned roundabout " + angle,t.hudType == 67 && t.hudCurrent == 20);
            t.stop();
            check("cancellation clears returned roundabout route " + angle,t.hudType <= 0 && t.hudCurrent == -1);
        }

        // Integer inputs straddling the midpoints between the existing canonical
        // directions. The 0/90 midpoint ties toward positive rotation; +/-180
        // share the same back-facing icon.
        int[][] boundaries = {
            {-180,102},{-158,102},{-157,104},{-113,104},{-112,105},
            {-68,105},{-67,107},{-23,107},{-22,97},{0,97},
            {44,97},{45,99},{112,99},{113,100},{157,100},{158,102},{180,102}
        };
        for (int[] row : boundaries) {
            CpTurns t = start(row[0]);
            check("roundabout sector boundary " + row[0],t.hudType == row[1]
                    && ManeuverValidationTest.has("tbt 32 "));
        }
        boolean covered = true;
        for (int angle = -180; angle <= 180; angle++) {
            int icon = CpTurns.icon(29,0,1,angle,1);
            covered &= icon == 97 || icon == 99 || icon == 100 || icon == 102 || icon == 104 || icon == 105 || icon == 107;
        }
        check("every valid signed angle has an existing supported roundabout symbol",covered);
        for (int invalid : new int[] {-360,-181,181,360,Integer.MIN_VALUE,Integer.MAX_VALUE}) {
            check("out-of-range roundabout angle refused " + invalid,CpTurns.icon(29,0,1,invalid,1) == 0);
        }
        CpTurns t = start(-87);
        c.calls.clear();
        t.maneuver(msg(0,U16,i(1),1,U16,i(10),11,U8,i(-15)));
        check("changed supplied angle refreshes both outputs",t.hudType == 97
                && ManeuverValidationTest.has("tbt 32 0 345.0 ") && ManeuverValidationTest.has("hud f20161"));
        c.calls.clear();
        t.maneuver(msg(0,U16,i(1),1,U16,i(10),8,ENUM,i(1)));
        check("traffic-side change cannot reuse right-traffic geometry",t.hudType <= 0);
        t = ManeuverValidationTest.selected();
        t.maneuver(msg(0,U16,i(1),1,U16,i(10),3,ENUM,i(29),9,ENUM,i(1)));
        check("missing exit angle still cannot invent a roundabout symbol",t.hudType <= 0);
        check("numbered roundabout sectors do not change intersection turns",CpTurns.icon(1,0,0,-87,1) == 0);
        check("numbered roundabout sectors do not infer left-traffic symbols",CpTurns.icon(29,1,1,-87,1) == 0);
        System.exit(fail);
    }
}
