package hmgapi.mods.wireless.dipo;

import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;

/** Issues 4/7: inspect emitted calls, including partial and unsupported inputs. */
public final class ManeuverValidationTest {
    static boolean has(String prefix) {
        for (String call : c.calls) if (call.startsWith(prefix)) return true;
        return false;
    }

    static CpTurns selected() {
        CpTurns t = PalisadeHudTest.fresh();
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1),13,U16,i(10),11,STR,s("500"),12,ENUM,i(4)));
        return t;
    }

    public static void main(String[] args) {
        // Explicit expectations inventory the existing DiPO mapping. They do not
        // establish the artwork or introduce interpolated angle/exit numbers.
        int[][] cases = {
            {1,0,0,-120,69}, {1,0,0,-90,70}, {1,0,0,-60,71}, {1,0,0,-45,145},
            {2,0,0,60,66}, {2,0,0,90,67}, {2,0,0,120,68}, {2,0,0,45,144},
            {3,0,0,0,65}, {4,0,0,0,72}, {4,1,0,0,73},
            {5,0,0,0,172}, {12,0,0,0,112}, {15,0,0,0,176},
            {22,0,0,0,82}, {23,0,0,0,81}, {24,0,0,0,114}, {25,0,0,0,113},
            {47,0,0,-140,69}, {47,0,0,-30,71}, {48,0,0,30,66}, {48,0,0,140,68},
            {49,0,0,-45,71}, {50,0,0,45,66}, {52,0,0,0,174}, {53,0,0,0,173},
            {1,1,1,-120,104}, {1,1,1,-90,105}, {1,1,1,-30,107},
            {2,0,1,0,96}, {2,1,1,0,97}, {2,1,1,90,99}, {2,1,1,120,100}, {2,1,1,180,102},
            {19,0,1,-180,102}, {28,0,1,135,100}, {29,0,1,90,99},
            {31,0,1,0,97}, {31,1,1,0,96}, {32,0,1,-45,107},
            {33,0,1,-90,105}, {34,0,1,-135,104}, {35,0,1,-180,102},
            {30,0,1,0,97}, {36,0,1,90,99}, {46,0,1,-90,105},
            {99,0,0,0,0}, {29,0,1,89,99}, {28,1,1,135,0},
            {31,0,1,45,99}, {3,1,0,0,65}, {1,0,0,-89,0}
        };
        for (int[] row : cases) {
            CpTurns t = selected();
            t.maneuver(maneuver(10,row[0],row[1],row[2],row[3],"",100));
            int icon = row[4];
            int clusterType = row[0]>=28 && row[0]<=46 && row[1]==0 && row[2]==1 && icon>0 ? 32
                    : row[1]==0 && row[2]==0 && icon>0 && (row[0]==1 || row[0]==2 || row[0]==47 || row[0]==48 || row[0]==49 || row[0]==50) ? 13 : icon;
            check("emitted mapping " + java.util.Arrays.toString(row),
                    icon == 0 ? !has("tbt ") && t.hudType <= 0
                    : has("tbt " + clusterType + " ") && t.hudType == icon);
        }
        for (int ordinal = 1; ordinal <= 19; ordinal++) {
            CpTurns t = selected();
            t.maneuver(maneuver(10,27+ordinal,0,1,90,"",100));
            check("numbered roundabout exit " + ordinal + " uses supplied geometry",has("tbt 32 ") && t.hudType == 99);
        }

        CpTurns t = selected();
        t.maneuver(maneuver(10,2,1,1,90,"",100));
        c.calls.clear();
        t.maneuver(msg(0,U16,i(1),1,U16,i(10),5,U32,i(80)));
        check("roundabout distance-only detail retains proven 90-degree icon instead of zero-degree icon",
                t.hudType == 99 && !has("tbt 97 ") && !has("hud f20161"));
        t.routeGuidance(msg(0,U16,i(1),13,U16,i(20)));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),3,ENUM,i(31),9,ENUM,i(1)));
        check("new roundabout without exit geometry cannot invent the zero-degree icon",t.hudType <= 0);
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),11,U8,i(0)));
        check("explicit zero-degree roundabout exit renders",t.hudType == 97);
        c.calls.clear();
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),8,ENUM,i(1)));
        check("changed traffic side without geometry cannot retain or invent a roundabout icon",t.hudType <= 0);
        t = selected();
        t.maneuver(maneuver(10,31,0,1,181,"",100));
        c.calls.clear();
        t.maneuver(msg(0,U16,i(1),1,U16,i(10),5,U32,i(80)));
        check("unsupported roundabout geometry cannot become a zero-degree instruction on partial update",
                t.hudType <= 0 && !has("tbt 97 "));

        t = selected();
        t.maneuver(maneuver(10,2,0,0,90,"",100));
        c.calls.clear();
        t.routeGuidance(msg(0,U16,i(1),13,U16,i(99)));
        check("uncached primary clears the old cluster turn as well as the HUD",has("tbt 0 0 0.0 0.0 -1 ") && t.hudType <= 0);
        t.maneuver(maneuver(99,1,0,0,-90,"",100));
        c.calls.clear();
        t.maneuver(maneuver(99,99,0,0,0,"",100));
        check("unknown maneuver clears both displays instead of retaining a different cluster instruction",has("tbt 0 0 0.0 0.0 -1 ") && t.hudType <= 0);

        t = selected();
        t.maneuver(maneuver(10,2,0,0,90,"",100));
        c.calls.clear();
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(30)));
        check("new raw primary distance replaces stale formatted 500 ft countdown",
                has("tbt 13 0 90.0 100.0") && HudDistanceTest.last().startsWith("2c 3 0 98 "));
        c.calls.clear();
        t.routeGuidance(msg(0,U16,i(1),11,STR,s("250"),12,ENUM,i(4)));
        check("formatted primary distance retains exact feet",has("tbt 13 0 90.0 250.0 ")
                && HudDistanceTest.last().startsWith("2c 3 0 250 "));
        c.calls.clear();
        t.routeGuidance(msg(0,U16,i(1),13,U16,i(20)));
        t.maneuver(maneuver(20,1,0,0,-90,"",100));
        check("promotion cannot reuse the previous primary countdown",has("tbt 70 0 0.0 0.0 -1 ")
                && HudDistanceTest.last().startsWith("2c 0 0 0 "));
        c.calls.clear();
        t.routeGuidance(msg(0,U16,i(1),11,STR,s("-10"),12,ENUM,i(4)));
        check("negative formatted distance is unavailable rather than sent to the cluster",has("tbt 70 0 0.0 0.0 -1 "));

        t = PalisadeHudTest.fresh();
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1),13,U16,i(10),10,U32,i(100)));
        t.maneuver(maneuver(10,3,0,0,0,"",100));
        check("raw metres without phone units are not silently interpreted as kilometres",
                has("tbt 65 0 0.0 100.0 0 0 0 ") && HudDistanceTest.last().startsWith("2c 0 0 100 "));
        int[] phoneUnits = {0,1,2,3,4};
        int[] clusterUnits = {1,2,0,4,3};
        String[] display = {"2c 1 6 1 ","2c 2 0 1 ","2c 0 0 1609 ","2c 4 0 1759 ","2c 3 0 4095 "};
        for (int k = 0; k < phoneUnits.length; k++) {
            c.calls.clear();
            t.routeGuidance(msg(0,U16,i(1),10,U32,i(1609),12,ENUM,i(phoneUnits[k])));
            check("raw primary conversion for phone unit " + phoneUnits[k],HudDistanceTest.last().startsWith(display[k]));
            c.calls.clear();
            t.routeGuidance(msg(0,U16,i(1),11,STR,s("0.5")));
            check("formatted primary preserves phone unit " + phoneUnits[k],has("tbt 65 0 0.0 0.5 0 0 " + clusterUnits[k] + " "));
        }
        for (String invalid : new String[] {"NaN","Infinity","-1","bad",""}) {
            c.calls.clear();
            t.routeGuidance(msg(0,U16,i(1),11,STR,s(invalid)));
            check("invalid primary display is unavailable: " + invalid,has("tbt 65 0 0.0 0.0 -1 ")
                    && HudDistanceTest.last().startsWith("2c 0 0 0 "));
        }
        c.calls.clear();
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(0),12,ENUM,i(2)));
        check("explicit raw zero remains a valid cluster countdown",has("tbt 65 0 0.0 0.0 0 0 0 "));
        c.calls.clear();
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(100),11,STR,s("NaN"),12,ENUM,i(99)));
        check("unknown unit and invalid text fall back to supplied raw metres",has("tbt 65 0 0.0 100.0 0 0 0 "));
        System.exit(fail);
    }
}
