package hmgapi.mods.wireless.dipo;

import java.io.*;
import java.util.*;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import hmgapi.mods.wireless.SimulatorHandoff;

/** One JSON request on stdin, one versioned replay result on stdout. */
public final class SimulatorRunner {
    private static int number(Map<String,Object> e,String key,int fallback) { return e.containsKey(key) ? ((Number)e.get(key)).intValue() : fallback; }
    private static void ints(Iap2NotiMsg m,int id,int type,int... values) { m.getItemGroup().add(id,type,values); }
    private static void field(Iap2NotiMsg m,Map<String,Object> e,String key,int id,int type) {
        if (!e.containsKey(key)) return;
        Object v=e.get(key);
        if (type==10) m.getItemGroup().add(id,type,new String[]{(String)v});
        else if (type==7) m.getItemGroup().add(id,type,new long[]{((Number)v).longValue()});
        else if (v instanceof Boolean) ints(m,id,type,(Boolean)v ? 1:0);
        else ints(m,id,type,((Number)v).intValue());
    }
    private static int[] array(List<?> a) {
        int[] out=new int[a.size()];for(int k=0;k<out.length;k++)out[k]=((Number)a.get(k)).intValue();return out;
    }
    @SuppressWarnings("unchecked")
    private static Iap2NotiMsg message(Map<String,Object> e,String kind) {
        Iap2NotiMsg m=new Iap2NotiMsg();ints(m,0,5,number(e,"component",1));
        if (kind.equals("route")) {
            field(m,e,"state",1,8);field(m,e,"destination",4,10);field(m,e,"etaEpoch",5,7);
            field(m,e,"remainingMetres",7,6);field(m,e,"remainingText",8,10);field(m,e,"remainingUnit",9,8);
            field(m,e,"distanceMetres",10,6);field(m,e,"distanceText",11,10);field(m,e,"distanceUnit",12,8);
            if(e.containsKey("selected"))ints(m,13,5,array((List<?>)e.get("selected")));
            field(m,e,"visible",15,9);field(m,e,"laneIndex",16,5);field(m,e,"lanesShown",18,9);field(m,e,"source",19,10);
        } else if(kind.equals("maneuver")) {
            field(m,e,"index",1,5);field(m,e,"type",3,8);field(m,e,"road",4,10);field(m,e,"distanceMetres",5,6);
            field(m,e,"distanceText",6,10);field(m,e,"distanceUnit",7,8);field(m,e,"side",8,8);
            field(m,e,"junction",9,8);field(m,e,"exit",11,1);
        } else {
            field(m,e,"index",1,5);
            List<?> lanes=(List<?>)e.get("lanes");
            Iap2NotiMsg.Iap2ObjGroup[] groups=new Iap2NotiMsg.Iap2ObjGroup[lanes.size()];
            for(int k=0;k<groups.length;k++) {
                Map<String,Object> lane=(Map<String,Object>)lanes.get(k);groups[k]=m.new Iap2ObjGroup();
                if(lane.containsKey("status"))groups[k].add(1,5,new int[]{number(lane,"status",0)});
                if(lane.containsKey("angles"))groups[k].add(2,5,array((List<?>)lane.get("angles")));
                if(lane.containsKey("preferred"))groups[k].add(3,5,array((List<?>)lane.get("preferred")));
            }
            m.getItemGroup().add(2,13,groups);
        }
        return m;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ByteArrayOutputStream input=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=System.in.read(buffer))!=-1) { input.write(buffer,0,n);if(input.size()>1048576)throw new IllegalArgumentException("Request too large"); }
        Map<String,Object> scenario=(Map<String,Object>)Json.parse(new String(input.toByteArray(),"UTF-8"));
        if(number(scenario,"schemaVersion",0)!=1)throw new IllegalArgumentException("Unsupported schemaVersion");
        Car.unit="km".equals(scenario.get("units")) ? Car.DISTANCE_KM:Car.DISTANCE_MILES;
        Cluster cluster=new Cluster();Cluster.instance=cluster;
        final SimulatorHandoff handoff=new SimulatorHandoff();
        CpTurns turns=new CpTurns(new android.content.Context("com.daudio.app.dipo"),cluster,null,new CpTurns.RouteStateSink(){
            public void active(boolean active){handoff.active(active);}
        });
        List<Object> steps=new ArrayList<Object>();long last=-1;
        for(Object item:(List<?>)scenario.get("events")) {
            Map<String,Object> e=(Map<String,Object>)item;long at=((Number)e.get("at")).longValue();
            if(at<last)throw new IllegalArgumentException("Events out of order");last=at;android.os.SystemClock.now=at;
            cluster.calls.clear();Dipo.trace.clear();String kind=(String)e.get("kind");
            if(kind.equals("route"))turns.routeGuidance(message(e,kind));
            else if(kind.equals("maneuver"))turns.maneuver(message(e,kind));
            else if(kind.equals("lanes"))turns.lanes(message(e,kind));
            else if(kind.equals("stop"))turns.stop();
            else if(kind.equals("disconnect"))turns.disconnected();
            else if(kind.equals("compass"))handoff.compass(((Number)e.get("heading")).floatValue());
            else if(!kind.equals("tick"))throw new IllegalArgumentException("Unknown event kind");
            Map<String,Object> state=Json.object("routeActive",turns.routeActive,"component",turns.hudComponent,
                "current",turns.hudCurrent,"following",turns.hudFollowing);
            steps.add(Json.object("at",at,"input",e,"calls",new ArrayList<Object>(cluster.calls),
                "diagnostics",new ArrayList<String>(Dipo.trace),"bridge",state));
        }
        System.out.println(Json.write(Json.object("schemaVersion",1,"backend","java-host","steps",steps)));
    }
}
