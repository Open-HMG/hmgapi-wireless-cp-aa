package hmgapi.mods.wireless.dipo;

import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/** Destination and remaining trip must work without a directional maneuver. */
public final class ClusterContextTest {
    static CpTurns t;
    static void fresh() { Car.unit=Car.DISTANCE_MILES;c=new Cluster();t=new CpTurns(new android.content.Context(),c,null); }
    static boolean has(String prefix) { for(String s:c.calls)if(s.startsWith(prefix))return true;return false; }
    static int count(String prefix) { int n=0;for(String s:c.calls)if(s.startsWith(prefix))n++;return n; }
    static void route(int state,int metres,String destination) {
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(state),4,STR,s(destination),7,U32,i(metres),9,ENUM,i(1)));
    }
    public static void main(String[] args) {
        fresh();route(1,1609,"Fixture destination");
        check("active positive route publishes destination without selection/details",has("dest 0 [Fixture destination]"));
        check("active positive route publishes remaining trip without turn details",has("sub 1.0 0 2 "));
        check("context-only route creates no synthetic turn or HUD list",!has("tbt ")&&!has("hud ")&&!has("2c "));
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),7,U32,i(1287)));
        check("remaining distance updates independently while details are missing",has("sub 0.8 0 2 ")&&!has("dest "));
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),4,STR,s("Changed destination")));
        check("destination change during state 1 reaches cluster immediately",has("dest 0 [Changed destination]"));
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),4,STR,s("")));
        check("explicit empty destination clears old name",has("dest 0 []"));

        fresh();t.maneuver(maneuver(0,11,0,0,0,"",0));
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(6),4,STR,s("Fixture destination"),7,U32,i(1609),9,ENUM,i(1),13,U16,i(0)));
        check("captured ProceedToRoute/depart pattern publishes both context channels",has("dest 0 [Fixture destination]")&&has("sub 1.0 0 2 "));
        check("departure publishes upstream NO_INFO without a directional HUD arrow",has("tbt 1 ")&&!has("hud f201")&&!has("hud f202"));
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1),13,U16,i(10)));
        t.maneuver(maneuver(10,2,0,0,90,"Fixture road",100));
        check("late valid turn coexists with previously published destination/trip",has("tbt 67 ")&&has("sub 1.0 0 2 ")&&!has("dest "));
        check("numeric context diagnostics omit destination and road text",
                Dipo.trace.toString().contains("destinationChars=19")
                &&Dipo.trace.toString().contains("cluster-trip metres=1609.0")
                &&!Dipo.trace.toString().contains("Fixture destination")&&!Dipo.trace.toString().contains("Fixture road"));

        fresh();t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1),8,STR,s("0.5"),9,ENUM,i(1)));
        check("formatted remaining distance works when raw is absent",has("sub 0.5 0 2 "));
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),7,U32,i(1609),8,STR,s("99")));
        check("same-message raw remaining distance has precedence",has("sub 1.0 0 2 "));
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),8,STR,s("0.2")));
        check("new display-only remaining value replaces old raw measurement",has("sub 0.2 0 2 "));
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),7,U32,i(0)));
        check("explicit zero remaining distance is published",has("sub 0.0 0 0 "));
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),7,U32,i(-1)));
        check("invalid remaining value clears old trip instead of freezing it",has("sub 0.0 -1 0 0 0 0 0 "));
        fresh();t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1),8,STR,s("1.0")));
        check("display remaining without supplied units is not guessed",!has("sub "));

        fresh();route(1,1609,"Fixture destination");c.calls.clear();t.stop();
        check("cancellation clears destination and trip once before idle handoff",count("dest 0 []")==1&&count("sub 0.0 -1")==1&&!t.routeActive);
        c.calls.clear();t.stop();
        check("repeated idle does not spam context clears",!has("dest ")&&!has("sub "));
        t.maneuver(maneuver(1,2,0,0,90,"Late",100));
        check("late detail cannot restore cancelled route context",!has("dest ")&&!has("sub "));
        fresh();route(1,1609,"Fixture destination");c.calls.clear();t.disconnected();
        check("disconnect clears old destination/trip",has("dest 0 []")&&has("sub 0.0 -1"));
        fresh();route(1,1609,"Fixture destination");c.calls.clear();t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(2)));
        check("arrival clears context while retaining compass handoff",has("dest 0 []")&&has("sub 0.0 -1")&&!t.routeActive);
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1)));
        check("new route after arrival cannot reuse old destination/trip",!has("dest ")&&!has("sub "));
        final boolean[] clearedBeforeIdle={false};
        c=new Cluster();t=new CpTurns(new android.content.Context(),c,null,new CpTurns.RouteStateSink(){
            @Override public void active(boolean value) {
                if(!value)clearedBeforeIdle[0]=has("dest 0 []")&&has("sub 0.0 -1");
            }
        });
        route(1,1609,"Fixture destination");c.calls.clear();t.stop();
        check("route context clears before compass ownership is released",clearedBeforeIdle[0]);
        System.exit(fail);
    }
}
