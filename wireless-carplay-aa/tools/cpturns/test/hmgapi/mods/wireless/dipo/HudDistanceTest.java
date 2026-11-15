package hmgapi.mods.wireless.dipo;

import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;
import hmgapi.hook.Car;
import java.util.Arrays;

/** Following distance is the interval from the primary to the selected following turn. */
public final class HudDistanceTest {
    static CpTurns t;
    static void fresh(int owner) {
        Car.unit=owner;t=PalisadeHudTest.fresh();
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(1),10,U32,i(0),11,STR,s("0.2"),12,ENUM,i(1),13,U16,i(10,20)));
        t.maneuver(maneuver(10,2,0,0,90,"Primary",300));
    }
    static String last() {
        for(int k=c.calls.size()-1;k>=0;k--)if(c.calls.get(k).startsWith("2c "))return c.calls.get(k);
        return "missing";
    }
    static boolean point2(int unit,int fraction,int whole) {
        return last().endsWith(" "+unit+" "+fraction+" "+whole+" 0 0 0");
    }
    static void following(int metres) {t.maneuver(maneuver(20,1,0,0,-90,"Following",metres));}
    public static void main(String[] args) {
        fresh(Car.DISTANCE_MILES);following(800);
        check("following interval populates point 2",point2(2,5,0));
        check("following distance belongs to the selected pair",t.hudCurrent==10&&t.hudFollowing==20&&t.hudNext==70);
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(1000)));
        check("following interval excludes raw primary distance",point2(2,5,0));
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(500)));
        check("following interval stays fixed during raw primary countdown",point2(2,5,0));
        t.routeGuidance(msg(0,U16,i(1),11,STR,s("0.1")));
        check("primary display changes do not change the interval",point2(2,5,0));
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(0)));following(100);
        check("short interval uses feet",point2(3,0,328));
        fresh(Car.DISTANCE_KM);following(965);
        check("metric zero primary preserves following metres",point2(0,0,965));
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(100)));
        check("primary distance cannot move interval across unit threshold",point2(0,0,965));
        following(2500);
        check("long metric interval uses kilometres and fraction",point2(1,5,2));
        fresh(Car.DISTANCE_MILES);
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),3,ENUM,i(1),11,U8,i(-90),6,STR,s("0.2"),7,ENUM,i(1)));
        check("formatted following interval is fallback when raw is absent",point2(2,2,0));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),5,U32,i(1609)));
        check("new raw interval invalidates stale display text",point2(2,0,1)&&t.maneuvers.get(1).get(20).distStr.equals(""));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),6,STR,s("NaN")));
        check("nonfinite interval text cannot override valid raw metres",point2(2,0,1));
        t.maneuver(msg(0,U16,i(1),1,U16,i(10),5,U32,i(250)));
        check("partial primary still transmits following interval",point2(2,0,1));
        t.routeGuidance(msg(0,U16,i(1),13,U16,i(10)));
        check("removing following selection clears point 2",point2(0,0,0));
        t.routeGuidance(msg(0,U16,i(1),13,U16,i(10,99)));
        check("uncached following selection cannot retain old distance",point2(0,0,0));
        t.stop();check("navigation takeover clears all distance points",last().equals("2c 0 0 0 0 0 0 0 0 0"));
        fresh(-1);following(1000);
        check("unknown owner unit uses current phone unit",point2(2,6,0));
        int[] fields=new int[3];CpTurns.hudDistance(0.96f,2,fields,0);
        check("fractional distance rounds with carry",Arrays.equals(fields,i(2,0,1)));
        fields=new int[3];CpTurns.hudDistance(Float.POSITIVE_INFINITY,2,fields,0);
        check("nonfinite distance never reaches vehicle",Arrays.equals(fields,i(0,0,0)));
        check("following road diagnostics contain counts without text",Dipo.trace.toString().contains("nextRoadBytes=18")&&!Dipo.trace.toString().contains("Following"));
        fresh(Car.DISTANCE_KM);following(100);
        t.maneuver(maneuver(30,2,0,0,90,"Third",200));
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(800),13,U16,i(20,30)));
        check("promotion selects new between-turn interval",point2(0,0,200));
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(80)));
        check("promoted interval stays fixed while new primary counts down",point2(0,0,200));
        t.routeGuidance(msg(0,U16,i(1),13,U16,i(10,20)));
        check("selected following interval does not depend on a primary measurement",point2(0,0,100));
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(50)));
        check("new primary distance leaves following interval unchanged",point2(0,0,100));
        t.routeGuidance(msg(0,U16,i(1),11,STR,s("NaN")));
        check("invalid primary countdown does not erase a valid following interval",point2(0,0,100));
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(-1)));
        check("rejected primary distance is not added to a following interval",point2(0,0,100));
        fresh(Car.DISTANCE_KM);
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),3,ENUM,i(1),11,U8,i(-90)));
        check("unknown interval is not silently treated as zero",point2(0,0,0));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),5,U32,i(0)));
        check("explicit zero interval is valid",t.maneuvers.get(1).get(20).distanceKnown&&point2(0,0,0));
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(200),11,STR,s("99"),12,ENUM,i(1)));
        check("explicit zero interval remains zero with a nonzero primary",point2(0,0,0));
        t.routeGuidance(msg(0,U16,i(1),11,STR,s("100"),12,ENUM,i(4)));
        check("feet-only primary is not included in the interval",point2(0,0,0));
        t.routeGuidance(msg(0,U16,i(1),11,STR,s("100"),12,ENUM,i(3)));
        boolean yards=point2(0,0,0);
        t.routeGuidance(msg(0,U16,i(1),12,ENUM,i(4),12,ENUM,i(4)));
        check("primary yards and repeated units leave zero interval intact",yards&&point2(0,0,0));
        check("point 3 always remains unused",last().endsWith(" 0 0 0"));
        fresh(Car.DISTANCE_KM);following(800);
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(100)));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),6,STR,s("200"),7,ENUM,i(2)));
        check("new display-only following interval supersedes older raw metres",point2(0,0,200));
        check("distance diagnostics expose exact inputs and selected representations",
                Dipo.trace.toString().contains("currentMetres=100.0 intervalMetres=200.0 totalMetres=300.0 currentSource=1 intervalSource=2 point2Metres=200.0 distanceMode=interval"));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),5,U32,i(400),6,STR,s("999")));
        check("dedicated between-turn display wins over conflicting raw value",point2(0,0,999));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),6,STR,s("NaN")));
        check("invalid display interval retains valid raw interval",point2(0,0,400));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),6,STR,s("Infinity")));
        check("infinite display interval retains valid raw interval",point2(0,0,400));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),6,STR,s("-1")));
        check("negative display interval retains valid raw interval",point2(0,0,400));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),5,U32,i(0),6,STR,s("999")));
        check("explicit raw zero still wins over simultaneous display interval",point2(0,0,0));
        check("distance diagnostics remain free of road strings",!Dipo.trace.toString().contains("Primary")&&!Dipo.trace.toString().contains("Following"));
        fresh(Car.DISTANCE_MILES);following(3057);
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(2253),11,STR,s("1.4"),12,ENUM,i(1)));
        check("primary 1.4mi and 1.9mi interval stay separate",last().equals("2c 2 4 1 2 9 1 0 0 0"));
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(2092),11,STR,s("1.3")));
        check("primary counts down to 1.3mi while interval stays 1.9mi",last().equals("2c 2 3 1 2 9 1 0 0 0"));
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(1931),11,STR,s("1.2")));
        check("primary counts down to 1.2mi while interval stays 1.9mi",last().equals("2c 2 2 1 2 9 1 0 0 0"));
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(0),11,STR,s("0"),12,ENUM,i(4)));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),6,STR,s("30"),7,ENUM,i(4)));
        check("stock close-turn 0ft primary and 30ft following remain exact",last().equals("2c 3 0 0 3 0 30 0 0 0"));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),5,U32,i(-1)));
        check("unsupported following raw metres cannot reach point2",point2(0,0,0));
        fresh(Car.DISTANCE_MILES);following(1609);
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(1097),11,STR,s("0.7"),12,ENUM,i(1)));
        check("returned #15 example sends 0.7mi primary and 1.0mi interval",last().equals("2c 2 7 0 2 0 1 0 0 0"));
        t.routeGuidance(msg(0,U16,i(1),1,ENUM,i(5),13,U16,i(30,40)));
        check("reroute cannot retain old following distance",point2(0,0,0));
        t.routeGuidance(msg(0,U16,i(1),19,STR,s("Maps")));
        t.routeGuidance(msg(0,U16,i(1),19,STR,s("Other")));
        check("source switch clears both distance points",last().equals("2c 0 0 0 0 0 0 0 0 0"));
        fresh(Car.DISTANCE_MILES);
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(1609),11,STR,s("1.0"),12,ENUM,i(1)));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),3,ENUM,i(1),11,U8,i(-90),
                5,U32,i(1609),6,STR,s("250"),7,ENUM,i(4)));
        check("one-mile primary and conflicting raw distance retain explicit 250ft between-turn display",
                last().equals("2c 2 0 1 3 0 250 0 0 0"));
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(1287),11,STR,s("0.8")));
        check("between-turn 250ft stays fixed during primary countdown",point2(3,0,250));
        check("raw and display diagnostics expose source disagreement without road text",
                Dipo.trace.toString().contains("rawSelectedMetres=1609.0")
                &&Dipo.trace.toString().contains("displaySelectedMetres=76."));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),7,ENUM,i(4)));
        check("repeated unchanged display units preserve explicit interval",point2(3,0,250));
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),5,U32,i(100)));
        check("new raw-only interval invalidates old display before fallback",point2(3,0,328));
        fresh(-1);
        t.maneuver(msg(0,U16,i(1),1,U16,i(20),3,ENUM,i(1),11,U8,i(-90),6,STR,s("250"),7,ENUM,i(4)));
        check("unknown owner units preserve phone's explicit following feet",point2(3,0,250));
        Car.unit=Car.DISTANCE_KM;
        t.routeGuidance(msg(0,U16,i(1),10,U32,i(1000)));
        check("metric owner converts explicit following feet to metres",point2(0,0,76));
        System.exit(fail);
    }
}
