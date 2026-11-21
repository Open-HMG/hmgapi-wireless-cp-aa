package hmgapi.mods.wireless;

import android.content.Context;
import android.os.Handler;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.Calendar;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/**
 * Android Auto turn-by-turn on the cluster and HUD, for one Android Auto connection.
 * GPM has its own (GpmNaviTBTManager), but makes it only when stepofspec is 999 or
 * more, and that one also claims the cluster (alive + status), which these cars never
 * give back. This is the turn-by-turn build's manager in Java, fed by the AA library's
 * callbacks (gpm.navigation_status.*) and sending through HMG-API's Cluster API: turn,
 * distance, HUD turn list and distance, lanes, the next turns, remaining distance and
 * arrival time, destination name. It never sends alive or operation status.
 *
 * The AA library (the Wireless mod's library delta) packs more than stock into the
 * callbacks: next-turn events 32 (destination name; number = destination point),
 * 33 (debug dump), 34 (lanes, hex string), 64..66 (turns ahead: road, side, angle,
 * number = event | exit << 8), 67 (end of the turns ahead, number = how many); and
 * distance flags 0x200 (a turn ahead, index in bits 10-11) and 0x100 (remaining trip:
 * meters, seconds, unit, 0x800 + point << 12).
 *
 * Everything runs on one thread (the handler's), in the order the callbacks came.
 */
final class AaTurns {
    // AA protocol numbers (Protos.NavigationNextTurnEvent)
    static final int UNKNOWN = 0, DEPART = 1, NAME_CHANGE = 2, SLIGHT_TURN = 3, TURN = 4, SHARP_TURN = 5, U_TURN = 6,
            ON_RAMP = 7, OFF_RAMP = 8, FORK = 9, MERGE = 10, ROUNDABOUT_ENTER = 11, ROUNDABOUT_EXIT = 12,
            ROUNDABOUT_ENTER_AND_EXIT = 13, STRAIGHT = 14, FERRY_BOAT = 16, FERRY_TRAIN = 17, DESTINATION = 19;
    static final int LEFT = 1, RIGHT = 2, UNSPECIFIED = 3;
    static final int METERS = 1, KILOMETERS = 2, KILOMETERS_P1 = 3, MILES = 4, MILES_P1 = 5, FEET = 6, YARDS = 7;
    // the library's extra events and flags
    static final int EV_DEST_NAME = 32, EV_DUMP = 33, EV_LANES = 34, EV_AHEAD = 64, EV_AHEAD_END = 67;
    static final int FLAG_TRIP = 0x100, FLAG_AHEAD = 0x200, FLAG_POINT = 0x800;
    static final byte HUD_LIST = (byte) 0xF2, LANES = (byte) 0xF1;
    /** the car navigation's main-display types (Navigation.DisplayType) */
    static final int TYPE_TURN = 13, TYPE_EXIT_RIGHT = 17, TYPE_EXIT_LEFT = 18, TYPE_FORK = 25, TYPE_U_TURN = 31,
            TYPE_ROUNDABOUT_RIGHT = 32, TYPE_ROUNDABOUT_LEFT = 33;
    /** its destination icons: ahead, on the right, on the left */
    static final int DEST_AHEAD = 112, DEST_RIGHT = 113, DEST_LEFT = 114;
    /** how far before a maneuver the car's navigation switches to its turn format and starts the bar (m) */
    static final int GUIDE_WINDOW = 457;
    /** Android Auto's rerouting status; the cluster's "Recalculating..." type; how long a reroute runs before it shows */
    static final int STATUS_REROUTING = 3, TYPE_RECALCULATING = 10;
    static final long REROUTE_SHOW_MS = 1000;

    final Context context;
    final Cluster cluster;
    final Handler handler;
    final Runnable tick = new Runnable() {
        @Override
        public void run() {
            unitTick();
        }
    };

    // the next turn
    int event, turnSide, turnAngle, turnNumber, displayType, direction, unit;
    String road;
    float distance;
    int meters = -1;
    boolean navLive;
    /**
     * The roundabout being guided through: the exit icon and direction its
     * ROUNDABOUT_ENTER_AND_EXIT gave, kept for the ROUNDABOUT_EXIT Android Auto sends once the
     * car is in it (with no side and no angle). -1: none.
     */
    int roundaboutType = -1, roundaboutDir;
    /** a reroute is going on; "Recalculating..." is up (standard turns only) */
    boolean rerouting, rerouteShown;
    /**
     * Setting tbt_standard (off by default): the next maneuver as the car's own navigation sends it
     * (standard(), from its cluster code: libExSLNavi, Navi::CLUSTER::Turn::CInformation,
     * US Ioniq 5 260507). Within GUIDE_WINDOW of a maneuver: its type (TURN, U_TURN,
     * FORK_2, EXIT_*, roundabout) with the maneuver's angle and the distance bar counting
     * down; farther away, the projection icon with the bar off; destinations as its
     * destination icons. The HUD's turn list keeps the projection icons, as the car's does.
     */
    boolean standardTurns;
    // destination and the remaining trip
    String destName;
    int estPoint;
    int tripMeters = -1, tripSeconds = -1, tripUnit, tripGen, sentGen;
    int lastUsm = -2;
    // HUD turn list (what was sent last)
    int hudType = -1, hudNext;
    boolean hudFresh;
    String hudRoad, laneKey;
    // the turns ahead (index 0..2)
    final String[] aheadName = new String[3];
    final int[] aheadEv = new int[3], aheadSide = new int[3], aheadAng = new int[3], aheadExit = new int[3];
    final int[] aheadMeters = new int[3], aheadUnit = new int[3], aheadE3 = new int[3];
    final boolean[] aheadOk = new boolean[3];
    /**
     * The last full 0x8006 / 0x8007 as AaNavState decoded them from the dump (null until one
     * comes): the exact maneuver (0..50), lanes, cue, time to step, every destination. Kept and
     * logged (AANAV, no text) only; nothing drawn uses them yet.
     */
    AaNavState.State navState;
    AaNavState.Position navPosition;
    String navLogged;
    /**
     * Setting tbt_route_details (off by default): use the full message for what the library's
     * turn events drop. Keep left/right as the car's fork ("keep") instead of a slight turn; a
     * clockwise roundabout (left-hand traffic) in the car's clockwise set, also for the newer
     * enter/exit types the library sends with no side; on-ramps at their real angle near them;
     * the next charging stop's planned minutes after its name; and the distance counting down
     * between the phone's updates (about 1 s apart), from the speed its distance and time give.
     */
    boolean routeDetails;
    /** the full message's maneuver (AaNavState.M_*) for the current turn, -1: none or not used */
    int exactType = -1;
    /** the roundabout being guided through is clockwise (kept for its ROUNDABOUT_EXIT) */
    boolean roundaboutCw;
    /** what went to the cluster's destination name last */
    String destSent;

    AaTurns(Context context, Cluster cluster, Handler handler) {
        this.context = context;
        this.cluster = cluster;
        this.handler = handler;
    }

    // ---- from the AA library

    void nextTurn(String str, int side, int ev, int angle, int number) {
        if (ev == EV_DUMP) {
            Log.d("NAVDUMP", String.valueOf(str));
            hmgapi.hook.ProjectionHooks.navigationDump(str);   // to mods that asked for projection events (EGMP's route data)
            navDump(str);
            return;
        }
        if (ev == EV_LANES) {
            sendLanes(str);
            return;
        }
        if (ev == EV_DEST_NAME) {
            if (str == null || str.length() == 0) {
                destName = "";
                sendDestName();
                destName = null;
                return;
            }
            if (number >= 0) estPoint = number;
            if (!str.equals(destName)) {
                destName = str;
                sendDestName();
            }
            return;
        }
        if (ev == EV_AHEAD_END) {
            if (number > 0) sendAhead(Math.min(number, 3));
            return;
        }
        if (ev >= EV_AHEAD && ev < EV_AHEAD_END) {
            int k = ev - EV_AHEAD;
            aheadName[k] = str == null ? "" : str;
            aheadSide[k] = side;
            aheadAng[k] = angle;
            aheadEv[k] = number & 0xff;
            aheadExit[k] = (number >> 8) & 0xff;
            aheadOk[k] = false;
            return;
        }
        if (rerouting) {   // the new route's first turn
            rerouting = false;
            rerouteShown = false;
            handler.removeCallbacks(rerouteShow);
        }
        hudFresh = false;
        boolean first = road == null;
        event = ev;
        road = str;
        turnSide = side;
        turnAngle = angle;
        turnNumber = number;
        if (!isRoundabout(ev)) roundaboutType = -1;
        exactType = routeDetails ? exactFor(ev) : -1;
        switch (ev) {
            case DEPART:
                if (first) {
                    cluster.destinationName(0, road);
                    distance = -1f;
                } else {
                    cluster.crossroadName(road);
                    setTurnType(ev, turnSide);
                }
                break;
            case NAME_CHANGE: case SLIGHT_TURN: case TURN: case SHARP_TURN: case STRAIGHT: case MERGE:
                cluster.crossroadName(road);
                setTurnType(ev, turnSide);
                break;
            case U_TURN:
                if (side == UNSPECIFIED) turnSide = LEFT;
                cluster.crossroadName(road);
                setTurnType(ev, turnSide);
                break;
            case ON_RAMP: case OFF_RAMP: case FORK: case FERRY_BOAT:
                cluster.crossroadName(road);
                setRampType(ev, turnSide);
                break;
            case ROUNDABOUT_EXIT: case ROUNDABOUT_ENTER_AND_EXIT:
                cluster.crossroadName(road);
                setRoundaboutType(ev, turnSide, turnAngle);
                break;
            case DESTINATION:
                displayType = 3;
                break;
            case UNKNOWN:
                displayType = 65;   // straight: 64 is the cluster's spinner
                direction = 0;
                break;
            default:
                logTurn();
                return;   // ROUNDABOUT_ENTER, FERRY_TRAIN: not for the cluster
        }
        applyExact();
        logTurn();
        sendTurn();
    }

    /**
     * The full message's maneuver if it is this turn: the library calls back with the dump
     * (event 33) first, then the turn, for the same 0x8006 (emulated on the US 260507 library),
     * so navState is the message this turn came from. -1 if it doesn't match.
     */
    int exactFor(int ev) {
        AaNavState.Step st = navState == null ? null : navState.step();
        if (st == null || st.type < 0) return -1;
        if (isRoundabout(ev) && AaNavState.isRoundabout(st.type)) return st.type;
        return AaNavState.legacy(st.type)[0] == ev ? st.type : -1;
    }

    static boolean keep(int t) {
        return t == AaNavState.M_KEEP_LEFT || t == AaNavState.M_KEEP_RIGHT;
    }

    static boolean clockwise(int t) {
        return t == AaNavState.M_ROUNDABOUT_ENTER_AND_EXIT_CW || t == AaNavState.M_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE
                || t == AaNavState.M_ROUNDABOUT_ENTER_CW || t == AaNavState.M_ROUNDABOUT_EXIT_CW;
    }

    /** Refines the projection icon from exactType (tbt_route_details). */
    void applyExact() {
        if (exactType < 0) return;
        if (keep(exactType)) {
            boolean r = exactType == AaNavState.M_KEEP_RIGHT;
            displayType = r ? 173 : 174;   // the fork icons, as for FORK
            direction = 0;
        } else if (isRoundabout(event)) {
            if (event != ROUNDABOUT_EXIT) roundaboutCw = clockwise(exactType);
            boolean cw = clockwise(exactType) || (event == ROUNDABOUT_EXIT && roundaboutCw);
            if (cw && displayType >= 96 && displayType <= 107) displayType += 96;   // the car's clockwise set, 192-203
        }
    }

    /** standard() for the current turn, with exactType's keep and on-ramp angle (tbt_route_details). */
    int[] standardNow(int m) {
        boolean k = exactType >= 0 && keep(exactType);
        int[] n = standard(k ? FORK : event, turnSide, displayType, direction, m);
        if (exactType >= 0 && event == ON_RAMP && n[0] == TYPE_TURN && (turnSide == LEFT || turnSide == RIGHT)) {
            boolean r = turnSide == RIGHT;
            switch (exactType) {
                case AaNavState.M_ON_RAMP_NORMAL_LEFT: case AaNavState.M_ON_RAMP_NORMAL_RIGHT:
                    n[1] = r ? 90 : 270;
                    break;
                case AaNavState.M_ON_RAMP_SHARP_LEFT: case AaNavState.M_ON_RAMP_SHARP_RIGHT:
                case AaNavState.M_ON_RAMP_U_TURN_LEFT: case AaNavState.M_ON_RAMP_U_TURN_RIGHT:
                    n[1] = r ? 135 : 225;
                    break;
                default:
                    break;   // slight: 45 / 315, as standard() has it
            }
        }
        return n;
    }

    /**
     * One line per maneuver in HMG-API's log (and so in a Car Report): Android Auto's event,
     * side, angle and number, and the type and direction it became; with standard turns, also
     * what goes out near it. Numbers only, no road names. Issue #19: which side Android Auto
     * gives a roundabout, and which way the cluster then draws it.
     */
    void logTurn() {
        String s = "[wireless-carplay-aa] AA turn: event " + event + " side " + turnSide + " angle " + turnAngle
                + " number " + turnNumber + (exactType >= 0 ? " exact " + exactType : "") + " -> type " + displayType
                + " dir " + direction;
        if (standardTurns) {
            int[] n = standardNow(GUIDE_WINDOW);
            s += ", near: type " + n[0] + " dir " + n[1];
        }
        if (s.equals(lastLog)) return;   // Android Auto sends the same maneuver again about every 1.3 s
        lastLog = s;
        Log.i("HMG-API", s);
    }

    /** the last maneuver line logged */
    String lastLog;

    void nextTurnDistance(int meters, int seconds, int e3, int unitFlags) {
        if ((unitFlags & FLAG_AHEAD) != 0) {
            int k = (unitFlags >> 10) & 3;
            if (k > 2) return;
            aheadMeters[k] = meters;
            aheadE3[k] = e3;
            aheadUnit[k] = unitFlags & 0xff;
            aheadOk[k] = true;
            return;
        }
        if ((unitFlags & FLAG_TRIP) != 0) {
            if ((unitFlags & FLAG_POINT) != 0) {
                int p = (unitFlags >> 12) & 15;
                estPoint = p > 4 ? 0 : p;
            }
            tripMeters = meters;
            tripSeconds = seconds;
            tripUnit = unitFlags & 0xff;
            tripGen++;
            sendRemain();
            return;
        }
        setDistance(meters, e3, unitFlags);
        setTurnType(event, turnSide);
        applyExact();
        sendTurn();
        if (routeDetails) smoothFrom(meters, seconds, unitFlags & 0xff);
    }

    // ---- the distance between the phone's updates (tbt_route_details)

    /** only this close to the turn (m); how often (ms); how long without an update before it holds (ms) */
    static final int SMOOTH_MAX_M = 2 * GUIDE_WINDOW;
    static final long SMOOTH_MS = 500, SMOOTH_STALE_MS = 2500;
    int smMeters = -1, smUnit;
    long smAt;
    float smSpeed;
    /** host tests set the clock; -1: the real one */
    static long testNow = -1;

    static long now() {
        return testNow >= 0 ? testNow : System.nanoTime() / 1000000L;
    }

    final Runnable smooth = new Runnable() {
        @Override
        public void run() {
            smoothTick();
        }
    };

    /** A distance update from the phone: count down from it at the speed its distance and time to the turn give. */
    void smoothFrom(int m, int seconds, int u) {
        handler.removeCallbacks(smooth);
        smMeters = -1;
        if (m <= 0 || seconds <= 0 || m > SMOOTH_MAX_M) return;
        float v = m / (float) seconds;
        if (v < 1f || v > 60f) return;   // stopped, or not a speed
        smMeters = m;
        smUnit = u;
        smSpeed = v;
        smAt = now();
        handler.postDelayed(smooth, SMOOTH_MS);
    }

    void smoothTick() {
        if (smMeters < 0 || !navLive || rerouteShown) return;
        long dt = now() - smAt;
        if (dt > SMOOTH_STALE_MS) return;   // no update from the phone: hold what it said last
        int est = Math.max(0, Math.round(smMeters - smSpeed * dt / 1000f));
        float before = distance;
        int beforeMeters = meters;
        setDistance(est, e3For(est, smUnit), smUnit);
        distance = rounded(distance, unit);
        boolean barMoved = standardTurns && (beforeMeters * 10 / GUIDE_WINDOW) != (est * 10 / GUIDE_WINDOW);
        if (distance != before || barMoved) {
            setTurnType(event, turnSide);
            applyExact();
            sendTurn();
        }
        handler.postDelayed(smooth, SMOOTH_MS);
    }

    /** the phone's distance value x 1000 (its e3) for m metres in unit u */
    static int e3For(int m, int u) {
        switch (u) {
            case FEET: return m * 3281;
            case YARDS: return m * 1094;
            case MILES: case MILES_P1: return m * 1000 / 1609;
            case KILOMETERS: case KILOMETERS_P1: return m;
            default: return m * 1000;
        }
    }

    /** as the phone shows them: feet and metres in 10s, miles and km in tenths */
    static float rounded(float d, int clusterUnit) {
        if (d < 0f) return d;
        if (clusterUnit == Cluster.UNIT_FT || clusterUnit == Cluster.UNIT_M || clusterUnit == Cluster.UNIT_YD)
            return Math.round(d / 10f) * 10f;
        return Math.round(d * 10f) / 10f;
    }

    void status(int s) {
        if (s == 1 || s == STATUS_REROUTING) {   // active, rerouting
            navLive = true;
            if (s == STATUS_REROUTING) {
                reroute();
            } else if (rerouting) {
                endReroute();
            }
            return;
        }
        rerouting = false;
        rerouteShown = false;
        handler.removeCallbacks(rerouteShow);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(smooth);
        smMeters = -1;
        destName = null;
        navLive = false;
        tripMeters = -1;
        tripSeconds = -1;
        estPoint = 0;
        roundaboutType = -1;
        release();
    }

    /**
     * Android Auto is rerouting (status 3). With standard turns, a reroute still going after
     * REROUTE_SHOW_MS shows the cluster's "Recalculating..." (type 10, RECALC ROUTE: the
     * cluster draws it with a spinner, on a US Ioniq 5 and on the Creta Electric), until the
     * new route's first turn or the end of the reroute. A short one leaves the turn as it is.
     * Without it the AR HUD broke for about a minute after a missed exit on a US EV6.
     */
    void reroute() {
        if (!standardTurns || rerouting) return;
        rerouting = true;
        handler.removeCallbacks(rerouteShow);
        handler.postDelayed(rerouteShow, REROUTE_SHOW_MS);
    }

    final Runnable rerouteShow = new Runnable() {
        @Override
        public void run() {
            if (!rerouting || !navLive) return;
            rerouteShown = true;
            cluster.tbtInfo(TYPE_RECALCULATING, 65535, 0f, 0f, -3, 0, 15, 0, -1);
        }
    };

    /** The reroute is over: the turn goes back up if "Recalculating..." replaced it. */
    void endReroute() {
        boolean shown = rerouteShown;
        rerouting = false;
        rerouteShown = false;
        handler.removeCallbacks(rerouteShow);
        if (shown && road != null) sendTurn();
    }

    /** The car's navigation took over (focus native): forget the turn. */
    void stop() {
        handler.removeCallbacks(smooth);
        smMeters = -1;
        exactType = -1;
        event = 0;
        road = null;
        turnSide = 0;
        turnAngle = 0;
        turnNumber = 0;
        unit = 0;
        meters = -1;
        displayType = 0;
        direction = 0;
        roundaboutType = -1;
        rerouting = false;
        rerouteShown = false;
        handler.removeCallbacks(rerouteShow);
    }

    /** The connection ended: clear what we showed, if a route was on. */
    void end() {
        handler.removeCallbacks(tick);
        if (navLive) status(2);
        navState = null;
        navPosition = null;
        navLogged = null;
    }

    /** Decodes the full message in a dump and keeps it; logs a text-free summary when the S summary changes. */
    void navDump(String str) {
        AaNavState.Dump d;
        try {
            d = AaNavState.parse(str);
        } catch (AaNavState.ParseException e) {
            Log.d("AANAV", "bad dump: " + e.getMessage());
            return;
        }
        if (d == null) return;
        if (d.state != null) {
            navState = d.state;
            if (routeDetails && destName != null && destName.length() > 0 && !destText().equals(destSent)) sendDestName();
            String s = AaNavState.summary(d);
            if (!s.equals(navLogged)) {
                navLogged = s;
                Log.d("AANAV", s);
            }
        } else {
            navPosition = d.position;
        }
    }

    // ---- the next turn

    void sendTurn() {
        if (!navLive || rerouteShown) return;   // "Recalculating..." stays until the new route's turn
        int type = displayType, dir = direction, bar = 32, barErr = 0, sides = 65535;
        if (distance < 0f) {
            bar = 254;
            barErr = -2;
            if (displayType < 64) displayType = type = 2;
        } else if (standardTurns) {
            int[] s = standardNow(meters);
            type = s[0];
            dir = s[1];
            bar = s[2];
            // the car's own types draw the intersection from the side-street bits (one per
            // 22.5 degrees): 65535 is a road every way. The car's navigation sends 0 (the
            // phone has no intersection shape either); the icons keep 65535
            if (type < 64) sides = 0;
        }
        cluster.tbtInfo(type, sides, dir, distance, 0, 0, unit, bar, barErr);
        sendHudList();
        sendHudDistance();
    }

    /**
     * {type, direction, bar} for the main display, as the car's navigation sends them: its
     * cluster code (Navi::CLUSTER::Turn::CInformation) picks a type per maneuver near it
     * (SetCaseTurnPoint, SetCaseRotary, SetCaseExpExit, SetCaseDestWayPoint), the angle
     * in 45 degree steps (convertRGIconIDToClusterAngle) and the bar from the progress to
     * the maneuver: (100 - progress%) / 10, at least 1. The progress runs over about 457 m
     * (two drives: 0.3 mi 9, 900 ft 6, 300 ft 2, 30 ft 1). Farther away it keeps the icon
     * (RGIconIDToClusterDispType, the same codes as ours) with the bar off.
     * icon: our projection type for this maneuver; iconDir: its direction.
     */
    static int[] standard(int ev, int side, int icon, int iconDir, int m) {
        if (ev == DESTINATION) {
            if (m >= 0 && m < 20) return new int[] { 3, 0, 0 };   // ARRIVED
            int d = side == RIGHT ? DEST_RIGHT : side == LEFT ? DEST_LEFT : DEST_AHEAD;
            return new int[] { d, 0, 0 };
        }
        if (m < 0 || m > GUIDE_WINDOW) return new int[] { icon, iconDir, 0 };
        int bar = Math.max(1, Math.min(9, m * 10 / GUIDE_WINDOW)) * 10;
        boolean r = side == RIGHT, l = side == LEFT;
        switch (ev) {
            case STRAIGHT: case MERGE:
                return new int[] { TYPE_TURN, 0, bar };
            case NAME_CHANGE:
                return r || l ? new int[] { TYPE_TURN, r ? 90 : 270, bar } : new int[] { TYPE_TURN, 0, bar };
            case SLIGHT_TURN:
                if (r || l) return new int[] { TYPE_TURN, r ? 45 : 315, bar };
                break;
            case TURN:
                if (r || l) return new int[] { TYPE_TURN, r ? 90 : 270, bar };
                break;
            case SHARP_TURN:
                if (r || l) return new int[] { TYPE_TURN, r ? 135 : 225, bar };
                break;
            case U_TURN:
                return new int[] { TYPE_U_TURN, r ? 90 : 270, bar };
            case ON_RAMP:
                // the car's navigation has no ramp type: entering a ramp is one of its turn
                // points (TURN, at the ramp's angle), which the AR HUD animates; the icon doesn't
                return new int[] { TYPE_TURN, r ? 45 : l ? 315 : 0, bar };
            case FORK:
                if (r || l) return new int[] { TYPE_FORK, r ? 90 : 270, bar };
                break;
            case OFF_RAMP:
                if (r || l) return new int[] { r ? TYPE_EXIT_RIGHT : TYPE_EXIT_LEFT, r ? 90 : 270, bar };
                break;
            case ROUNDABOUT_EXIT: case ROUNDABOUT_ENTER_AND_EXIT:
                // the exit's direction. 32 draws a counterclockwise roundabout, 33 a clockwise one
                // (measured on a US Ioniq 5, issue #19), so the icon's set picks it, not Android
                // Auto's side: it marked a US (counterclockwise) roundabout LEFT, drawn clockwise
                return new int[] { clockwiseIcon(icon) ? TYPE_ROUNDABOUT_LEFT : TYPE_ROUNDABOUT_RIGHT, iconDir, bar };
            default:
                break;
        }
        return new int[] { icon, iconDir, bar };   // ferries, no side: the icon, with the bar
    }

    /** Distance to the next turn, in the owner's unit (Settings) where the phone's differs. */
    void setDistance(int meters, int e3, int u) {
        this.meters = meters;
        int usm = Car.distanceUnit(context);
        if (meters >= 0) {
            if (usm == Car.DISTANCE_KM) {
                if (u >= MILES) {
                    if (meters < 1000) {
                        u = METERS;
                    } else {
                        e3 = meters;
                        u = KILOMETERS;
                    }
                }
            } else if (usm == Car.DISTANCE_MILES) {
                if (meters >= 161) {
                    if (u < MILES) {
                        e3 = meters * 1000 / 1609;
                        u = MILES_P1;
                    }
                } else if (u != FEET && u != YARDS) {
                    e3 = meters * 3281;
                    u = FEET;
                }
            }
        }
        distance = (float) (e3 / 1000.0d);
        if (u == METERS) {
            if (meters >= 1000) {
                distance = (float) (meters / 1000.0d);
                unit = Cluster.UNIT_KM;
            } else {
                distance = meters;
                unit = Cluster.UNIT_M;
            }
        } else if (u == KILOMETERS || u == KILOMETERS_P1) {
            unit = Cluster.UNIT_KM;
        } else if (u == MILES || u == MILES_P1) {
            unit = Cluster.UNIT_MI;
        } else if (u == FEET) {
            unit = Cluster.UNIT_FT;
        } else if (u == YARDS) {
            unit = Cluster.UNIT_YD;
        } else {
            unit = 15;
        }
    }

    void setTurnType(int ev, int side) {
        switch (ev) {
            case STRAIGHT: case MERGE:
                displayType = 65;
                direction = 0;
                break;
            case SLIGHT_TURN:
                set(side, 66, 30, 71, 330);
                break;
            case TURN:
                set(side, 67, 90, 70, 270);
                break;
            case SHARP_TURN:
                set(side, 68, 135, 69, 225);
                break;
            case U_TURN:
                displayType = side == RIGHT ? 73 : 72;
                direction = 270;
                break;
            case NAME_CHANGE:
                if (side == RIGHT || side == LEFT) {
                    set(side, 67, 90, 70, 270);
                } else {
                    displayType = 65;
                    direction = 0;
                }
                break;
            default:
                break;   // ramps, roundabouts, destination: set by their own
        }
    }

    /**
     * right type/direction, left type/direction, otherwise straight (65). Never 64 ("no
     * direction"): the cluster draws its loading spinner for it.
     */
    private void set(int side, int rType, int rDir, int lType, int lDir) {
        if (side == RIGHT) {
            displayType = rType;
            direction = rDir;
        } else if (side == LEFT) {
            displayType = lType;
            direction = lDir;
        } else {
            displayType = 65;
            direction = 0;
        }
    }

    void setRampType(int ev, int side) {
        if (ev == ON_RAMP) {
            if (side == RIGHT) {
                displayType = 173;
                direction = 30;
            } else if (side == LEFT) {
                displayType = 174;
                direction = 330;
            } else {
                displayType = 172;
                direction = 0;
            }
        } else if (ev == OFF_RAMP) {
            if (side == RIGHT) {
                displayType = 81;
                direction = 30;
            } else if (side == LEFT) {
                displayType = 82;
                direction = 330;
            } else {
                displayType = 86;
                direction = 0;
            }
        } else if (ev == FORK) {
            // no side: the plain one (otherwise the last maneuver's icon stayed on)
            displayType = side == RIGHT ? 173 : side == LEFT ? 174 : 172;
            direction = 0;
        } else if (ev == FERRY_BOAT) {
            displayType = 176;
            direction = 0;
        }
    }

    /**
     * Roundabouts: the exit's angle picks the icon (the cluster draws them for right-hand traffic).
     * Once the car is in the roundabout, Android Auto sends ROUNDABOUT_EXIT, usually with no side
     * and no angle: the exit its ROUNDABOUT_ENTER_AND_EXIT gave stays on until the next
     * maneuver. Never type 64 ("no direction"): the cluster draws its loading spinner for it, in
     * the turn and in the HUD list (Creta Electric symbol sweep; on the road it stayed until
     * the next turn). Straight (65) where there's no exit to show.
     */
    void setRoundaboutType(int ev, int side, int a) {
        if (ev == ROUNDABOUT_EXIT) {
            // with or without a side: Android Auto's side doesn't say which way the roundabout
            // goes (issue #19: a US roundabout marked LEFT, drawn clockwise as 33)
            if (roundaboutType >= 0) {
                displayType = roundaboutType;
                direction = roundaboutDir;
            } else {
                int[] r = roundabout(a);
                displayType = r[0];
                direction = r[1];
            }
            return;
        }
        int[] r = roundabout(a);
        displayType = r[0];
        direction = r[1];
        roundaboutType = r[0] == 65 ? -1 : r[0];
        roundaboutDir = r[1];
    }

    /** The car's clockwise (left-hand traffic) roundabout icons, 192-203: the counterclockwise 96-107 + 96. */
    static boolean clockwiseIcon(int type) {
        return type >= 192 && type <= 203;
    }

    /**
     * {icon, direction} for a roundabout exit at Android Auto's turn angle a; straight (65) without one.
     * The icons are the ones the car's own navigation uses, one per 45 degrees: 96 97 99 100 102
     * 104 105 107. Never 103 or 106: the car doesn't use them, and the Creta Electric's sweep saw
     * 106's left-hand traffic twin (0xca) draw the loading spinner.
     */
    static int[] roundabout(int a) {
        if (a == 180) return new int[] { 96, 0 };
        if (a >= 135 && a < 180) return new int[] { 97, 45 };
        if (a >= 90 && a < 135) return new int[] { 99, 90 };
        if (a >= 45 && a < 90) return new int[] { 100, 135 };
        if ((a >= 0 && a < 45) || (a > 315 && a <= 360)) return new int[] { 102, 180 };
        if (a > 270 && a <= 315) return new int[] { 104, 225 };
        if (a > 225 && a <= 270) return new int[] { 105, 270 };
        if (a > 180 && a <= 225) return new int[] { 107, 315 };
        return new int[] { 65, 0 };
    }

    // ---- HUD

    /** The HUD's turn list: this turn's icon, the next one's (if known) and the road name, when one of them changed. */
    void sendHudList() {
        int type = displayType;
        if (type < 64) return;
        int next = 0;
        if (aheadOk[0]) {
            next = aheadType(aheadEv[0], aheadSide[0], aheadAng[0]);
            hudFresh = true;
        } else if (hudFresh) {
            next = hudNext;
        }
        String r = road == null ? "" : road;
        if (hudType == type && hudNext == next && r.equals(hudRoad)) return;
        byte[] name;
        try {
            name = r.getBytes("UTF-16LE");
        } catch (java.io.UnsupportedEncodingException e) {
            return;
        }
        int n = Math.min(name.length, 78);
        byte[] f = new byte[n + 5];
        f[0] = HUD_LIST;
        f[1] = (byte) (next != 0 ? 2 : 1);
        f[2] = (byte) type;
        f[3] = (byte) next;
        System.arraycopy(name, 0, f, 5, n);
        cluster.turnListNew(f);
        hudType = type;
        hudNext = next;
        hudRoad = r;
    }

    /** The HUD's distance: whole number (up to 4095) and tenths for km/mi. */
    void sendHudDistance() {
        float d = distance;
        int u = unit;
        if (d < 0f || u < 0 || u > 4) return;
        int whole = Math.min((int) d, 4095);
        int tenths = 0;
        if (u == Cluster.UNIT_KM || u == Cluster.UNIT_MI) tenths = Math.max(0, Math.min(9, (int) ((d - whole) * 10f + 0.5f)));
        cluster.tbtSubInfo2C(u, tenths, whole, 0, 0, 0, 0, 0, 0);
    }

    void hudBlank() {
        laneKey = "";
        cluster.tbtSubInfo2C(0, 0, 0, 0, 0, 0, 0, 0, 0);
        byte[] lanes = new byte[81];
        lanes[0] = LANES;
        cluster.laneInfo(lanes);
        hudType = -1;
        hudNext = 0;
        hudFresh = false;
        hudRoad = null;
        cluster.turnListNew(new byte[] { HUD_LIST });
        cluster.turnListNew(new byte[] { HUD_LIST });
    }

    /** Clears the turn, the remaining trip, the destination and the HUD. */
    void release() {
        destName = "";
        sendDestName();
        destName = null;
        cluster.tbtInfo(0, 0, 0f, 0f, -1, 0, 0, 0, -1);
        cluster.tbtSubInfo(0f, -1, 0, 0, 0, 0, 0, 0f, -1, 0);
        hudBlank();
    }

    void sendDestName() {
        if (destName == null) return;
        String t = destText();
        destSent = t;
        cluster.destinationName(0, t);
    }

    /**
     * The destination name; with tbt_route_details, a charging stop's planned minutes after it
     * ("Electrify America - 24 min charge"), from the full message's first destination.
     */
    String destText() {
        if (!routeDetails || destName == null || destName.length() == 0 || navState == null
                || navState.destinations.isEmpty()) return destName;
        AaNavState.Destination d = navState.destinations.get(0);
        if (!d.station || d.chargeSeconds <= 0) return destName;
        return destName + " - " + Math.max(1, (d.chargeSeconds + 30) / 60) + " min charge";
    }

    // ---- lanes: "nn" lane count, then per lane 8 hex digits (type, then 3 arrow bytes)

    void sendLanes(String s) {
        if (s == null) s = "";
        if (s.equals(laneKey)) return;
        int n = 0;
        if (s.length() >= 2) {
            n = hexByte(s, 0);
            if (n < 0 || n > 8) return;
        }
        byte[] f = new byte[81];
        f[0] = LANES;
        f[1] = (byte) n;
        for (int k = 0; k < n; k++) {
            int at = k * 8 + 2;
            int b = hexByte(s, at);
            if (b < 0) return;
            f[k + 3] = (byte) b;
            for (int j = 0; j < 3; j++) {
                b = hexByte(s, at + 2 + j * 2);
                if (b < 0) return;
                f[k * 3 + 19 + j] = (byte) b;
            }
        }
        cluster.laneInfo(f);
        laneKey = s;
    }

    static int hexByte(String s, int at) {
        if (at + 1 >= s.length()) return -1;
        int hi = hex(s.charAt(at)), lo = hex(s.charAt(at + 1));
        return hi < 0 || lo < 0 ? -1 : hi << 4 | lo;
    }

    static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }

    // ---- the turns ahead (cluster turn list)

    /**
     * A turn ahead's icon: the same as the next turn's (setTurnType, setRampType,
     * setRoundaboutType), destinations as their icons, straight (65) for the rest. Never 64:
     * the cluster draws its card from this list far from a turn, and 64 is its spinner.
     */
    static int aheadType(int ev, int side, int angle) {
        boolean r = side == RIGHT, l = side == LEFT;
        switch (ev) {
            case ROUNDABOUT_ENTER: case ROUNDABOUT_EXIT: case ROUNDABOUT_ENTER_AND_EXIT: return roundabout(angle)[0];
            case SLIGHT_TURN: return r ? 66 : l ? 71 : 65;
            case TURN: case NAME_CHANGE: return r ? 67 : l ? 70 : 65;
            case SHARP_TURN: return r ? 68 : l ? 69 : 65;
            case U_TURN: return r ? 73 : 72;
            case ON_RAMP: case FORK: return r ? 173 : l ? 174 : 172;
            case OFF_RAMP: return r ? 81 : l ? 82 : 86;
            case FERRY_BOAT: case FERRY_TRAIN: return 176;
            case DESTINATION: return r ? DEST_RIGHT : l ? DEST_LEFT : DEST_AHEAD;
            default: return 65;   // straight, merge, depart, unknown
        }
    }

    static boolean isRoundabout(int ev) {
        return ev == ROUNDABOUT_ENTER || ev == ROUNDABOUT_EXIT || ev == ROUNDABOUT_ENTER_AND_EXIT;
    }

    void sendAhead(int n) {
        int count = 0;
        for (int k = 0; k < n; k++) if (aheadOk[k]) count++;
        if (count == 0) return;
        int usm = Car.distanceUnit(context);
        Cluster.Turn[] list = new Cluster.Turn[count];
        int i = 0;
        for (int k = 0; k < n && i < count; k++) {
            if (!aheadOk[k]) continue;
            Cluster.Turn t = new Cluster.Turn();
            t.type = aheadType(aheadEv[k], aheadSide[k], aheadAng[k]);
            t.direction = isRoundabout(aheadEv[k]) ? roundabout(aheadAng[k])[1] : Math.max(aheadAng[k], 0);
            t.traffic = 0;
            t.no = i + 1;
            t.name = aheadName[k] == null ? "" : aheadName[k];
            t.exitNumber = aheadExit[k] == 255 || aheadExit[k] < 0 ? "" : String.valueOf(aheadExit[k]);
            int m = aheadMeters[k], e3 = aheadE3[k], u = aheadUnit[k];
            int cu = Cluster.UNIT_M;
            float d = m;
            if (e3 >= 0) {
                if (u == KILOMETERS || u == KILOMETERS_P1) { cu = Cluster.UNIT_KM; d = e3 / 1000f; }
                else if (u == MILES || u == MILES_P1) { cu = Cluster.UNIT_MI; d = e3 / 1000f; }
                else if (u == FEET) { cu = Cluster.UNIT_FT; d = e3 / 1000f; }
                else if (u == YARDS) { cu = Cluster.UNIT_FT; d = e3 / 1000f * 3f; }
            }
            if (usm == Car.DISTANCE_KM) {
                if (m < 1000) { cu = Cluster.UNIT_M; d = m; }
                else { cu = Cluster.UNIT_KM; d = m / 1000f; }
            } else if (usm == Car.DISTANCE_MILES) {
                if (m < 161) { cu = Cluster.UNIT_FT; d = m * 3.28084f; }
                else { cu = Cluster.UNIT_MI; d = m / 1609f; }
            }
            t.distance = Math.max(0f, Math.min(65535f, d));
            t.unit = cu;
            list[i++] = t;
        }
        cluster.turnList(list);
        if (aheadOk[0]) {
            hudNext = aheadType(aheadEv[0], aheadSide[0], aheadAng[0]);
            hudFresh = true;
        }
        for (int k = 0; k < 3; k++) aheadOk[k] = false;
    }

    // ---- remaining distance and arrival time

    /** Sends the remaining trip when it changed or the owner changed units; then checks again every 250 ms. */
    void sendRemain() {
        if (!navLive || tripMeters < 0) return;
        if (tripGen == sentGen) {
            int usm = Car.distanceUnit(context);
            if (usm == -1 || usm == lastUsm) return;
        }
        sentGen = tripGen;
        int m = tripMeters, u = tripUnit;
        int usm = Car.distanceUnit(context);
        lastUsm = usm;
        if (usm == Car.DISTANCE_KM) u = m < 1000 ? METERS : KILOMETERS;
        else if (usm == Car.DISTANCE_MILES) u = m < 161 ? FEET : MILES;
        int whole, tenths = 0, cu;
        if (u == KILOMETERS || u == KILOMETERS_P1 || (u != MILES && u != MILES_P1 && u != FEET && u != YARDS && m >= 1000)) {
            whole = m / 1000;
            tenths = m % 1000 / 100;
            cu = Cluster.UNIT_KM;
        } else if (u == MILES || u == MILES_P1) {
            int t = m * 10 / 1609;
            tenths = t % 10;
            whole = t / 10;
            cu = Cluster.UNIT_MI;
        } else if (u == FEET) {
            whole = m * 3281 / 1000;
            cu = Cluster.UNIT_FT;
        } else if (u == YARDS) {
            whole = m * 1094 / 1000;
            cu = Cluster.UNIT_YD;
        } else {
            whole = m;
            cu = Cluster.UNIT_M;
        }
        int hour = -1, minute = -1, timeType = -1, timeFormat = -1;
        int s = tripSeconds;
        Calendar c = null;
        if (s >= 500000000) {   // a clock time (seconds since 1970)
            c = Calendar.getInstance();
            c.setTimeInMillis(s * 1000L);
        } else if (s >= 0 && s <= 604800) {   // seconds from now
            c = Calendar.getInstance();
            c.add(Calendar.SECOND, s);
        }
        if (c != null) {
            hour = c.get(Calendar.HOUR_OF_DAY);
            minute = c.get(Calendar.MINUTE);
            timeType = 1;
            timeFormat = context == null || DateFormat.is24HourFormat(context) ? 2 : 1;
        }
        cluster.tbtSubInfo(whole + tenths / 10f, 0, cu, hour, minute, timeType, timeFormat, 0f, -1, estPoint);
        scheduleTick();
    }

    void scheduleTick() {
        if (tripMeters < 0) return;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 250);
    }

    void unitTick() {
        if (tripMeters < 0) return;
        sendRemain();
        scheduleTick();
    }
}
