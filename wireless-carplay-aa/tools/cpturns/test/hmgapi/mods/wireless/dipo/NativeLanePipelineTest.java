package hmgapi.mods.wireless.dipo;

import java.nio.*;
import java.nio.file.*;
import com.tcc.iap2.Iap2NotiMsg;
import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;

/** Replay actual ARM converter output through CpTurns. Android Parcel storage and
 * vendor value objects are host stand-ins; native decoder/converter ran in QEMU.
 */
public final class NativeLanePipelineTest {
    static Iap2NotiMsg read(Path file)throws Exception {
        ByteBuffer b=ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        Iap2NotiMsg m=new Iap2NotiMsg();group(b,m,m.getItemGroup(),0);
        if(b.hasRemaining())throw new AssertionError("unconsumed Parcel bytes");return m;
    }
    static void group(ByteBuffer b,Iap2NotiMsg m,Iap2NotiMsg.Iap2ObjGroup g,int depth) {
        int count=b.getInt();if(count<0||count>128||depth>2)throw new AssertionError("group bound");
        for(int k=0;k<count;k++) {
            int id=b.getInt(),type=b.getInt(),n=b.getInt();
            if(n<0||n>32)throw new AssertionError("array bound");
            if(type==13) {
                Iap2NotiMsg.Iap2ObjGroup[] children=new Iap2NotiMsg.Iap2ObjGroup[n];
                for(int j=0;j<n;j++){children[j]=m.new Iap2ObjGroup();group(b,m,children[j],depth+1);}
                g.addFromParcel(id,type,children);
            } else if(type==1||type==5||type==6||type==8||type==9) {
                int[] values=new int[n];for(int j=0;j<n;j++)values[j]=b.getInt();g.addFromParcel(id,type,values);
            } else throw new AssertionError("unexpected Parcel type "+type);
        }
    }
    static CpTurns active() {
        CpTurns t=LaneVisibilityTest.fresh();LaneVisibilityTest.visibility(t,5,true);c.calls.clear();return t;
    }
    static byte[] apply(Path dir,String name)throws Exception {
        CpTurns t=active();t.lanes(read(dir.resolve(name+".parcel")));return c.lastLanes;
    }
    public static void main(String[] args)throws Exception {
        if(args.length==0)return; // Generated firmware-dependent replay is run explicitly.
        Path dir=Paths.get(args[0]);byte[] f=apply(dir,"three-lanes");
        int replayed=0;
        try(java.nio.file.DirectoryStream<Path> files=Files.newDirectoryStream(dir,"*.parcel")) {
            for(Path p:files) {
                Iap2NotiMsg m=read(p);CpTurns t=active();
                if(p.getFileName().toString().startsWith("route-"))t.routeGuidance(m);
                else if(p.getFileName().toString().startsWith("maneuver-"))t.maneuver(m);
                else t.lanes(m);
                replayed++;
            }
        }
        check("all native converter outputs parse and replay without errors",
                replayed==Integer.parseInt(new String(Files.readAllBytes(dir.resolve("event-count.txt")),"UTF-8").trim()));
        f=apply(dir,"three-lanes");
        check("native repeated lane groups preserve all three lanes",f[1]==3);
        check("native recommendation highlights only middle lane",f[3]==3&&f[4]==1&&f[5]==3);
        f=apply(dir,"three-lanes-no-highlight-fields-75");
        check("75-byte three-lane shape tolerates missing highlight fields",f[1]==3&&f[3]==3&&f[4]==1&&f[5]==3);
        f=apply(dir,"vehicle-three-highlight-only");
        check("vehicle three-lane pattern draws recommended straight arrows",f[1]==3&&f[3]==1&&f[4]==1&&f[5]==3&&f[19]==1&&f[22]==1&&f[25]==3);
        f=apply(dir,"vehicle-four-highlight-only");
        check("vehicle four-lane pattern recommends middle lanes and preserves outer directions",f[1]==4&&f[3]==3&&f[4]==1&&f[5]==1&&f[6]==3&&f[19]==7&&f[22]==1&&f[25]==1&&f[28]==3);
        f=apply(dir,"mixed-highlight-direction");
        check("native highlight angle appears before other lane branches",f[3]==1&&f[19]==1&&f[20]==7);
        f=apply(dir,"three-lanes");
        check("native signed/multiple angles map to correct HUD arrows",f[19]==1&&f[22]==7&&f[23]==1&&f[25]==3);
        f=apply(dir,"straight-highlight");check("native zero-angle recommendation retained",f[1]==1&&f[3]==1&&f[19]==1);
        f=apply(dir,"status-preferred");check("native preferred status survives converter",f[3]==1&&f[19]==3);
        f=apply(dir,"signed-angles");check("native signed angles preserve highlighted direction first",f[19]==7&&f[20]==5&&f[21]==1);
        f=apply(dir,"unsigned-angle-representation");check("native 270 degrees maps left",f[19]==7);
        f=apply(dir,"eight-lanes");check("all eight lanes survive native and Java stages",f[1]==8&&f[3]==3&&f[6]==1&&f[10]==3);
        f=apply(dir,"repeated-angle-fields");check("repeated angle TLVs are aggregated",f[19]==7&&f[20]==1&&f[3]==1);
        f=apply(dir,"unknown-fields");check("unknown fields do not change lanes",f[1]==1&&f[19]==1);
        f=apply(dir,"reordered");check("lane before component TLV remains correctly routed",f[1]==1&&f[19]==1);
        CpTurns t=active();t.lanes(read(dir.resolve("three-lanes.parcel")));t.lanes(read(dir.resolve("empty-lanes.parcel")));
        check("native empty guidance clears previous arrows",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        t=active();t.lanes(read(dir.resolve("three-lanes.parcel")));LaneVisibilityTest.visibility(t,5,false);
        check("phone hiding guidance still clears decoded lanes",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        t=active();t.lanes(read(dir.resolve("two-components.parcel")));LaneVisibilityTest.visibility(t,7,true);
        check("native component array preserves isolated pending guidance",c.lastLanes[1]==1);
        t=active();t.lanes(read(dir.resolve("three-lanes.parcel")));
        t.routeGuidance(read(dir.resolve("route-after-lanes.parcel")));t.maneuver(read(dir.resolve("maneuver-after-lanes.parcel")));
        check("real native route/maneuver messages still reach turn and HUD outputs",c.calls.toString().contains("tbt 67")&&c.calls.toString().contains("hud f20143"));
        t.stop();check("stop clears fully decoded native lane output",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        t=LaneVisibilityTest.fresh();t.routeGuidance(read(dir.resolve("route-hidden-20.parcel")));
        for(int k=20;k<=22;k++)t.lanes(read(dir.resolve("prefetch-"+k+".parcel")));
        check("native prefetch window stays hidden before visibility",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        t.routeGuidance(read(dir.resolve("route-show-20.parcel")));
        check("native selected event displays left with recommendation",c.lastLanes[19]==7&&c.lastLanes[3]==1);
        t.routeGuidance(read(dir.resolve("route-show-21.parcel")));
        check("native next lane event advances independently of maneuvers",c.lastLanes[19]==3&&c.lastLanes[3]==1);
        t.routeGuidance(read(dir.resolve("route-show-22.parcel")));
        check("native window retains last prefetched straight event",c.lastLanes[19]==1&&c.lastLanes[3]==1);
        t.routeGuidance(read(dir.resolve("route-missing-23.parcel")));
        check("native missing lane event clears rather than reusing previous",!LaneVisibilityTest.nonblankFrame(c.lastLanes));
        System.exit(fail);
    }
}
