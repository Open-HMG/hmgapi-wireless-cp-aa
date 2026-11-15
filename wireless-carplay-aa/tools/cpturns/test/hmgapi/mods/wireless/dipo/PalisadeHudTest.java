package hmgapi.mods.wireless.dipo;

import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;
import android.os.SystemClock;
import hmgapi.hook.Cluster;

/** Regression cases for HUD-only stalls and late second maneuvers. */
public final class PalisadeHudTest {
    static CpTurns fresh() {
        c = new Cluster();
        Dipo.trace.clear();
        SystemClock.now = 10000;
        return new CpTurns(new android.content.Context(), c, null);
    }
    static void route(CpTurns t, int... indices) {
        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 11, STR, s("0.2"),
                12, ENUM, i(1), 13, U16, indices));
    }
    static int hudCount() {
        int n = 0;
        for (String call : c.calls) if (call.startsWith("hud f2")) n++;
        return n;
    }
    static boolean hasHud(String prefix) {
        for (String call : c.calls) if (call.startsWith(prefix)) return true;
        return false;
    }
    public static void main(String[] args) {
        CpTurns t = fresh();
        route(t, 0, 1);
        t.maneuver(maneuver(0, 2, 0, 0, 90, "Private Road", 300));
        check("late primary renders without another route message", hasHud("hud f20143"));
        c.calls.clear();
        t.maneuver(maneuver(1, 1, 0, 0, -90, "Next Road", 0));
        check("late second turn renders even before its distance arrives", hasHud("hud f2024346"));
        c.calls.clear();
        route(t, 1);
        check("next maneuver is promoted to primary on route advance", hasHud("hud f2014600"));
        route(t, 0, 1);
        c.calls.clear();
        t.maneuver(maneuver(9, 2, 0, 0, 90, "Unrelated", 200));
        check("unlisted maneuver does not render", c.calls.isEmpty());

        route(t, 0, 1);
        c.calls.clear();
        SystemClock.now += 1999;
        route(t, 0, 1);
        check("identical HUD suppressed before two seconds", hudCount() == 0);
        c.calls.clear();
        SystemClock.now++;
        route(t, 0, 1);
        check("incoming route refresh resends identical HUD at two seconds", hudCount() == 1);
        c.calls.clear();
        route(t, 0, 1);
        check("same-time refresh does not resend again", hudCount() == 0);

        t.maneuver(maneuver(2, 2, 0, 0, 90, "Private Road", 300));
        c.calls.clear();
        route(t, 2, 1);
        check("new current index resends identical arrows and road", hudCount() == 1);
        c.calls.clear();
        t.maneuver(maneuver(1, 12, 0, 0, 0, "Destination", 100));
        check("unsupported secondary type produces single-turn HUD", hasHud("hud f2014300"));

        t = fresh();
        route(t, 0);
        t.maneuver(msg(1, U16, i(0), 3, ENUM, i(3), 4, STR, s("No ID")));
        check("id-less late maneuver reaches known route with no cached maneuvers", hasHud("hud f20141"));
        c.calls.clear();
        route(t);
        check("empty route list clears obsolete HUD output", hasHud("hud f2") && !hasHud("hud f201"));
        check("empty-list clear has a numeric diagnostic reason",Dipo.trace.toString().contains("turn-clear reason=empty-selection"));
        route(t, 0);
        t.stop();
        c.calls.clear();
        route(t, 0);
        check("stop discards old maneuver cache until fresh details arrive", hudCount() == 0);
        t.maneuver(msg(1, U16, i(0), 3, ENUM, i(3), 4, STR, s("No ID")));
        check("fresh details after restart render the selected primary", hudCount() == 1);
        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(0)));
        check("route cancellation traces its component and cause",Dipo.trace.toString().contains("route-stop component=1 state=0")&&Dipo.trace.toString().contains("reset reason=route-state-zero"));
        c.calls.clear();
        t.maneuver(maneuver(0, 2, 0, 0, 90, "Cancelled", 100));
        check("cancelled guidance is not revived by late details", c.calls.isEmpty());
        t.disconnected();
        check("session disconnect has a distinct reset cause",Dipo.trace.toString().contains("reset reason=disconnect"));
        c.calls.clear();
        t.maneuver(maneuver(0, 2, 0, 0, 90, "Stale", 100));
        check("disconnected route cannot be revived by maneuver alone", c.calls.isEmpty());
        check("diagnostic trace excludes road strings", !Dipo.trace.toString().contains("Stale") && !Dipo.trace.toString().contains("No ID"));
        com.tcc.iap2.Iap2NotiMsg lanes = new com.tcc.iap2.Iap2NotiMsg();
        com.tcc.iap2.Iap2NotiMsg.Iap2ObjGroup lane = lanes.new Iap2ObjGroup();
        lane.add(1, U16, i(2)).add(2, U16, i(0,90)).add(3, U16, i(90));
        lanes.getItemGroup().add(2, GROUPS, new com.tcc.iap2.Iap2NotiMsg.Iap2ObjGroup[] {lane});
        t.lanes(lanes);
        check("lane diagnostics retain numeric highlight values and existing mapping",
                Dipo.trace.toString().contains("3:5[90]") && Dipo.trace.toString().contains("output=[1, 3, 1, 0]"));
        System.exit(fail);
    }
}
