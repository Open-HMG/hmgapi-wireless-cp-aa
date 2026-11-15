package hmgapi.hook;

import hmgapi.mods.wireless.dipo.Json;
import java.util.*;

/** Simulator output boundary. Captures complete arguments without trimming bytes. */
public final class Cluster {
    public static final int UNIT_M=0, UNIT_KM=1, UNIT_MI=2, UNIT_FT=3, UNIT_YD=4;
    public static Cluster instance;
    public final List<Object> calls = new ArrayList<Object>();
    public static Cluster of(android.content.Context c) { return instance; }
    public void record(String method, Object... args) { calls.add(Json.object("method",method,"args",Arrays.asList(args))); }
    public void tbtInfo(int a,int b,float c,float d,int e,int f,int g,int h,int i) { record("tbtInfo",a,b,c,d,e,f,g,h,i); }
    public void tbtSubInfo(float a,int b,int c,int d,int e,int f,int g,float h,int i,int j) { record("tbtSubInfo",a,b,c,d,e,f,g,h,i,j); }
    public void tbtSubInfo2C(int a,int b,int c,int d,int e,int f,int g,int h,int i) { record("tbtSubInfo2C",a,b,c,d,e,f,g,h,i); }
    private void frame(String method, byte[] bytes) {
        List<Integer> raw = new ArrayList<Integer>();
        for (byte b : bytes) raw.add(b & 255);
        record(method, raw);
    }
    public void turnListNew(byte[] f) { frame("turnListNew",f); }
    public void laneInfo(byte[] f) { frame("laneInfo",f); }
    public void destinationName(int type,String name) { record("destinationName",type,name); }
    public void crossroadName(String name) { record("crossroadName",name); }
    public static final class Turn {
        public int type,direction,traffic,no,unit;
        public String name="",exitNumber="";
        public float distance;
    }
    public void turnList(Turn[] turns) {
        List<Object> list = new ArrayList<Object>();
        for (Turn t : turns) list.add(Json.object("type",t.type,"direction",t.direction,"traffic",t.traffic,
                "no",t.no,"unit",t.unit,"name",t.name,"exitNumber",t.exitNumber,"distance",t.distance));
        record("turnList",list);
    }
}
