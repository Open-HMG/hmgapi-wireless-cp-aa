package hmgapi.mods.wireless.dipo;

import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;

/** Stock photo-correlated schematic types; keep HUD symbols and lifecycle intact. */
public final class ClusterGeometryTest {
    static boolean has(String s) { return ManeuverValidationTest.has(s); }
    static void distance(CpTurns t, int metres) {
        c.calls.clear();
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(metres)));
    }
    public static void main(String[] args) {
        CpTurns t = ManeuverValidationTest.selected();
        t.maneuver(maneuver(10,2,0,0,90,"",100));
        check("near right uses photographed type13 at90 while HUD keeps67",has("tbt 13 0 90.0 ") && t.hudType==67);
        distance(t,501);
        check("far right returns compact cluster instruction",has("tbt 67 0 0.0 ") && t.hudType==67);
        distance(t,500);
        check("500 metre boundary enters schematic with bounded bar",has("tbt 13 0 90.0 ") && c.calls.toString().contains(" 0 0 3 100 0"));
        distance(t,0);
        check("zero countdown keeps geometry until maneuver advances",has("tbt 13 0 90.0 0.0 0 0 3 0 0"));
        t.maneuver(maneuver(20,1,0,0,-90,"",100));
        t.routeGuidance(msg(0,U16,i(1),13,U16,i(20)));
        check("promotion without fresh distance replaces shape with compact left",has("tbt 70 0 0.0 0.0 -1 ") && t.hudType==70);
        distance(t,100);
        check("fresh promoted left uses photographed270 degrees",has("tbt 13 0 270.0 ") && t.hudType==70);
        c.calls.clear();
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),5,U32,i(80)));
        check("partial detail refresh preserves same selected geometry",has("tbt 13 0 270.0 "));
        t.routeGuidance(msg(0,U16,i(1),13,U16,i(99)));
        check("uncached promotion clears stale schematic",has("tbt 0 0 0.0 0.0 -1 ") && t.hudType<=0);
        t=ManeuverValidationTest.selected();
        t.maneuver(maneuver(10,29,0,1,-87,"",100));
        check("minus87 supplies actual direction273 to roundabout shape and105 toHUD",has("tbt 32 0 273.0 ") && t.hudType==105);
        distance(t,600);
        check("rerouted farther distance restores compact roundabout",has("tbt 105 0 0.0 "));
        distance(t,100);
        check("closer update restores roundabout schematic",has("tbt 32 0 273.0 "));
        t.maneuver(maneuver(10,29,0,1,-15,"",100));
        check("minus15 changes roundabout direction345 and preserves HUD97",has("tbt 32 0 345.0 ") && t.hudType==97);
        c.calls.clear();t.stop();
        check("route cancellation clears schematic and HUD",has("tbt 0 ") && t.hudType<=0);
        for (float invalid : new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1f,501f})
            check("unavailable or distant input keeps compact fallback "+invalid,CpTurns.clusterShape(29,0,1,-87,true,invalid,1)==0);
        check("missing angle cannot invent a schematic",CpTurns.clusterShape(29,0,1,0,false,100,1)==0);
        check("left traffic is not assigned unverified stock geometry",CpTurns.clusterShape(29,1,1,-87,true,100,1)==0);
        check("arrival cannot retain schematic",CpTurns.clusterShape(29,0,1,-87,true,100,2)==0);
        check("supported non-right-angle turn uses schematic",CpTurns.clusterShape(1,0,0,-120,true,100,1)==13);
        check("straight and ramp types do not become junction drawings",CpTurns.clusterShape(3,0,0,0,true,100,1)==0 && CpTurns.clusterShape(47,0,0,-90,true,100,1)==0);
        check("out of range angle rejected",CpTurns.clusterShape(29,0,1,181,true,100,1)==0);
        System.exit(fail);
    }
}
