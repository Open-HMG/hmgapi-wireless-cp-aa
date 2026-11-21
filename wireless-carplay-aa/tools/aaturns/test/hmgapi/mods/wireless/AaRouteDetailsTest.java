package hmgapi.mods.wireless;

import android.os.Handler;

import java.io.ByteArrayOutputStream;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/**
 * Host test of tbt_route_details: AaTurns with the full message (the dump) next to the
 * library's turn events, in the order the library calls back (dump, then turn; emulated on
 * the US 260507 library), and the legacy event and side the library makes for each type.
 */
public final class AaRouteDetailsTest {
    static int fail;
    static Cluster c;

    static void check(String what, boolean ok, String got) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what + (ok ? "" : ": got " + got));
        if (!ok) fail = 1;
    }

    /** the first "tbt" call's type and direction, "type dir" */
    static String tbt() {
        for (String s : c.calls) {
            if (s.startsWith("tbt ")) {
                String[] p = s.split(" ");
                return p[1] + " " + p[3];
            }
        }
        return "none";
    }

    static String dest() {
        for (String s : c.calls) if (s.startsWith("dest ")) return s;
        return "none";
    }

    // a tiny protobuf writer
    static final class W {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();

        W v(int f, long x) {
            raw(f << 3);
            raw(x);
            return this;
        }

        W s(int f, String x) {
            return b(f, x.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        W m(int f, W x) {
            return b(f, x.o.toByteArray());
        }

        W b(int f, byte[] x) {
            raw((f << 3) | 2);
            raw(x.length);
            o.write(x, 0, x.length);
            return this;
        }

        void raw(long x) {
            while ((x & ~0x7FL) != 0) {
                o.write((int) ((x & 0x7F) | 0x80));
                x >>>= 7;
            }
            o.write((int) x);
        }

        String dump() {
            byte[] b = o.toByteArray();
            StringBuilder sb = new StringBuilder("S " + b.length + " ");
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        }
    }

    static W w() {
        return new W();
    }

    /** a 0x8006 dump: one step of this type (and roundabout angle), one destination, maybe a charging stop */
    static String state(int type, int angle, String dest, int chargeS) {
        W man = w().v(1, type);
        if (angle >= 0) man.v(3, angle);
        W d = w().s(1, dest);
        if (chargeS >= 0) d.m(2, w().v(1, 18000).v(2, 350000).v(3, chargeS));
        return w().m(1, w().m(1, man).m(2, w().s(1, "Road"))).m(2, d).dump();
    }

    static AaTurns fresh(boolean details, boolean standard) {
        c = new Cluster();
        AaTurns t = new AaTurns(new android.content.Context(), c, new Handler());
        t.routeDetails = details;
        t.standardTurns = standard;
        t.status(1);
        c.calls.clear();
        return t;
    }

    /** the library's callbacks for one 0x8006: the dump, then the turn (its event, side, angle) */
    static void turn(AaTurns t, int type, int angle, int ev, int side, int libAngle) {
        t.nextTurn(state(type, angle, "Home", -1), 0, AaTurns.EV_DUMP, -1, -1);
        c.calls.clear();
        t.nextTurn("Road", side, ev, libAngle, 0);
    }

    public static void main(String[] a) {
        Car.unit = Car.DISTANCE_MILES;
        final int L = AaTurns.LEFT, R = AaTurns.RIGHT, U = AaTurns.UNSPECIFIED;

        // keep right: the library sends SLIGHT_TURN + RIGHT
        AaTurns t = fresh(false, false);
        turn(t, AaNavState.M_KEEP_RIGHT, -1, AaTurns.SLIGHT_TURN, R, -1);
        check("keep right, setting off: slight right (66 / 30)", tbt().equals("66 30.0"), tbt());
        t = fresh(true, false);
        turn(t, AaNavState.M_KEEP_RIGHT, -1, AaTurns.SLIGHT_TURN, R, -1);
        check("keep right, setting on: the fork icon (173)", tbt().equals("173 0.0"), tbt());
        check("keep right: exactType kept", t.exactType == AaNavState.M_KEEP_RIGHT, "" + t.exactType);
        t.nextTurnDistance(300, 20, 1000, AaTurns.FEET);
        check("keep right: still the fork after a distance update", tbt().equals("173 0.0"), tbt());
        t = fresh(true, true);
        turn(t, AaNavState.M_KEEP_LEFT, -1, AaTurns.SLIGHT_TURN, L, -1);
        c.calls.clear();
        t.nextTurnDistance(200, 15, 656000, AaTurns.FEET);
        check("keep left near it, standard turns: the car's FORK left (25 / 270)", tbt().equals("25 270.0"), tbt());

        // a slight turn stays a slight turn
        t = fresh(true, false);
        turn(t, AaNavState.M_TURN_SLIGHT_RIGHT, -1, AaTurns.SLIGHT_TURN, R, -1);
        check("slight right: unchanged (66)", tbt().equals("66 30.0"), tbt());

        // a stale dump (another maneuver) is not used
        t = fresh(true, false);
        turn(t, AaNavState.M_TURN_NORMAL_LEFT, -1, AaTurns.SLIGHT_TURN, R, -1);
        check("dump of another maneuver: not used", t.exactType == -1 && tbt().equals("66 30.0"), tbt() + " exact " + t.exactType);

        // roundabouts: the library sends ENTER_AND_EXIT, side RIGHT for clockwise, LEFT for counterclockwise
        t = fresh(true, false);
        turn(t, AaNavState.M_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, 90, AaTurns.ROUNDABOUT_ENTER_AND_EXIT, L, 90);
        check("counterclockwise roundabout, exit at 90: 99 (as before)", tbt().equals("99 90.0"), tbt());
        t = fresh(false, false);
        turn(t, AaNavState.M_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 90, AaTurns.ROUNDABOUT_ENTER_AND_EXIT, R, 90);
        check("clockwise roundabout, setting off: 99 (the counterclockwise set)", tbt().equals("99 90.0"), tbt());
        t = fresh(true, false);
        turn(t, AaNavState.M_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 90, AaTurns.ROUNDABOUT_ENTER_AND_EXIT, R, 90);
        check("clockwise roundabout, setting on: 195 (the clockwise set)", tbt().equals("195 90.0"), tbt());
        // once in it, Android Auto sends a plain ROUNDABOUT_EXIT
        t.nextTurn(state(AaNavState.M_ROUNDABOUT_EXIT, -1, "Home", -1), 0, AaTurns.EV_DUMP, -1, -1);
        c.calls.clear();
        t.nextTurn("Road", U, AaTurns.ROUNDABOUT_EXIT, -1, 0);
        check("its plain ROUNDABOUT_EXIT: still clockwise (195)", tbt().equals("195 90.0"), tbt());
        t = fresh(true, true);
        turn(t, AaNavState.M_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 90, AaTurns.ROUNDABOUT_ENTER_AND_EXIT, R, 90);
        c.calls.clear();
        t.nextTurnDistance(100, 10, 328000, AaTurns.FEET);
        check("clockwise near it, standard turns: the car's clockwise roundabout (33)", tbt().startsWith("33 "), tbt());

        // on-ramps near them (standard turns): the ramp's angle
        t = fresh(true, true);
        turn(t, AaNavState.M_ON_RAMP_SHARP_RIGHT, -1, AaTurns.ON_RAMP, R, -1);
        c.calls.clear();
        t.nextTurnDistance(100, 10, 328000, AaTurns.FEET);
        check("sharp right on-ramp near it: TURN at 135", tbt().equals("13 135.0"), tbt());
        t = fresh(false, true);
        turn(t, AaNavState.M_ON_RAMP_SHARP_RIGHT, -1, AaTurns.ON_RAMP, R, -1);
        c.calls.clear();
        t.nextTurnDistance(100, 10, 328000, AaTurns.FEET);
        check("sharp right on-ramp, setting off: TURN at 45 (as before)", tbt().equals("13 45.0"), tbt());

        // the next charging stop's planned minutes after its name
        t = fresh(true, false);
        t.nextTurn(state(AaNavState.M_TURN_NORMAL_RIGHT, -1, "Charger", 1432), 0, AaTurns.EV_DUMP, -1, -1);
        t.nextTurn("Electrify America", 0, AaTurns.EV_DEST_NAME, -1, 1);
        check("charging stop: name - 24 min charge", dest().equals("dest 0 [Electrify America - 24 min charge]"), dest());
        c.calls.clear();
        t.nextTurn(state(AaNavState.M_TURN_NORMAL_RIGHT, -1, "Charger", 1500), 0, AaTurns.EV_DUMP, -1, -1);
        check("planned minutes change: name sent again", dest().equals("dest 0 [Electrify America - 25 min charge]"), dest());
        c.calls.clear();
        t.nextTurn(state(AaNavState.M_TURN_NORMAL_RIGHT, -1, "Charger", 1500), 0, AaTurns.EV_DUMP, -1, -1);
        check("same minutes: not sent again", dest().equals("none"), dest());
        t = fresh(false, false);
        t.nextTurn(state(AaNavState.M_TURN_NORMAL_RIGHT, -1, "Charger", 1432), 0, AaTurns.EV_DUMP, -1, -1);
        t.nextTurn("Electrify America", 0, AaTurns.EV_DEST_NAME, -1, 1);
        check("setting off: the name alone", dest().equals("dest 0 [Electrify America]"), dest());
        t = fresh(true, false);
        t.nextTurn(state(AaNavState.M_TURN_NORMAL_RIGHT, -1, "Home", -1), 0, AaTurns.EV_DUMP, -1, -1);
        t.nextTurn("Home", 0, AaTurns.EV_DEST_NAME, -1, 1);
        check("no charging stop: the name alone", dest().equals("dest 0 [Home]"), dest());

        // the distance between the phone's updates
        AaTurns.testNow = 10000;
        t = fresh(true, false);
        turn(t, AaNavState.M_TURN_NORMAL_RIGHT, -1, AaTurns.TURN, R, -1);
        c.calls.clear();
        t.nextTurnDistance(300, 15, 984000, AaTurns.FEET);   // 984 ft, 15 s: 20 m/s
        check("an update: 980 ft (as sent, in 10s after a tick)", tbt().equals("67 90.0"), tbt());
        c.calls.clear();
        AaTurns.testNow = 10500;
        t.smoothTick();   // 0.5 s later: 290 m = 951 ft -> 950
        String g = c.calls.isEmpty() ? "none" : c.calls.get(0);
        check("0.5 s later: 950 ft", g.startsWith("tbt 67 65535 90.0 950.0"), g);
        c.calls.clear();
        AaTurns.testNow = 13000;
        t.smoothTick();
        check("no update for 3 s: holds (nothing sent)", c.calls.isEmpty(), String.valueOf(c.calls));
        t = fresh(true, false);
        turn(t, AaNavState.M_TURN_NORMAL_RIGHT, -1, AaTurns.TURN, R, -1);
        t.nextTurnDistance(3000, 120, 1900, AaTurns.MILES_P1);
        check("farther than 914 m: not smoothed", t.smMeters == -1, "" + t.smMeters);
        t.nextTurnDistance(300, 0, 984000, AaTurns.FEET);
        check("no time to the turn: not smoothed", t.smMeters == -1, "" + t.smMeters);
        t = fresh(false, false);
        turn(t, AaNavState.M_TURN_NORMAL_RIGHT, -1, AaTurns.TURN, R, -1);
        t.nextTurnDistance(300, 15, 984000, AaTurns.FEET);
        check("setting off: not smoothed", t.smMeters == -1, "" + t.smMeters);
        AaTurns.testNow = -1;

        System.out.println(fail == 0 ? "all passed" : "FAILED");
        if (fail != 0) System.exit(1);
    }
}
