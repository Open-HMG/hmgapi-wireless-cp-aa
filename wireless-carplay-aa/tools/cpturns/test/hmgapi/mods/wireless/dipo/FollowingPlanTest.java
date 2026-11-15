package hmgapi.mods.wireless.dipo;

import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;
import hmgapi.hook.Car;
import java.util.Arrays;

/** Numeric November 10/12 return patterns, including the observed real promotion.
 * Reconstructs logged values; it cannot establish physical HUD interpretation. */
public final class FollowingPlanTest {
    static CpTurns t;
    static void fresh() { Car.unit=Car.DISTANCE_MILES;t=PalisadeHudTest.fresh(); }
    static void route(int primary,int secondary,int metres,String text,int unit,int count) {
        t.routeGuidance(msg(0,U16,i(5),1,ENUM,i(1),10,U32,i(metres),11,STR,s(text),12,ENUM,i(unit),
                13,U16,secondary<0?i(primary):i(primary,secondary),14,U16,i(count)));
    }
    static void detail(int index,int type,int angle,int metres,String display,int unit) {
        t.maneuver(msg(0,U16,i(5),1,U16,i(index),3,ENUM,i(type),8,ENUM,i(0),9,ENUM,i(0),
                11,U8,i(angle),5,U32,i(metres),6,STR,s(display),7,ENUM,i(unit),4,STR,s("PRIVATE ROAD")));
    }
    static String last() { for(int n=c.calls.size()-1;n>=0;n--)if(c.calls.get(n).startsWith("2c "))return c.calls.get(n);return "missing"; }
    static void november12() {
        fresh();route(3,4,840,"0.5",1,14);
        // Same type/angle/raw/formatted pattern as the return. Supply the next
        // plan entry first to ensure insertion order is not used as route order.
        detail(5,2,90,321,"0.2",1);detail(3,1,-90,804,"0.5",1);detail(4,2,0,804,"0.5",1);
    }
    public static void main(String[] args) {
        november12();
        check("November12 primary 0.5mi / actual following 0.2mi replaces skipped 0.5mi entry",last().equals("2c 2 5 0 2 2 0 0 0 0"));
        check("HUD icon and distance use the same resolved following index",t.hudFollowing==5&&t.hudNext==67);
        check("phone primary and active selection remain unchanged",t.hudCurrent==3&&Arrays.equals(t.updates.get(5).list,i(3,4)));
        route(3,4,10,"30",4,14);
        check("captured approach keeps 0.2mi interval while primary counts down",last().equals("2c 3 0 30 2 2 0 0 0 0"));
        route(3,4,0,"0",4,14);
        check("zero primary does not add itself to the following interval",last().equals("2c 3 0 0 2 2 0 0 0 0"));
        route(3,5,0,"0",4,14);
        check("wire replacement selects the same 0.2mi real following",t.hudFollowing==5&&last().equals("2c 3 0 0 2 2 0 0 0 0"));
        route(5,-1,317,"0.2",1,14);
        check("observed promotion starts 0.2mi primary and clears secondary",last().equals("2c 2 2 0 0 0 0 0 0 0")&&t.hudCurrent==5);
        fresh();route(2,3,136,"450",4,12);detail(2,1,-90,182,"0.1",1);detail(3,2,0,182,"0.1",1);detail(4,2,90,76,"250",4);
        check("November10 close-turn interval uses actual following 250ft rather than skipped 0.1mi",last().equals("2c 3 0 450 3 0 250 0 0 0"));
        route(5,6,792,"0.5",1,12);detail(5,2,90,1287,"0.8",1);detail(6,2,0,804,"0.5",1);detail(7,2,90,76,"250",4);
        check("second November10 pair resolves without assuming duplicate primary distance",last().equals("2c 2 5 0 3 0 250 0 0 0"));
        fresh();route(3,4,840,"0.5",1,14);detail(3,1,-90,804,"0.5",1);detail(4,2,0,804,"0.5",1);
        check("missing adjacent detail cannot emit the known misleading secondary",t.hudNext==0&&last().equals("2c 2 5 0 0 0 0 0 0 0"));
        detail(8,2,90,76,"250",4);
        check("resolver never searches arbitrary cached future entries",t.hudNext==0);
        detail(5,2,90,321,"0.2",1);
        check("late adjacent plan detail refreshes paired icon and distance immediately",t.hudNext==67&&t.hudFollowing==5&&last().endsWith(" 2 2 0 0 0 0"));
        t.maneuver(msg(0,U16,i(5),1,U16,i(5),11,U8,i(0)));
        check("invalidated adjacent geometry clears the old secondary immediately",t.hudNext==0&&last().endsWith(" 0 0 0 0 0 0"));
        november12();detail(5,1,-90,321,"0.2",1);
        check("adjacent direction mismatch is ambiguous and clears secondary",t.hudNext==0);
        november12();route(3,4,840,"0.5",1,5);
        check("adjacent entry outside supplied route count is not selected",t.hudNext==0);
        november12();route(2,4,840,"0.5",1,14);detail(2,1,-90,804,"0.5",1);
        check("noncontiguous current selection cannot justify a plan replacement",t.hudNext==0);
        november12();t.maneuver(msg(0,U16,i(5),1,U16,i(4),3,ENUM,i(2),8,ENUM,i(0),9,ENUM,i(1),11,U8,i(0),5,U32,i(804)));
        check("genuine zero-angle roundabout selection is retained",t.hudFollowing==4&&t.hudNext!=0);
        november12();detail(4,3,0,76,"250",4);
        check("straight selected secondary remains valid at zero angle",t.hudFollowing==4&&last().endsWith(" 3 0 250 0 0 0"));
        november12();detail(4,2,90,76,"250",4);
        check("supported selected secondary keeps authoritative wire selection",t.hudFollowing==4&&last().endsWith(" 3 0 250 0 0 0"));
        november12();t.routeGuidance(msg(0,U16,i(6),1,ENUM,i(1),10,U32,i(840),11,STR,s("0.5"),12,ENUM,i(1),13,U16,i(3,4),14,U16,i(14)));
        t.maneuver(msg(0,U16,i(6),1,U16,i(3),3,ENUM,i(1),11,U8,i(-90)));
        t.maneuver(msg(0,U16,i(6),1,U16,i(4),3,ENUM,i(2),11,U8,i(0)));
        check("another component's plan cannot supply a secondary",t.hudComponent==6&&t.hudNext==0);
        november12();t.maneuver(msg(0,U16,i(5),1,U16,i(4),7,ENUM,i(1)));
        check("partial auxiliary units preserve its role and the short interval",t.hudFollowing==5&&last().endsWith(" 2 2 0 0 0 0"));
        t.maneuver(msg(0,U16,i(5),1,U16,i(5),5,U32,i(321)));
        check("partial real-turn distance retains supplied geometry for HUD association",t.hudFollowing==5);
        november12();t.routeGuidance(msg(0,U16,i(5),1,ENUM,i(5)));route(3,4,840,"0.5",1,14);
        check("reroute reusing indices cannot resolve against stale adjacent geometry",t.hudNext==0);
        detail(3,1,-90,804,"0.5",1);detail(5,2,90,321,"0.2",1);
        check("fresh post-reroute geometry restores the short secondary",t.hudFollowing==5&&last().endsWith(" 2 2 0 0 0 0"));
        fresh();route(3,4,840,"0.5",1,14);detail(3,1,-90,804,"0.5",1);detail(5,2,90,321,"0.2",1);
        t.maneuver(msg(0,U16,i(5),1,U16,i(4),3,ENUM,i(2),9,ENUM,i(0),5,U32,i(804)));
        check("contiguous captured pattern also resolves when auxiliary exit is absent",t.hudFollowing==5&&last().endsWith(" 2 2 0 0 0 0"));
        fresh();route(3,4,840,"0.5",1,14);detail(3,2,90,804,"0.5",1);detail(4,1,0,804,"0.5",1);detail(5,1,-120,76,"250",4);
        t.maneuver(msg(0,U16,i(5),1,U16,i(5),5,U32,i(76)));
        check("resolved secondary retains its exact icon through distance-only updates",t.hudFollowing==5&&t.hudNext==69);
        november12();t.stop();route(3,4,840,"0.5",1,14);detail(3,1,-90,804,"0.5",1);detail(4,2,0,804,"0.5",1);
        check("new route cannot reuse a stopped route's adjacent detail",t.hudNext==0);
        november12();t.routeGuidance(msg(0,U16,i(5),19,STR,s("Maps")));t.routeGuidance(msg(0,U16,i(5),19,STR,s("Other")));
        check("source changes clear the resolved secondary",t.hudFollowing==-1&&last().equals("2c 0 0 0 0 0 0 0 0 0"));
        check("new mapping diagnostics contain indices rather than road text",Dipo.trace.toString().contains("wireNext=4")&&!Dipo.trace.toString().contains("PRIVATE ROAD"));
        System.exit(fail);
    }
}
