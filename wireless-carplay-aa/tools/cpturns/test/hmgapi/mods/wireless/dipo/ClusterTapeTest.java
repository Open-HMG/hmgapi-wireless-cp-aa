package hmgapi.mods.wireless.dipo;

import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;

/** Returned type50/+45 failure and synchronized stock numeric/tape fields. */
public final class ClusterTapeTest {
    static String lastTbt() {
        for (int k=c.calls.size()-1;k>=0;k--) if (c.calls.get(k).startsWith("tbt ")) return c.calls.get(k);
        return "";
    }
    public static void main(String[] args) {
        CpTurns t=ManeuverValidationTest.selected();
        t.maneuver(maneuver(10,50,0,0,45,"",1287));
        check("returned slight right45 shows schematic and keeps HUD66",lastTbt().startsWith("tbt 13 0 45.0 ")&&t.hudType==66);
        int previous=100, sample=0;
        int[] numbers={1700,1700,1500,1500,1400,1400,600,500,500,200,200,4,0};
        int[] bars={100,100,100,90,90,80,40,40,30,20,10,10,0};
        for(int metres:new int[]{500,499,451,450,401,400,169,151,150,51,50,1,0}) {
            c.calls.clear();t.routeGuidance(msg(0,U16,i(1),10,U32,i(metres),11,STR,s("0.2"),12,ENUM,i(1)));
            String[] fields=lastTbt().split(" ");int bar=Integer.parseInt(fields[8]);
            check("fresh raw numeric/tape pair at "+metres,fields[1].equals("13")&&fields[3].equals("45.0")
                    && Float.parseFloat(fields[4])==numbers[sample]&&fields[7].equals("3")&&bar==bars[sample]&&bar<=previous);
            previous=bar;sample++;
        }
        check("zero remainder empties tape",previous==0);
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),11,STR,s("250"),12,ENUM,i(4)));
        check("fresh display-only fallback keeps exact feet with synchronized tape",lastTbt().equals("tbt 13 0 45.0 250.0 0 0 3 20 0"));
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),13,U16,i(20)));
        check("promotion clears previous tape before new details",lastTbt().startsWith("tbt 0 "));
        t.maneuver(maneuver(20,49,0,0,-45,"",100));
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),10,U32,i(100)));
        check("new slight-left tape uses its own direction and distance",lastTbt().startsWith("tbt 13 0 315.0 ")&&t.hudType==71);
        c.calls.clear();t.routeGuidance(msg(0,U16,i(1),11,STR,s("NaN")));
        check("unknown distance hides tape via compact fallback",lastTbt().startsWith("tbt 71 0 0.0 0.0 -1 "));
        for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1f,0f})
            check("invalid/zero tape remainder "+invalid,CpTurns.clusterBar(invalid)==0);
        check("large finite distance clamps without overflow",CpTurns.clusterBar(Float.MAX_VALUE)==100);
        check("unsupported raw angle is not manufactured into a turn",CpTurns.clusterShape(50,0,0,-45,true,100,1)==0);
        System.exit(fail);
    }
}
