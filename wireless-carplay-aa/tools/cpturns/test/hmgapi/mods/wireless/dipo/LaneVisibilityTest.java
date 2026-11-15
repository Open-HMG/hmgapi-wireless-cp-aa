package hmgapi.mods.wireless.dipo;

import static hmgapi.mods.wireless.dipo.CpTurnsTest.*;
import android.os.SystemClock;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Cluster;

/** Phone lane visibility owns navigation lane output, including partial messages. */
public final class LaneVisibilityTest {
    static CpTurns fresh() {
        c = new Cluster(); Dipo.trace.clear(); SystemClock.now = 10000;
        return new CpTurns(new android.content.Context(), c, null);
    }
    static void visibility(CpTurns t, int id, boolean shown) {
        t.routeGuidance(msg(0,U16,i(id),1,ENUM,i(1),16,U16,i(0),18,9,i(shown ? 1 : 0)));
    }
    static Iap2NotiMsg lanes(int id) {
        Iap2NotiMsg m = new Iap2NotiMsg();
        Iap2NotiMsg.Iap2ObjGroup lane = m.new Iap2ObjGroup();
        lane.add(1,U16,i(2)).add(2,U16,i(90)).add(3,U16,i(90));
        m.getItemGroup().add(0,U16,i(id)).add(1,U16,i(0)).add(2,GROUPS,new Iap2NotiMsg.Iap2ObjGroup[]{lane});
        return m;
    }
    static boolean nonblank() {
        for(String s:c.calls) if(s.startsWith("lanes ") && !s.equals("lanes f1")) return true;
        return false;
    }
    public static void main(String[] args) {
        CpTurns t=fresh();
        visibility(t,5,false);
        expect("phone not showing lanes: clear even without maneuvers", "lanes f1");
        t.lanes(lanes(5));
        check("late lane payload cannot restore arrows while phone says false",!nonblank());c.calls.clear();
        visibility(t,5,true);
        check("false-to-true selects prefetched data for the same event",nonblank());c.calls.clear();
        t.lanes(lanes(5));
        check("true plus unchanged lane data preserves recommendation mapping",c.lastLanes[1]==1&&c.lastLanes[3]==1);c.calls.clear();
        t.routeGuidance(msg(0,U16,i(5),11,STR,s("0.2")));
        check("partial route update preserves established lane visibility",c.calls.isEmpty());
        visibility(t,5,false);
        expect("true-to-false explicitly clears old arrows", "lanes f1");
        SystemClock.now+=1999;visibility(t,5,false);
        check("hidden-lane updates are coalesced within two seconds",c.calls.isEmpty());
        SystemClock.now++;visibility(t,5,false);
        expect("incoming hidden-lane update refreshes clear after two seconds", "lanes f1");

        t=fresh();t.lanes(lanes(5));
        check("lane payload before route metadata is not displayed",!nonblank());c.calls.clear();
        visibility(t,5,true);
        check("lane payload preceding true flag can render when visibility arrives",nonblank());c.calls.clear();
        t.lanes(lanes(7));
        check("different component cannot overwrite active lane display",c.calls.isEmpty());
        visibility(t,5,false);c.calls.clear();visibility(t,7,true);
        check("pending lane data stays isolated by component",nonblank());c.calls.clear();
        t.stop();c.calls.clear();visibility(t,7,true);
        check("stop invalidates cached lanes",!nonblank());c.calls.clear();
        t.lanes(lanes(7));c.calls.clear();t.disconnected();c.calls.clear();
        visibility(t,7,true);
        check("disconnect invalidates cached lanes",!nonblank());c.calls.clear();

        t=fresh();t.routeGuidance(msg(0,U16,i(5),1,ENUM,i(1)));
        expect("unknown visibility clears stale output conservatively", "lanes f1");
        t.maneuver(msg(0,U16,i(5),1,U16,i(0),3,ENUM,i(2),11,U8,i(90)));
        SystemClock.now+=2000;
        t.routeGuidance(msg(0,U16,i(5),13,U16,i(0),18,9,i(0),11,STR,s("0.2")));
        check("lane clear follows normal turn output without suppressing turn",
              c.calls.toString().contains("tbt 67") && c.calls.get(c.calls.size()-1).equals("lanes f1"));

        t=fresh();visibility(t,5,true);c.calls.clear();
        Iap2NotiMsg m=new Iap2NotiMsg();
        Iap2NotiMsg.Iap2ObjGroup left=m.new Iap2ObjGroup(),middle=m.new Iap2ObjGroup(),right=m.new Iap2ObjGroup();
        left.add(1,U16,i(1)).add(2,U16,i(0));
        middle.add(1,U16,i(1)).add(2,U16,i(-90,0)).add(3,U16,i(-90));
        right.add(1,U16,i(1)).add(2,U16,i(90));
        m.getItemGroup().add(0,U16,i(5)).add(1,U16,i(0)).add(2,GROUPS,new Iap2NotiMsg.Iap2ObjGroup[]{left,middle,right});
        t.lanes(m);
        byte[] frame=c.lastLanes;
        check("lane states match stock recommended 1 and other 3",
              frame.length==81 && frame[1]==3 && frame[3]==3 && frame[4]==1 && frame[5]==3);
        check("multi-arrow lane retains directions without highlighting adjacent lanes",
              frame[19]==1 && frame[22]==7 && frame[23]==1 && frame[25]==3);
        Iap2NotiMsg.Iap2ObjGroup straight=m.new Iap2ObjGroup();
        straight.add(2,U16,i(0)).add(3,U16,i(0));
        check("zero-degree highlighted angle correctly recommends straight",CpTurns.laneOne(straight)[0]==1);
        Iap2NotiMsg.Iap2ObjGroup empty=m.new Iap2ObjGroup();
        empty.add(2,U16,i(0)).add(3,U16,i());
        check("empty highlight list does not recommend a lane",CpTurns.laneOne(empty)[0]==3);
        int[] normalOutput=CpTurns.laneOne(left);
        check("non-recommended lane uses stock state 3 with geometry retained",normalOutput[0]==3&&normalOutput[1]==1);
        Iap2NotiMsg.Iap2ObjGroup inactive=m.new Iap2ObjGroup();
        inactive.add(1,U16,i(0)).add(2,U16,i(90));
        int[] inactiveOutput=CpTurns.laneOne(inactive);
        check("nonpreferred input keeps a stock other-lane right arrow",inactiveOutput[0]==3&&inactiveOutput[1]==3);
        Iap2NotiMsg.Iap2ObjGroup statusOnly=m.new Iap2ObjGroup();
        statusOnly.add(1,U16,i(2)).add(2,U16,i(90));
        check("explicit preferred-lane status is retained without angle highlights",CpTurns.laneOne(statusOnly)[0]==1);
        Iap2NotiMsg.Iap2ObjGroup highlightOnly=m.new Iap2ObjGroup();
        highlightOnly.add(1,ENUM,i(2)).add(3,U16,i(0));
        int[] highlightOutput=CpTurns.laneOne(highlightOnly);
        check("vehicle highlight-only straight lane has a visible recommended arrow",highlightOutput[0]==1&&highlightOutput[1]==1);
        Iap2NotiMsg.Iap2ObjGroup mixed=m.new Iap2ObjGroup();
        mixed.add(1,ENUM,i(2)).add(2,U16,i(-90)).add(3,U16,i(0));
        int[] mixedOutput=CpTurns.laneOne(mixed);
        check("highlight direction is drawn before non-highlight branches",mixedOutput[0]==1&&mixedOutput[1]==1&&mixedOutput[2]==7);
        Iap2NotiMsg.Iap2ObjGroup duplicate=m.new Iap2ObjGroup();
        duplicate.add(2,U16,i(0,0,45,50)).add(3,U16,i(0));
        int[] duplicateOutput=CpTurns.laneOne(duplicate);
        check("duplicate angle icons do not exhaust arrow slots",duplicateOutput[1]==1&&duplicateOutput[2]==2&&duplicateOutput[3]==0);
        Iap2NotiMsg stock=new Iap2NotiMsg();
        Iap2NotiMsg.Iap2ObjGroup[] four=new Iap2NotiMsg.Iap2ObjGroup[4];
        int[] directions={-90,0,0,90};
        for(int k=0;k<4;k++) {
            four[k]=stock.new Iap2ObjGroup();
            four[k].add(1,ENUM,i(k==1||k==2?2:0)).add(2,U16,i(directions[k]));
            if(k==1||k==2)four[k].add(3,U16,i(0));
        }
        stock.getItemGroup().add(0,U16,i(5)).add(1,U16,i(0)).add(2,GROUPS,four);
        t.lanes(stock);frame=c.lastLanes;
        check("November 9 stock four-lane state pattern is reproduced",frame[1]==4&&frame[3]==3&&frame[4]==1&&frame[5]==1&&frame[6]==3);
        check("stock color mapping preserves left straight straight right geometry",frame[19]==7&&frame[22]==1&&frame[25]==1&&frame[28]==3);
        Iap2NotiMsg clear=new Iap2NotiMsg();
        clear.getItemGroup().add(0,U16,i(5)).add(1,U16,i(0)).add(2,GROUPS,new Iap2NotiMsg.Iap2ObjGroup[0]);
        t.lanes(clear);
        check("empty lane payload clears recommendation and arrows",!nonblankFrame(c.lastLanes));
        System.exit(fail);
    }
    static boolean nonblankFrame(byte[] frame) {
        for(int k=1;k<frame.length;k++) if(frame[k]!=0) return true;
        return false;
    }
}
