package hmgapi.mods.wireless.dipo;

import android.os.SystemClock;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;

public final class ApproachReadabilityTest {
    static float m(float feet) { return feet / 3.28084f; }
    public static void main(String[] args) {
        for(int[] row:new int[][]{{1201,1300},{501,600},{500,500},{451,500},{450,450},
                {51,100},{50,50},{49,50},{40,40},{11,20},{10,10},{9,10},{5,5},{4,4},{1,1},{0,0}})
            check("readable proximity band "+row[0],CpTurns.roundedApproach(m(row[0]),Cluster.UNIT_FT,Float.NaN)==row[1]);
        check("high closing rate uses coarse feet above50",CpTurns.roundedApproach(m(149),Cluster.UNIT_FT,80)==200);
        check("fine near-turn precision overrides fast band",CpTurns.roundedApproach(m(39),Cluster.UNIT_FT,80)==40);
        check("positive sub-foot remainder never rounds to zero",CpTurns.roundedApproach(m(0.1f),Cluster.UNIT_FT,0)==1);
        check("metric proximity uses vehicle metres",CpTurns.roundedApproach(123,Cluster.UNIT_M,Float.NaN)==130
                &&CpTurns.roundedApproach(9,Cluster.UNIT_M,Float.NaN)==9);
        CpTurns t=ManeuverValidationTest.selected();Car.unit=Car.DISTANCE_MILES;
        c.calls.clear();t.maneuver(maneuver(10,50,0,0,45,"",100));
        int lastDisplay=-1,lastTbt=-1;
        for(int k=0;k<c.calls.size();k++) {
            if(c.calls.get(k).startsWith("hud ")||c.calls.get(k).startsWith("2c "))lastDisplay=k;
            if(c.calls.get(k).startsWith("tbt 13 "))lastTbt=k;
        }
        check("schematic numeric/bar packet follows HUD refreshes",lastTbt>lastDisplay&&lastDisplay>=0);
        t.clearApproach();SystemClock.now=10000;t.approachDistance(1,10,m(149),Cluster.UNIT_FT,true);
        t.approachKmh=160;SystemClock.now+=1000;
        check("fast-band transition cannot increase shrinking countdown",t.approachDistance(1,10,m(148),Cluster.UNIT_FT,true)==150);
        SystemClock.now+=1000;
        check("promotion resets prior number and estimated rate",t.approachDistance(1,20,m(401),Cluster.UNIT_FT,true)==450&&Float.isNaN(t.approachKmh));
        t.clearApproach();SystemClock.now+=1000;t.approachDistance(1,20,200,Cluster.UNIT_FT,true);SystemClock.now+=1000;
        t.approachDistance(1,20,180,Cluster.UNIT_FT,true);
        check("fresh raw countdowns estimate closing rate in kmh",Math.abs(t.approachKmh-72)<0.01f);
        SystemClock.now+=1000;t.approachDistance(1,20,170,Cluster.UNIT_FT,false);
        check("formatted input never pretends to measure speed",Float.isNaN(t.approachKmh));
        t.clearApproach();check("lifecycle clears numeric and rate state",t.approachCurrent==-1&&Float.isNaN(t.approachRounded));
        t=ManeuverValidationTest.selected();t.maneuver(maneuver(10,29,0,1,-45,"",100));
        check("native315 degree reference maps HUD glyph107",t.hudType==107&&ManeuverValidationTest.has("tbt 32 0 315.0 "));
        System.exit(fail);
    }
}
