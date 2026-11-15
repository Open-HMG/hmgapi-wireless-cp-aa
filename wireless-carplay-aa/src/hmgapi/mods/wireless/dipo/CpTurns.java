package hmgapi.mods.wireless.dipo;

import android.content.Context;
import android.os.SystemClock;
import android.text.format.DateFormat;

import com.tcc.iap2.Iap2NotiMsg;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/**
 * CarPlay turn-by-turn on the cluster and HUD: a port of the turn-by-turn build's DiPO
 * route guidance manager (e/u, with its route update e/c and maneuver e/v), fed by
 * HMG-API's own iAP2 listener instead of DiPO's, and sending through HMG-API's Cluster API.
 * DiPO's own manager only exists when stepofspec is 999 or more, and stays off.
 *
 * The iPhone sends RouteGuidanceUpdate (120: state, distances, the current maneuver
 * list), RouteGuidanceManeuverUpdate (121: one maneuver by index) and LaneGuidance
 * (122), each for a display component, often field by field. Kept per component as in
 * DiPO. What it sends, as the build did:
 *   - the turn (updateTBTInfo: icon, distance in the phone's units), crossroad name,
 *     and destination context whenever supplied during an active route
 *   - the HUD list (0xF2 frame: icon, next icon, road) and the HUD distance (2C)
 *   - remaining distance and arrival time (updateTBTSubInfo, in the owner's units)
 *   - up to 3 upcoming turns (updateTPTurnList, owner's units) and lanes (0xF1 frame)
 * Never the cluster's alive or operation status: phone guidance doesn't claim the
 * cluster (the cluster doesn't come back from that on these cars).
 * Also from the build: when the source app changes (Apple Maps to Waze, say), what the
 * old app left is cleared and route guidance restarted, so the phone resends all of it.
 * Not ported: DiPO's per-region icon filters (China, Europe, Middle East, Australia,
 * Brazil); on US and Canada cars the build sends every icon.
 * One thread: every call comes from the same handler.
 */
final class CpTurns {
    final Context context;
    final Cluster cluster;
    /** restarts the phone's route guidance updates (Stop, then Start) on a thread of its own */
    final Runnable restart;
    interface RouteStateSink { void active(boolean value); }
    final RouteStateSink routeState;
    boolean routeActive;
    String destinationSent;
    int destinationComponent = -1;
    boolean tripSent;
    boolean clusterSchematic;
    int approachComponent = -1, approachCurrent = -1, approachUnit = -1;
    float approachMetres = Float.NaN, approachRounded = Float.NaN, approachKmh = Float.NaN;
    long approachAt = -1;
    boolean approachRaw;
    /** component id -> its route guidance state (DiPO e/u.Al) */
    final LinkedHashMap<Integer, Update> updates = new LinkedHashMap<Integer, Update>();
    /** component id -> maneuver index -> maneuver (DiPO e/u.Am) */
    final LinkedHashMap<Integer, LinkedHashMap<Integer, Maneuver>> maneuvers = new LinkedHashMap<Integer, LinkedHashMap<Integer, Maneuver>>();
    String source;
    /** Coalesce bursts, but refresh on incoming guidance after two monotonic seconds. */
    int hudType, hudNext;
    String hudRoad;
    int hudComponent = -1, hudCurrent = -1, hudFollowing = -1;
    int hudManeuverType = -1, hudManeuverSide = -1, hudManeuverJunction = -1;
    long hudSentAt;
    /** Phone prefetches indexed lane events before selecting one in a route update. */
    static final int MAX_LANE_EVENTS = 32;
    final LinkedHashMap<Integer, LinkedHashMap<Integer, byte[]>> laneFrames = new LinkedHashMap<Integer, LinkedHashMap<Integer, byte[]>>();
    int laneComponent = -1;
    byte[] laneSent;
    long laneSentAt;

    CpTurns(Context context, Cluster cluster, Runnable restart) {
        this(context, cluster, restart, null);
    }

    CpTurns(Context context, Cluster cluster, Runnable restart, RouteStateSink routeState) {
        this.context = context;
        this.cluster = cluster;
        this.restart = restart;
        this.routeState = routeState;
    }

    void routeActive(boolean active) {
        if (routeActive == active) return;
        routeActive = active;
        if (routeState != null) routeState.active(active);
    }

    /** RouteGuidanceUpdate (DiPO e/u.c) */
    void routeGuidance(Iap2NotiMsg msg) {
        if (msg == null) return;
        traceRouteIndices(msg);
        sourceCheck(msg);
        int[] ids = componentIds(msg);
        if (ids == null) {
            for (Integer id : updates.keySet()) {
                Update u = updates.get(id);
                if (u != null) u.read(id, msg);
                turn(id, u);
            }
            return;
        }
        for (int k = 0; k < ids.length; k++) {
            Integer id = ids[k];
            Update u = updates.get(id);
            if (u != null) {
                u.read(id, msg);
            } else {
                u = new Update(id, msg);
                updates.put(id, u);
            }
            turn(id, u);
        }
    }

    /** the components a RouteGuidanceUpdate is for (item 0); null if it doesn't say */
    static int[] componentIds(Iap2NotiMsg msg) {
        Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
        if (g == null) return null;
        int[] ids = repeatedInts(g, 0, true);
        return ids == null || ids.length == 0 ? null : ids;
    }

    /** The native SDK emits repeated TLVs as separate numeric items. Collect
     * within this message only, retaining phone order and array-form compatibility.
     * Null means absent; an empty array means explicitly present with no values.
     */
    static int[] repeatedInts(Iap2NotiMsg.Iap2ObjGroup g, int field, boolean onlyU16) {
        java.util.ArrayList<Integer> values = new java.util.ArrayList<Integer>();
        boolean present = false;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null) return null;
            int type = it.getItemParamType();
            if (it.getItemId() != field || (type != 5 && (onlyU16 || type != 1))) continue;
            present = true;
            // The vendor Parcel reader leaves zero-count backing arrays null;
            // even asking its getter for an empty field throws.
            if (it.getItemObjCount() <= 0) continue;
            int[] part = it.getItemIntArray();
            if (part == null) continue;
            for (int j = 0; j < Math.min(it.getItemObjCount(), part.length); j++) values.add(part[j]);
        }
        if (!present) return null;
        int[] result = new int[values.size()];
        for (int k = 0; k < result.length; k++) result[k] = values.get(k);
        return result;
    }

    /** Before Update merges items, record component/selected indices and lifecycle flags.
     * Bound both item and value counts; never inspect string or other payloads. */
    static void traceRouteIndices(Iap2NotiMsg msg) {
        Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
        if (g == null) return;
        StringBuilder out = new StringBuilder("route-input items=").append(g.getItemCnt());
        int fields = 0;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null) continue;
            int field = it.getItemId();
            if (field != 0 && field != 1 && field != 2 && field != 10 && field != 11
                    && field != 12 && field != 13 && field != 15 && field != 18) continue;
            if (fields++ == 16) {
                out.append(" more-fields");
                break;
            }
            int type = it.getItemParamType(), n = it.getItemObjCount();
            out.append(' ').append(it.getItemId()).append(':').append(type).append(':').append(n);
            if (n <= 0 || (type != 1 && type != 5 && type != 6 && type != 8 && type != 9)) continue;
            int[] values = it.getItemIntArray();
            if (values == null) continue;
            out.append('[');
            int count = Math.min(n, values.length), shown = Math.min(count, 8);
            for (int j = 0; j < shown; j++) {
                if (j != 0) out.append(',');
                out.append(values[j]);
            }
            if (count > shown) out.append(",...");
            out.append(']');
        }
        Dipo.hudTrace(out.toString());
    }

    /**
     * RouteGuidanceManeuverUpdate (DiPO e/u.d): item 0 the components, item 1 the index.
     * Stored under the index the message carries (the build's fix, by Isaac Wisdom: stock
     * wrote the index into a copy and stored every maneuver under -1, so none was found).
     */
    void maneuver(Iap2NotiMsg msg) {
        if (msg == null) return;
        int index = -1;
        int[] ids = null;
        Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
        if (g != null) {
            boolean whole = true;
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                if (it == null) {
                    whole = false;
                    break;
                }
                int id = it.getItemId();
                if (it.getItemObjCount() <= 0 || (id != 0 && id != 1)) continue;
                int[] v = it.getItemIntArray();
                if (v == null || v.length == 0) continue;
                if (id == 1) index = v[0];
            }
            if (whole) ids = componentIds(msg);
        }
        if (index < 0) return;
        if (ids == null) {
            LinkedHashSet<Integer> known = new LinkedHashSet<Integer>(updates.keySet());
            known.addAll(maneuvers.keySet());
            for (Integer id : known) mergeManeuver(id, index, msg);
            return;
        }
        for (int id : ids) mergeManeuver(id, index, msg);
    }

    void mergeManeuver(int id, int index, Iap2NotiMsg msg) {
        Update u = updates.get(id);
        // Before the first route we may receive its details early. Once a route
        // has stopped or arrived, queued details belong to that finished route.
        if (u != null && (u.state == 0 || u.state == 2)) return;
        LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
        if (m == null) {
            m = new LinkedHashMap<Integer, Maneuver>();
            maneuvers.put(id, m);
        }
        Maneuver x = m.get(index);
        if (x != null) x.read(index, id, msg);
        else m.put(index, new Maneuver(index, id, msg));
        x = m.get(index);
        if (x.exitSupplied) x.hudGeometryGeneration = u == null ? 0 : u.hudPlanGeneration;
        Dipo.hudTrace("maneuver component=" + id + " index=" + index + " type=" + x.type
                + " side=" + x.side + " junction=" + x.junction + " exit=" + x.exit
                + " distance=" + x.distance + " linkedLane=" + x.linkedLane
                + " exitKnown=" + x.exitSupplied + " roadChars=" + (x.afterRoad == null ? 0 : x.afterRoad.length())
                + " hudExitKnown=" + x.hudExitKnown + " hudExit=" + x.hudExit);
        // A route list may precede its details. Render only a known active list.
        if (u == null || u.state == 0 || u.list == null) return;
        for (int listed : u.list) {
            if (listed == index) {
                turn(id, u);
                return;
            }
        }
        // The immediately adjacent plan entry can supply the real following
        // turn when the selected second entry has no directional geometry.
        if (u.list.length > 1 && index == u.list[1] + 1
                && (index == hudFollowing(u, m) || (hudComponent == id && hudFollowing == index))) turn(id, u);
    }

    /** LaneGuidance (the build's applyLanes): up to 8 lanes as the cluster's 0xF1 frame */
    void lanes(Iap2NotiMsg msg) {
        Iap2NotiMsg.Iap2ObjGroup g = msg == null ? null : msg.getItemGroup();
        if (g == null) {
            Dipo.hudTrace("lane-input missing-root");
            return;
        }
        int index = -1;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null || it.getItemId() != 1) continue;
            // Vendor Parcel readers leave zero-count backing arrays null; their
            // getters dereference those arrays. Check count before every getter.
            if (index >= 0 || it.getItemParamType() != 5 || it.getItemObjCount() != 1) {
                Dipo.hudTrace("lane-input invalid-index");
                return;
            }
            int[] v = it.getItemIntArray();
            if (v == null || v.length != 1 || v[0] < 0 || v[0] > 65535) {
                Dipo.hudTrace("lane-input invalid-index");
                return;
            }
            index = v[0];
        }
        int[] status = new int[8], a = new int[8], b = new int[8], c = new int[8];
        int n = 0;
        java.util.ArrayList<Iap2NotiMsg.Iap2ObjGroup> laneGroups = new java.util.ArrayList<Iap2NotiMsg.Iap2ObjGroup>();
        boolean hasLaneGroups = false;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it != null && it.getItemParamType() == 13 && it.getItemId() == 2) {
                if (it.getItemObjCount() == 0) {
                    hasLaneGroups = true; // Explicit clear; do not invoke the vendor getter.
                    continue;
                }
                if (it.getItemObjCount() < 0) {
                    Dipo.hudTrace("lane-input invalid-group-count");
                    return;
                }
                Iap2NotiMsg.Iap2ObjGroup[] one = it.getItemGroupArray();
                if (one != null) {
                    hasLaneGroups = true;
                    for (Iap2NotiMsg.Iap2ObjGroup lane : one) if (laneGroups.size() < 8) laneGroups.add(lane);
                }
            }
        }
        if (!hasLaneGroups) {
            Dipo.hudTrace("lane-input missing-groups");
            return;
        }
        Iap2NotiMsg.Iap2ObjGroup[] lanes = laneGroups.toArray(new Iap2NotiMsg.Iap2ObjGroup[laneGroups.size()]);
        if (lanes != null) {
            for (int k = 0; k < lanes.length && n < 8; k++) {
                int[] one = laneOne(lanes[k]);
                Dipo.hudTrace("lane n=" + n + " input=" + numericFields(lanes[k])
                        + " output=" + java.util.Arrays.toString(one));
                status[n] = one[0];
                a[n] = one[1];
                b[n] = one[2];
                c[n] = one[3];
                n++;
            }
        }
        if (index < 0) {
            Dipo.hudTrace("lane-input missing-index");
            return;
        }
        byte[] f = new byte[81];
        f[0] = (byte) 0xF1;
        f[1] = (byte) n;
        for (int k = 0; k < n; k++) {
            f[k + 3] = (byte) status[k];
            f[k * 3 + 19] = (byte) a[k];
            f[k * 3 + 20] = (byte) b[k];
            f[k * 3 + 21] = (byte) c[k];
        }
        int[] ids = componentIds(msg);
        if (ids == null) ids = laneComponent < 0 ? new int[0] : new int[] { laneComponent };
        for (int id : ids) {
            if (id < 0) continue;
            Update u = updates.get(id);
            LinkedHashMap<Integer, byte[]> events = laneFrames.get(id);
            if (events == null) {
                events = new LinkedHashMap<Integer, byte[]>();
                laneFrames.put(id, events);
            }
            if (!events.containsKey(index) && events.size() >= MAX_LANE_EVENTS) {
                Integer oldest = null;
                for (Integer key : events.keySet()) {
                    if (u == null || !u.laneIndexKnown || key.intValue() != u.laneIndex) { oldest = key; break; }
                }
                if (oldest != null) events.remove(oldest);
            }
            events.put(index, f.clone());
            Dipo.hudTrace("lane-input cached component=" + id + " index=" + index + " count=" + n);
            if (id == laneComponent && u != null) syncLanes(id, u);
        }
    }

    static byte[] emptyLanes() {
        byte[] f = new byte[81];
        f[0] = (byte) 0xF1;
        return f;
    }

    /** Phone visibility owns this display. Unknown visibility cannot expose old arrows. */
    void syncLanes(int id, Update u) {
        boolean active = u.state != 0 && u.state != 2;
        if (!active) laneFrames.remove(id);
        LinkedHashMap<Integer, byte[]> events = laneFrames.get(id);
        byte[] frame = active && u.lanesShown && u.laneIndexKnown && events != null ? events.get(u.laneIndex) : null;
        sendLanes(frame == null ? emptyLanes() : frame, false, id,
                !active ? "inactive" : !u.lanesShownKnown ? "unknown" : !u.lanesShown ? "hidden" : !u.laneIndexKnown ? "unknown-index" : frame == null ? "awaiting-data" : "shown");
    }

    void sendLanes(byte[] frame, boolean force, int id, String reason) {
        long now = SystemClock.elapsedRealtime();
        if (!force && java.util.Arrays.equals(frame, laneSent) && now - laneSentAt < 2000L) return;
        cluster.laneInfo(frame);
        laneSent = frame.clone();
        laneSentAt = now;
        Dipo.hudTrace("F1 component=" + id + " count=" + (frame[1] & 0xff) + " reason=" + reason
                + " states=" + java.util.Arrays.toString(java.util.Arrays.copyOfRange(frame,3,3+(frame[1]&0xff))));
    }

    /** Bounded numeric fields only: no road names, coordinates or phone identifiers. */
    static String numericFields(Iap2NotiMsg.Iap2ObjGroup g) {
        StringBuilder out = new StringBuilder();
        if (g == null) return "null";
        for (int k = 0; k < g.getItemCnt() && k < 16; k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null) continue;
            int type = it.getItemParamType();
            if (type != 1 && type != 5 && type != 6 && type != 8) continue;
            if (it.getItemObjCount() <= 0) continue;
            int[] v = it.getItemIntArray();
            if (v == null) continue;
            out.append(it.getItemId()).append(':').append(type).append('[');
            for (int j = 0; j < v.length && j < 16; j++) out.append(j == 0 ? "" : ",").append(v[j]);
            out.append("];");
        }
        return out.toString();
    }

    /** one lane: {stock state (1 recommended, 3 other), up to 3 arrow icons} */
    static int[] laneOne(Iap2NotiMsg.Iap2ObjGroup g) {
        int[] r = new int[4];
        // November 9 stock capture: outer turns 3, recommended straights 1.
        // The vehicle supplies the physical HUD/cluster palettes for those states.
        r[0] = 3;
        if (g == null) return r;
        int[] angles = null, highlight = null;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null) continue;
            int t = it.getItemParamType();
            if (t != 5 && t != 8) continue;
            if (it.getItemObjCount() <= 0) continue;
            int[] v = it.getItemIntArray();
            if (v == null || v.length == 0) continue;
            int id = it.getItemId();
            if (id == 1) {
                if (v[0] == 2) r[0] = 1;
            } else if (id == 2) {
                angles = v;
            } else if (id == 3) {
                highlight = v;
            }
        }
        if (highlight != null && highlight.length > 0) r[0] = 1;
        // Apple Maps can place a recommended direction only in field 3, with
        // no field-2 angles. Draw those directions first, then other branches.
        int n = laneIcons(r, 0, highlight);
        laneIcons(r, n, angles);
        return r;
    }

    static int laneIcons(int[] lane, int count, int[] angles) {
        if (angles == null) return count;
        for (int angle : angles) {
            if (count == 3) break;
            int icon = laneIcon(angle);
            boolean duplicate = icon == 0;
            for (int k = 0; k < count; k++) if (lane[k + 1] == icon) duplicate = true;
            if (!duplicate) lane[++count] = icon;
        }
        return count;
    }

    /** a lane angle (degrees, right positive) as the cluster's arrow: 1 straight, 2-4 right, 5 back, 6-8 left */
    static int laneIcon(int a) {
        if (a > 180) a -= 360;
        else if (a < -180) a += 360;
        if (a >= 0) {
            if (a <= 22) return 1;
            if (a <= 67) return 2;
            if (a <= 112) return 3;
            return a <= 157 ? 4 : 5;
        }
        a = -a;
        if (a <= 22) return 1;
        if (a <= 67) return 8;
        if (a <= 112) return 7;
        return a <= 157 ? 6 : 5;
    }

    /** the iAP2 session ended (DiPO e/u.disconnected) */
    void disconnected() {
        Dipo.hudTrace("reset reason=disconnect");
        reset();
        hudBlank();
        source = null;
    }

    /** route guidance stopped (DiPO e/u.nS: the car's navigation took turn-by-turn back, or state 0) */
    void stop() {
        stop("explicit-stop");
    }

    void stop(String reason) {
        Dipo.hudTrace("reset reason=" + reason);
        // Native navigation now owns guidance. Do not let a queued detail or a
        // later route reusing an index resurrect this route's cached maneuvers.
        reset();
        hudBlank();
    }

    /** forget every component's state and maneuvers (DiPO e/u.nT) */
    void reset() {
        // Release our schematic before returning route ownership to native nav.
        if (clusterSchematic) cluster.tbtInfo(0, 0, 0f, 0f, -1, 0, 0, 0, -1);
        clusterSchematic = false;
        clearApproach();
        clearRouteContext();
        routeActive(false);
        laneFrames.clear();
        for (LinkedHashMap<Integer, Maneuver> m : maneuvers.values()) {
            if (m != null) m.clear();
        }
        for (Update u : updates.values()) {
            if (u != null) u.clear();
        }
    }

    /** SourceName (item 19) changed: clear the old app's leftovers and have the phone resend */
    void sourceCheck(Iap2NotiMsg msg) {
        Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
        if (g == null) return;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null || it.getItemId() != 19 || it.getItemParamType() != 10 || it.getItemObjCount() <= 0) continue;
            String[] v = it.getItemStringArray();
            String s = v == null || v.length == 0 ? null : v[0];
            if (s == null || s.length() == 0) continue;
            String was = source;
            source = s;
            if (was == null || was.equals(s)) return;
            Dipo.hudTrace("reset reason=source-change");
            Dipo.log("CarPlay: route guidance source changed from " + was + " to " + s + ", clearing turn-by-turn");
            reset();
            hudBlank();
            if (restart != null) new Thread(restart, "hmgapi-cp-rg-restart").start();
            return;
        }
    }

    /** one component's update to the cluster (DiPO e/u.a(Integer, Object), with the build's changes) */
    void turn(Integer id, Update u) {
        if (id < 0 || u == null) return;
        if (u.state == 0) {
            Dipo.hudTrace("route-stop component=" + id + " state=0");
            stop("route-state-zero");
            return;
        }
        // Loading/locating/rerouting still belong to the phone. Mere TBT mode
        // ownership, without an actual route, does not own the idle cluster.
        boolean active = u.state != 2;
        for (Update other : updates.values()) {
            if (other != null && other.state != 0 && other.state != 2) active = true;
        }
        if (active) routeActive(true);
        if (laneComponent != id) {
            laneComponent = id;
            laneSent = null;
        }
        try {
            renderTurn(id, u);
        } finally {
            // Send the lane visibility result after normal turn output, even when
            // details are missing or the current maneuver has no supported icon.
            try {
                syncLanes(id, u);
            } finally {
                // Release after the final arrival output. Otherwise the
                // stock compass replay could race ahead of that last turn.
                if (u.state == 2) u.clearTripContext();
                if (!active) {
                    clearRouteContext();
                    routeActive(false);
                }
            }
        }
    }

    void renderTurn(Integer id, Update u) {
        boolean tripHandled = false;
        try {
            int[] list = u.list == null ? null : u.list.clone();
            int unit = u.nextUnitsKnown ? phoneDistanceUnit(u.nextUnits) : Cluster.UNIT_M;
            float dist = primaryDistance(u, unit);
            if (u.state != 2) sendDestination(id, u);
            Dipo.hudTrace("cluster-context component=" + id + " state=" + u.state
                    + " destinationKnown=" + u.destinationKnown
                    + " destinationChars=" + (u.destination == null ? 0 : u.destination.length())
                    + " remainingRawMetres=" + (u.distLeftKnown ? u.distLeft : -1)
                    + " remainingDisplayMetres=" + (u.distLeftDisplayKnown && u.tripUnitsKnown
                        ? displayMetres(u.distLeftStr, phoneDistanceUnit(u.distLeftUnits)) : Float.NaN));
            LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
            Dipo.hudTrace("route component=" + id + " state=" + u.state
                    + " maneuverState=" + u.maneuverState + " maneuverCount=" + u.count
                    + " lanesShown=" + u.lanesShown + " lanesKnown=" + u.lanesShownKnown
                    + " laneIndex=" + u.laneIndex + " laneCount=" + u.laneCount + " list="
                    + (list == null ? "null" : java.util.Arrays.toString(java.util.Arrays.copyOf(list, Math.min(8, list.length)))));
            if (m == null || list == null || list.length == 0) {
                clearUnsupportedTurn(m == null ? "missing-maneuvers" : list == null ? "missing-selection" : "empty-selection");
                return;
            }
            Maneuver x = m.get(list[0]);
            if (x == null) {
                clearUnsupportedTurn("awaiting-primary");
                return;
            }
            int icon = icon(x.type, x.side, x.junction, x.exit, u.state);
            int geometryAngle = x.exit;
            boolean geometryKnown = x.exitSupplied;
            boolean retained = false;
            // Left/right icons depend on the angle. A reset default of zero is not
            // evidence for the roundabout icon whose actual exit angle is zero.
            if (!x.exitSupplied && (x.junction == 1
                    || ((x.type == 1 || x.type == 2) && hudComponent == id && hudCurrent == list[0]
                        && hudManeuverType == x.type && hudManeuverSide == x.side
                        && hudManeuverJunction == x.junction))) icon = 0;
            cluster.crossroadName(x.afterRoad);
            if (icon == 0) {
                // A partial detail update can omit the angle for the same primary.
                // Keep its already valid instruction; never keep a different primary.
                if (x.exitSupplied || x.type == 0 || hudComponent != id || hudCurrent != list[0]
                        || hudManeuverType != x.type || hudManeuverSide != x.side || hudManeuverJunction != x.junction
                        || u.state == 2) {
                    clearUnsupportedTurn("unsupported-primary");
                    return;
                }
                icon = hudType;
                geometryAngle = x.hudExit;
                geometryKnown = x.hudExitKnown;
                retained = true;
            }
            if (u.state == 2 && icon != 3) {
                clearUnsupportedTurn("arrived");
                return;
            }
            boolean distanceKnown = !Float.isNaN(dist);
            boolean rawGeometryDistance = u.nextRawKnown && u.distNext >= 0;
            float mAway = rawGeometryDistance ? u.distNext : metres(dist, unit);
            int shape = clusterShape(x.type, x.side, x.junction, geometryAngle, geometryKnown, mAway, u.state);
            float direction = shape == 0 ? 0f : (geometryAngle + 360) % 360;
            // Stock type 13/32 renders the cluster schematic; F2 keeps the proven
            // compact HUD icon. No extra branches or exit ordinal are invented.
            int bar = shape == 0 ? 0 : clusterBar(mAway);
            int clusterUnit = shape == 0 ? unit : approachUnit(unit);
            float clusterDistance;
            if (shape == 0) { clearApproach(); clusterDistance = dist; }
            else clusterDistance = approachDistance(id, list[0], mAway, clusterUnit, rawGeometryDistance);
            // F2 is refreshed periodically for HUD reliability. Finish a schematic
            // refresh with its numeric/bar packet so a list/distance refresh cannot
            // be the final cluster instruction. No unproven PE fields are sent.
            if (shape == 0) cluster.tbtInfo(icon, 0, 0f,
                    distanceKnown ? clusterDistance : 0f, distanceKnown ? 0 : -1, 0, clusterUnit, 0, 0);
            sendHud(icon, id, u);
            if (icon >= 64) sendHudDist(dist, unit, id, u);
            if (shape != 0) cluster.tbtInfo(shape, 0, direction, clusterDistance, 0, 0, clusterUnit, bar, 0);
            clusterSchematic = shape != 0;
            Dipo.hudTrace("cluster-geometry type=" + (shape == 0 ? icon : shape) + " icon=" + icon
                    + " direction=" + direction + " metres=" + mAway + " bar=" + bar
                    + " distanceSource=" + (rawGeometryDistance ? "raw" : "display")
                    + " value=" + clusterDistance + " unit=" + clusterUnit + " barErr=0"
                    + " closingKmh=" + approachKmh + " ordering=" + (shape == 0 ? "compact" : "schematic-last"));
            if (u.state != 2) sendTrip(u);
            tripHandled = true;
            if (!retained) sendAhead(id, u);
        } finally {
            // Active route context must not depend on selected maneuver details
            // or on whether this firmware has an icon for that maneuver.
            if (!tripHandled && u.state != 2) sendTrip(u);
        }
    }

    /** Stock schematic examples: type 13 for supplied turn direction and type 32 for
     * right-traffic roundabouts. 500 m is this bridge's approach threshold, not
     * a decoded hardware limit. Missing distance/geometry retains compact output. */
    static int clusterShape(int type, int side, int junction, int angle, boolean known, float distance, int state) {
        if (!known || side != 0 || state != 1 || Float.isNaN(distance) || Float.isInfinite(distance)
                || distance < 0f || distance > 500f || angle < -180 || angle > 180) return 0;
        if (junction == 0 && angle != 0 && (type == 1 || type == 2 || type == 47 || type == 48 || type == 49 || type == 50)
                && icon(type, side, junction, angle, state) != 0) return 13;
        if (junction == 1 && type >= 28 && type <= 46) return 32;
        return 0;
    }

    /** The supported stock field is encoded in ten-percent steps. Use a fixed
     * 500-m approach scale; every positive remainder has a step, zero is empty. */
    static int clusterBar(float distance) {
        if (Float.isNaN(distance) || Float.isInfinite(distance) || distance <= 0f) return 0;
        if (distance >= 500f) return 100;
        return (int) Math.ceil(distance / 50f) * 10;
    }

    static float fromMetres(float value, int unit) {
        switch (unit) {
            case Cluster.UNIT_KM: return value / 1000f;
            case Cluster.UNIT_MI: return value / 1609f;
            case Cluster.UNIT_FT: return value * 3.28084f;
            case Cluster.UNIT_YD: return value * 1.09361f;
            default: return value;
        }
    }

    void clearApproach() {
        approachComponent = approachCurrent = approachUnit = -1;
        approachMetres = approachRounded = approachKmh = Float.NaN;
        approachAt = -1;
        approachRaw = false;
    }

    int approachUnit(int phoneUnit) {
        int owner = Car.distanceUnit(context);
        if (owner == Car.DISTANCE_MILES) return Cluster.UNIT_FT;
        if (owner == Car.DISTANCE_KM) return Cluster.UNIT_M;
        return phoneUnit == Cluster.UNIT_MI || phoneUnit == Cluster.UNIT_FT || phoneUnit == Cluster.UNIT_YD
                ? Cluster.UNIT_FT : Cluster.UNIT_M;
    }

    /** Conservative numeric ceiling: never show zero before the unrounded
     * countdown reaches zero. Rate is estimated from raw countdowns, not a
     * vehicle-speed sensor; missing/discontinuous samples use proximity alone. */
    static float roundedApproach(float metres, int unit, float closingKmh) {
        if (Float.isNaN(metres) || Float.isInfinite(metres) || metres < 0f) return Float.NaN;
        if (metres == 0f) return 0f;
        float value = fromMetres(metres, unit), step;
        boolean fast = !Float.isNaN(closingKmh) && closingKmh >= 50f;
        if (unit == Cluster.UNIT_FT) step = value > 500f || (fast && value >= 50f) ? 100f
                : value >= 50f ? 50f : value >= 10f ? 10f : value >= 5f ? 5f : 1f;
        else step = value > 150f || (fast && value >= 15f) ? 50f : value >= 10f ? 10f : 1f;
        float rounded = (float) Math.ceil((value - 0.001f) / step) * step;
        return Math.max(1f, rounded);
    }

    float approachDistance(int component, int current, float metres, int unit, boolean raw) {
        long now = SystemClock.elapsedRealtime();
        boolean same = approachComponent == component && approachCurrent == current && approachUnit == unit;
        long elapsed = now - approachAt;
        if (!same || !raw || !approachRaw || metres > approachMetres || elapsed > 3000L) approachKmh = Float.NaN;
        else if (elapsed >= 500L && approachAt >= 0 && !Float.isNaN(approachMetres)) {
            float rate = (approachMetres - metres) * 3600f / elapsed;
            approachKmh = rate >= 0f && rate <= 200f
                    ? Float.isNaN(approachKmh) ? rate : (approachKmh + rate) / 2f : Float.NaN;
        }
        float rounded = roundedApproach(metres, unit, approachKmh);
        // A speed-band transition must not increase the number while distance
        // decreases. Real increases/reroutes may reset it; never carry a prior turn.
        if (same && metres <= approachMetres && !Float.isNaN(approachRounded)) rounded = Math.min(rounded, approachRounded);
        approachComponent = component; approachCurrent = current; approachUnit = unit;
        approachMetres = metres; approachRounded = rounded; approachAt = now;
        approachRaw = raw;
        return rounded;
    }

    void sendDestination(int id, Update u) {
        if (!u.destinationKnown || u.destination == null) return;
        if (destinationComponent == id && u.destination.equals(destinationSent)) return;
        cluster.destinationName(0, u.destination);
        destinationComponent = id;
        destinationSent = u.destination;
        Dipo.hudTrace("cluster-destination component=" + id + " chars=" + u.destination.length());
    }

    void clearRouteContext() {
        if (destinationSent != null && destinationSent.length() > 0) cluster.destinationName(0, "");
        destinationSent = null;
        destinationComponent = -1;
        if (tripSent) cluster.tbtSubInfo(0f, -1, 0, 0, 0, 0, 0, 0f, -1, 0);
        if (tripSent) Dipo.hudTrace("cluster-trip clear");
        tripSent = false;
    }

    /**
     * The cluster icon for a maneuver: iAP2 ManeuverType, driving side (0 right-hand
     * traffic, 1 left), junction type (0 intersection, 1 roundabout) and exit angle, as
     * the turn-by-turn build maps them (0: nothing to show).
     */
    static int icon(int type, int side, int junction, int exit, int state) {
        // Stock Iap2Const.ManeuverType encodes roundabout exit ordinals 1..19
        // as 28..46. The stock icons here encode geometry, not that ordinal.
        // Quantize supplied right-traffic geometry independently of the ordinal.
        if (type >= 28 && type <= 46 && side == 0 && junction == 1) return roundaboutIcon(exit);
        switch (type) {
            // Ordinary left/right types carry the direction even when the optional
            // exit angle is absent (the parser resets it to zero on every update).
            case 1:   // left turn
                if (junction == 0 && exit == -120) return 69;
                if ((side == 0 || side == 1) && junction == 0 && (exit == -90 || exit == 0)) return 70;
                if (junction == 0 && exit == -60) return 71;
                if (junction == 0 && exit == -45) return 145;
                if (side == 1 && junction == 1 && exit == -120) return 104;
                if (side == 1 && junction == 1 && exit == -90) return 105;
                if (side == 1 && junction == 1 && exit == -30) return 107;
                return 0;
            case 2:   // right turn
                if (junction == 0 && exit == 60) return 66;
                if ((side == 0 || side == 1) && junction == 0 && (exit == 90 || exit == 0)) return 67;
                if (junction == 0 && exit == 120) return 68;
                if (side == 0 && junction == 1 && exit == 0) return 96;
                if (side == 1 && junction == 1 && exit == 0) return 97;
                if (side == 1 && junction == 1 && exit == 90) return 99;
                if (side == 1 && junction == 1 && exit == 120) return 100;
                if (side == 1 && junction == 1 && exit == 180) return 102;
                if (side == 0 && junction == 0 && exit == 45) return 144;
                return 0;
            case 3:   // straight
                return (side == 0 || side == 1) && junction == 0 && exit == 0 ? 65 : 0;
            case 4:   // U-turn
                if (side == 0 && junction == 0) return 72;
                if (side == 1 && junction == 0) return 73;
                return 0;
            case 5:
            case 9:
                return side == 0 && junction == 0 && exit == 0 ? 172 : 0;
            case 12: {   // arrive; state 2 (arrived): 3
                int i = side == 0 && junction == 0 ? 112 : 0;
                return state == 2 ? 3 : i;
            }
            case 11:  // departure: NO_INFO, not a turn inferred from missing geometry
                return (side == 0 || side == 1) && junction == 0 && exit == 0 ? 1 : 0;
            case 15: return side == 0 && junction == 0 && exit == 0 ? 176 : 0;
            case 19: return side == 0 && junction == 1 && exit == -180 ? 102 : 0;
            case 22: return side == 0 && junction == 0 && exit == 0 ? 82 : 0;
            case 23: return side == 0 && junction == 0 && exit == 0 ? 81 : 0;
            case 24: return side == 0 && junction == 0 && exit == 0 ? 114 : 0;
            case 25: return side == 0 && junction == 0 && exit == 0 ? 113 : 0;
            case 31:
                if (side == 1 && junction == 1 && exit == 0) return 96;
                return 0;
            case 47:
                if (junction == 0 && exit <= -135 && exit >= -150) return 69;
                if (junction == 0 && exit == -30) return 71;
                return 0;
            case 48:
                if (junction == 0 && exit == 30) return 66;
                if (junction == 0 && exit >= 135 && exit <= 150) return 68;
                return 0;
            case 49: return junction == 0 && exit == -45 ? 71 : 0;
            case 50: return junction == 0 && exit == 45 ? 66 : 0;
            case 52: return side == 0 && junction == 0 && exit == 0 ? 174 : 0;
            case 53: return side == 0 && junction == 0 && exit == 0 ? 173 : 0;
            default: return 0;
        }
    }

    /** Nearest existing canonical right-traffic symbol for a signed exit angle.
     * Sector boundaries are midpoints of the prior canonical directions:
     * -180, -135, -90, -45, 0, 90, 135 (and +180 wraps to -180).
     * The exact 0/90 tie at +45 chooses positive rotation. These are bridge
     * quantization rules, not claimed hardware thresholds or exit ordinals. */
    static int roundaboutIcon(int exit) {
        if (exit < -180 || exit > 180) return 0;
        if (exit <= -158 || exit >= 158) return 102;
        if (exit <= -113) return 104;
        if (exit <= -68) return 105;
        if (exit <= -23) return 107;
        if (exit < 45) return 97;
        if (exit <= 112) return 99;
        return 100;
    }

    /** an upcoming turn's small icon (HUD list and turn list): 64 none, 65 straight, 66-68 right, 69-71 left, 72/73 U-turn */
    static int aheadIcon(int type, int side, int junction, int exit) {
        if (type == 1) {
            if (exit == -120 && junction == 0) return 69;
            if (exit == -90 && side == 0 && junction == 0) return 70;
            if (exit == -60 && junction == 0) return 71;
            return 70;
        }
        if (type == 2) {
            if (exit == 60 && junction == 0) return 66;
            if (exit == 90 && side == 0 && junction == 0) return 67;
            if (exit == 120 && junction == 0) return 68;
            return 67;
        }
        if (type == 3) return 65;
        if (type == 4) return side == 0 ? 72 : 73;
        return 64;
    }

    /** A zero-angle intersection left/right entry is not a supported independent
     * turn. Returned routes select it temporarily, then skip it on promotion.
     * Only resolve the observed contiguous-plan pattern: selected [n,n+1],
     * followed by n+2 with the same turn direction and supported exit geometry.
     * Never walk cache insertion order, cross components, or alter the phone's
     * primary selection. Ambiguous explicit-zero geometry clears the secondary;
     * omitted geometry retains the existing wire fallback if resolution fails.
     * This bounded mapping is a vehicle-test candidate, not a general iAP2 role
     * flag: physical distance/turn association still needs acceptance. */
    static int hudFollowing(Update u, LinkedHashMap<Integer, Maneuver> m) {
        if (u == null || u.list == null || u.list.length < 2 || m == null) return -1;
        int selected = u.list[1];
        Maneuver y = m.get(selected);
        if (y == null) return -1;
        if ((y.type != 1 && y.type != 2) || y.junction != 0 || (y.hudExitKnown && y.hudExit != 0)) return selected;
        int primary = u.list[0], adjacent = selected + 1;
        Maneuver x = m.get(primary), z = m.get(adjacent);
        if (primary < 0 || selected != primary + 1 || adjacent >= u.count
                || x == null || z == null || z.type != y.type || z.side != y.side
                || z.junction != 0 || !z.hudExitKnown || !x.hudExitKnown
                || z.hudExit == 0 || x.hudExit == 0
                || z.hudGeometryGeneration != u.hudPlanGeneration || x.hudGeometryGeneration != u.hudPlanGeneration
                || icon(x.type, x.side, x.junction, x.hudExit, u.state) < 64
                || icon(z.type, z.side, z.junction, z.hudExit, u.state) < 64) return y.hudExitKnown ? -1 : selected;
        return adjacent;
    }

    /** F2: count, current icon, next icon, unused third icon, road (UTF-16LE). */
    void sendHud(int type, Integer id, Update u) {
        if (type < 64) {
            clearTurnHud("non-directional-icon");
            return;
        }
        int next = 0;
        String road = "";
        LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
        int[] list = u == null ? null : u.list;
        int following = hudFollowing(u, m);
        if (m != null && list != null && list.length > 0) {
            Maneuver x = m.get(list[0]);
            if (x != null) {
                if (x.afterRoad != null) road = x.afterRoad;
                hudManeuverType = x.type;
                hudManeuverSide = x.side;
                hudManeuverJunction = x.junction;
            }
            if (list.length > 1) {
                Maneuver y = m.get(following);
                // F2 has no secondary distance. Only encode supported next-turn types.
                if (y != null && y.type >= 1 && y.type <= 4) next = aheadIcon(y.type, y.side, y.junction,
                        following == list[1] ? y.exit : y.hudExit);
            }
        }
        int current = list == null || list.length == 0 ? -1 : list[0];
        long now = SystemClock.elapsedRealtime();
        if (hudComponent == id && hudCurrent == current && hudFollowing == following
                && hudType == type && hudNext == next && hudRoad != null && hudRoad.equals(road)
                && now - hudSentAt < 2000L) return;
        byte[] r;
        try {
            r = road.getBytes("UTF-16LE");
        } catch (java.io.UnsupportedEncodingException e) {
            return;
        }
        int n = Math.min(r.length, 78);
        byte[] f = new byte[n + 5];
        f[0] = (byte) 0xF2;
        f[1] = (byte) (next != 0 ? 2 : 1);
        f[2] = (byte) type;
        f[3] = (byte) next;
        System.arraycopy(r, 0, f, 5, n);
        Dipo.log("CarPlay: hud list type " + type + " next " + next);
        cluster.turnListNew(f);
        Dipo.hudTrace("F2 component=" + id + " current=" + current + " following=" + following
                + " count=" + (next != 0 ? 2 : 1) + " primary=" + type + " next=" + next + " roadBytes=" + n
                + " wireNext=" + (list == null || list.length < 2 ? -1 : list[1]));
        hudType = type;
        hudNext = next;
        hudRoad = road;
        hudComponent = id;
        hudCurrent = current;
        hudFollowing = following;
        hudSentAt = now;
    }

    /** Release a previously rendered cluster instruction when its replacement is
     * unavailable. Keep active destination/trip context and lane visibility. */
    void clearUnsupportedTurn(String reason) {
        clearApproach();
        clusterSchematic = false;
        if (hudRoad != null) {
            cluster.tbtInfo(0, 0, 0f, 0f, -1, 0, 0, 0, -1);
            cluster.crossroadName("");
        }
        clearTurnHud(reason);
    }

    /** Clear obsolete HUD output without discarding lane visibility or pending lanes.
     * Also used after the cluster's arrival icon, which must remain visible. */
    void clearTurnHud(String reason) {
        if (hudRoad == null) return;
        cluster.tbtSubInfo2C(0, 0, 0, 0, 0, 0, 0, 0, 0);
        hudType = -1;
        hudNext = 0;
        hudRoad = null;
        hudComponent = hudCurrent = hudFollowing = -1;
        hudManeuverType = hudManeuverSide = hudManeuverJunction = -1;
        hudSentAt = 0;
        byte[] empty = { (byte) 0xF2 };
        cluster.turnListNew(empty);
        cluster.turnListNew(empty);
        Dipo.hudTrace("turn-clear reason=" + reason);
    }

    /** Encode one of the stock API's three (unit, fraction, integer) triplets. */
    static void hudDistance(float f, int unit, int[] fields, int offset) {
        if (Float.isNaN(f) || Float.isInfinite(f) || f < 0f || unit < 0 || unit > 4) return;
        int whole = (int) Math.min(f, 4095f), tenths = 0;
        if (unit == Cluster.UNIT_KM || unit == Cluster.UNIT_MI) {
            tenths = (int) ((f - whole) * 10f + 0.5f);
            if (tenths >= 10) {
                if (whole < 4095) { whole++;tenths=0; }
                else tenths=9;
            }
        }
        fields[offset]=unit;fields[offset+1]=tenths;fields[offset+2]=whole;
    }

    static int phoneDistanceUnit(int unit) {
        switch (unit) {
            case 0:return Cluster.UNIT_KM;
            case 1:return Cluster.UNIT_MI;
            case 2:return Cluster.UNIT_M;
            case 3:return Cluster.UNIT_YD;
            case 4:return Cluster.UNIT_FT;
            default:return -1;
        }
    }

    static float metres(float value, int unit) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < 0f) return Float.NaN;
        switch (unit) {
            case Cluster.UNIT_M: return value;
            case Cluster.UNIT_KM: return value * 1000f;
            case Cluster.UNIT_MI: return value * 1609f;
            case Cluster.UNIT_FT: return value / 3.28084f;
            case Cluster.UNIT_YD: return value / 1.09361f;
            default: return Float.NaN;
        }
    }

    static float displayMetres(String text, int unit) {
        try { return metres(Float.parseFloat(text),unit); }
        catch (RuntimeException ignored) { return Float.NaN; }
    }

    /** Respect route-distance freshness, including raw-only updates and promotion.
     * Formatted values keep the phone's exact presentation when both are supplied.
     * Missing/invalid distance is unavailable, never an invented zero countdown. */
    static float primaryDistance(Update u, int unit) {
        if (u.nextDisplayKnown && u.nextUnitsKnown && !Float.isNaN(displayMetres(u.nextStr, unit))) {
            return Float.parseFloat(u.nextStr);
        }
        if (!u.nextRawKnown || u.distNext < 0) return Float.NaN;
        float value = u.distNext;
        if (unit == Cluster.UNIT_KM) return value / 1000f;
        if (unit == Cluster.UNIT_MI) return value / 1609f;
        if (unit == Cluster.UNIT_FT) return value * 3.28084f;
        if (unit == Cluster.UNIT_YD) return value * 1.09361f;
        return value;
    }

    /** Prefer the phone's dedicated between-maneuver display value for point 2.
     * Raw fallback and physical field interpretation remain visible in diagnostics.
     * Point 1 uses the resolved primary countdown; point 3 stays unused. */
    void sendHudDist(float f, int unit, Integer id, Update u) {
        int[] fields=new int[9];
        hudDistance(f,unit,fields,0);
        int following=-1, roadBytes=0;
        float current=Float.NaN, interval=Float.NaN, total=Float.NaN;
        float rawFollowing=Float.NaN, displayFollowing=Float.NaN;
        int selectedPhoneUnit=-1;
        int currentSource=0, intervalSource=0;
        LinkedHashMap<Integer,Maneuver> m=maneuvers.get(id);
        int resolvedFollowing=hudFollowing(u,m);
        if (u != null && u.list != null && u.list.length>1 && hudNext>0
                && hudComponent==id && resolvedFollowing>=0 && hudFollowing==resolvedFollowing && m!=null) {
            Maneuver next=m.get(resolvedFollowing);
            if (next!=null) {
                following=resolvedFollowing;
                roadBytes=next.afterRoad==null?0:next.afterRoad.length()*2;
                currentSource=u.nextRawKnown && u.distNext>=0 ? 1 : u.nextDisplayKnown ? 2 : 0;
                current=u.nextRawKnown && u.distNext>=0 ? u.distNext
                        : u.nextDisplayKnown ? displayMetres(u.nextStr,phoneDistanceUnit(u.nextUnits)) : Float.NaN;
                rawFollowing=next.distanceKnown && next.distance>=0 ? next.distance : Float.NaN;
                selectedPhoneUnit=phoneDistanceUnit(next.distUnits);
                displayFollowing=next.distUnitsKnown ? displayMetres(next.distStr,selectedPhoneUnit) : Float.NaN;
                boolean displayValid=!Float.isNaN(displayFollowing) && !Float.isInfinite(displayFollowing);
                boolean useDisplay=displayValid && rawFollowing!=0f;
                // The phone supplies a dedicated between-maneuver display value.
                // Respect that presentation when raw and formatted inputs differ;
                // never replace it with the primary countdown or a guessed delta.
                intervalSource=useDisplay ? 2 : !Float.isNaN(rawFollowing) ? 1 : 0;
                interval=useDisplay ? displayFollowing : rawFollowing;
                if (Float.isNaN(current) || Float.isInfinite(current)) currentSource=0;
                if (Float.isNaN(interval) || Float.isInfinite(interval)) intervalSource=0;
                total=current+interval;
                float value=interval;
                int owner=Car.distanceUnit(context), nextUnit;
                if (owner==Car.DISTANCE_MILES && useDisplay && (selectedPhoneUnit==Cluster.UNIT_FT
                        || selectedPhoneUnit==Cluster.UNIT_YD || selectedPhoneUnit==Cluster.UNIT_MI)) nextUnit=selectedPhoneUnit;
                else if (owner==Car.DISTANCE_KM && useDisplay && (selectedPhoneUnit==Cluster.UNIT_M
                        || selectedPhoneUnit==Cluster.UNIT_KM)) nextUnit=selectedPhoneUnit;
                else if (owner!=Car.DISTANCE_MILES && owner!=Car.DISTANCE_KM && useDisplay) nextUnit=selectedPhoneUnit;
                else if (owner==Car.DISTANCE_MILES) nextUnit=value<161?Cluster.UNIT_FT:Cluster.UNIT_MI;
                else if (owner==Car.DISTANCE_KM) nextUnit=value<1000?Cluster.UNIT_M:Cluster.UNIT_KM;
                else nextUnit=unit;
                if (nextUnit==Cluster.UNIT_KM) value/=1000f;
                else if (nextUnit==Cluster.UNIT_MI) value/=1609f;
                else if (nextUnit==Cluster.UNIT_FT) value*=3.28084f;
                else if (nextUnit==Cluster.UNIT_YD) value*=1.09361f;
                // Avoid an unnecessary feet->metres->feet truncation of the
                // phone's explicit integer display value (e.g. 250 -> 249 ft).
                if (useDisplay && nextUnit==selectedPhoneUnit) {
                    try { value=Float.parseFloat(next.distStr); } catch (RuntimeException ignored) {}
                }
                hudDistance(value,nextUnit,fields,3);
            }
        }
        cluster.tbtSubInfo2C(fields[0],fields[1],fields[2],fields[3],fields[4],fields[5],0,0,0);
        Dipo.hudTrace("2C unit="+fields[0]+" tenths="+fields[1]+" whole="+fields[2]
                +" following="+following+" nextUnit="+fields[3]+" nextTenths="+fields[4]
                +" nextWhole="+fields[5]+" nextRoadBytes="+roadBytes
                +" currentMetres="+current+" intervalMetres="+interval+" totalMetres="+total
                +" currentSource="+currentSource+" intervalSource="+intervalSource
                +" point2Metres="+interval+" distanceMode=interval-display-first"
                +" rawSelectedMetres="+rawFollowing+" displaySelectedMetres="+displayFollowing
                +" selectedPhoneUnit="+selectedPhoneUnit
                +" wireNext="+(u == null || u.list == null || u.list.length<2 ? -1 : u.list[1]));
    }

    /** HUD list and extras cleared (the build's sendHudBlank) */
    void hudBlank() {
        cluster.tbtSubInfo2C(0, 0, 0, 0, 0, 0, 0, 0, 0);
        laneFrames.clear();
        laneComponent = -1;
        sendLanes(emptyLanes(), true, -1, "clear");
        hudType = -1;
        hudNext = 0;
        hudRoad = null;
        hudComponent = hudCurrent = hudFollowing = -1;
        hudManeuverType = hudManeuverSide = hudManeuverJunction = -1;
        hudSentAt = 0;
        Dipo.hudTrace("clear");
        byte[] empty = { (byte) 0xF2 };
        cluster.turnListNew(empty);
        cluster.turnListNew(empty);
    }

    /**
     * Remaining distance and arrival time (updateTBTSubInfo). Distance in the owner's
     * units (Settings), else the phone's. Arrival: a clock time (seconds since 2001 or
     * 1970) or, failing that, now + the time remaining (up to a week).
     */
    void sendTrip(Update u) {
        float measured = u.distLeftKnown ? u.distLeft : u.distLeftDisplayKnown && u.tripUnitsKnown
                ? displayMetres(u.distLeftStr, phoneDistanceUnit(u.distLeftUnits)) : Float.NaN;
        boolean distanceKnown = !Float.isNaN(measured) && !Float.isInfinite(measured) && measured >= 0f;
        int owner = Car.distanceUnit(context), unit = Cluster.UNIT_M;
        float value = 0f;
        if (distanceKnown && measured > 0f) {
            int units = owner == Car.DISTANCE_KM ? (measured < 1000f ? 2 : 0)
                    : owner == Car.DISTANCE_MILES ? (measured < 161f ? 4 : 1)
                    : u.tripUnitsKnown ? u.distLeftUnits : measured < 1000f ? 2 : 0;
            unit = phoneDistanceUnit(units);
            if (unit < 0) unit = Cluster.UNIT_M;
            value = measured;
            if (unit == Cluster.UNIT_KM) value /= 1000f;
            else if (unit == Cluster.UNIT_MI) value /= 1609f;
            else if (unit == Cluster.UNIT_FT) value *= 3.28084f;
            else if (unit == Cluster.UNIT_YD) value *= 1.09361f;
        }
        // Preserve decimal precision until the final display rounding. An
        // explicit 0.5mi must not become 0.4mi through integer-metre truncation.
        int whole = (int) Math.min(value, 65535f), tenths = 0;
        if (unit == Cluster.UNIT_KM || unit == Cluster.UNIT_MI) {
            tenths = (int) ((value - whole) * 10f + 0.5f);
            if (tenths >= 10) { if (whole < 65535) { whole++;tenths=0; } else tenths=9; }
        }
        int hour = -1, minute = -1, timeType = -1, timeFormat;
        Calendar cal = null;
        long eta = u.eta;
        if (eta < 500000000L) {
            long left = u.timeLeft;
            if (left > 0 && left <= 604800L) {
                cal = Calendar.getInstance();
                cal.add(Calendar.SECOND, (int) left);
            }
        } else {
            if (eta < 1500000000L) eta += 978307200L;   // Apple's 2001 epoch
            cal = Calendar.getInstance();
            cal.setTimeInMillis(eta * 1000L);
        }
        if (cal != null) {
            hour = cal.get(Calendar.HOUR_OF_DAY);
            minute = cal.get(Calendar.MINUTE);
            timeType = 1;
            timeFormat = context == null || DateFormat.is24HourFormat(context) ? 2 : 1;
        } else {
            timeFormat = -1;
        }
        if (!distanceKnown && timeType < 0) {
            if (tripSent) {
                cluster.tbtSubInfo(0f, -1, 0, 0, 0, 0, 0, 0f, -1, 0);
                tripSent = false;
                Dipo.hudTrace("cluster-trip clear reason=unavailable");
            }
            return;
        }
        cluster.tbtSubInfo(whole + tenths / 10f, distanceKnown ? 0 : -1, unit, hour, minute, timeType, timeType < 0 ? -1 : timeFormat, 0f, -1, 0);
        tripSent = true;
        Dipo.hudTrace("cluster-trip metres=" + measured + " unit=" + unit
                + " value=" + (whole + tenths / 10f) + " distanceKnown=" + distanceKnown
                + " timeType=" + timeType);
    }

    /** up to 3 upcoming turns after this one (updateTPTurnList), distances in the owner's units */
    void sendAhead(Integer id, Update u) {
        int[] list = u == null ? null : u.list;
        if (id == null || list == null || list.length <= 1) return;
        LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
        if (m == null) return;
        int count = 0;
        for (int k = 1; k < list.length && count < 3; k++) {
            Maneuver x = m.get(list[k]);
            if (x != null && x.distance > 0) count++;
        }
        if (count == 0) return;
        int owner = Car.distanceUnit(context);
        Cluster.Turn[] turns = new Cluster.Turn[count];
        int n = 0;
        for (int k = 1; k < list.length && n < 3; k++) {
            Maneuver x = m.get(list[k]);
            if (x == null || x.distance <= 0 || n >= count) continue;
            Cluster.Turn t = new Cluster.Turn();
            t.type = aheadIcon(x.type, x.side, x.junction, x.exit);
            t.direction = 0;
            t.traffic = 0;
            t.no = n + 1;
            t.name = x.afterRoad == null ? "" : x.afterRoad;
            t.exitNumber = "";
            int d = x.distance;
            float f = d;
            int unit = Cluster.UNIT_M;
            if (owner == Car.DISTANCE_KM) {
                if (d >= 1000) {
                    unit = Cluster.UNIT_KM;
                    f = d / 1000f;
                }
            } else if (owner == Car.DISTANCE_MILES) {
                if (d < 161) {
                    unit = Cluster.UNIT_FT;
                    f = d * 3.28084f;
                } else {
                    unit = Cluster.UNIT_MI;
                    f = d / 1609f;
                }
            }
            if (f < 0f) f = 0f;
            if (f > 65535f) f = 65535f;
            t.distance = f;
            t.unit = unit;
            turns[n++] = t;
        }
        if (n > 0) cluster.turnList(turns);
    }

    /** one display component's route guidance state (DiPO e/c) */
    static final class Update {
        int state, maneuverState, distLeft, distLeftUnits, distNext, nextUnits, count, laneIndex, laneCount;
        int hudPlanGeneration;
        String road, destination, distLeftStr, nextStr, sourceName;
        long eta, timeLeft;
        int[] list;
        boolean visible, lanesShown, lanesShownKnown, laneIndexKnown, supports, nextRawKnown, nextDisplayKnown, nextUnitsKnown;
        boolean destinationKnown, distLeftKnown, distLeftDisplayKnown, tripUnitsKnown;

        /** as DiPO: a component id of 0 or less starts with nothing set (not even the defaults) */
        Update(int id, Iap2NotiMsg msg) {
            if (msg == null || id <= 0) return;
            clear();
            read(id, msg);
        }

        void clear() {
            state = 0;
            hudPlanGeneration = 0;
            maneuverState = 1;
            road = "";
            destination = "";
            eta = 0;
            timeLeft = 0;
            distLeft = 0;
            distLeftKnown = distLeftDisplayKnown = destinationKnown = tripUnitsKnown = false;
            distLeftStr = "";
            distLeftUnits = 0;
            distNext = 0;
            nextRawKnown = nextDisplayKnown = false;
            nextStr = "";
            nextUnits = 0;
            nextUnitsKnown = false;
            list = null;
            count = 0;
            visible = false;
            laneIndex = 0;
            laneIndexKnown = false;
            laneCount = 0;
            lanesShown = false;
            lanesShownKnown = false;
            sourceName = "";
            supports = false;
        }

        void clearTripContext() {
            destination = "";
            destinationKnown = distLeftKnown = distLeftDisplayKnown = tripUnitsKnown = false;
            distLeft = 0;
            distLeftStr = "";
            eta = timeLeft = 0;
        }

        /** the fields this message carries (by iAP2 type: 1/5 uint8/16, 2/6 uint32, 3/7 uint64, 8 enum, 9 bool, 10 string) */
        void read(int id, Iap2NotiMsg msg) {
            Iap2NotiMsg.Iap2ObjGroup g = msg == null ? null : msg.getItemGroup();
            if (g == null) return;
            int oldState=state;
            int oldPrimary=list==null || list.length==0 ? -1 : list[0];
            int[] displayList = repeatedInts(g, 13, false);
            if (displayList != null) list = displayList;
            int primary=list==null || list.length==0 ? -1 : list[0];
            if (oldPrimary!=primary) nextRawKnown=nextDisplayKnown=false;
            boolean rawChanged=false, textChanged=false, unitChanged=false;
            boolean tripRawChanged=false, tripTextChanged=false, tripUnitChanged=false;
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                int item = it.getItemId(), n = it.getItemObjCount();
                switch (it.getItemParamType()) {
                    case 1:
                    case 5: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        switch (item) {
                            case 13:
                                // Collected across all matching items above.
                                break;
                            case 14: count = v != null ? v[0] : 0; break;
                            case 16:
                                laneIndexKnown = v != null && n == 1 && v.length == 1 && v[0] >= 0 && v[0] <= 65535;
                                laneIndex = laneIndexKnown ? v[0] : 0;
                                break;
                            case 17: laneCount = v != null ? v[0] : 0; break;
                        }
                        break;
                    }
                    case 2:
                    case 6: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (v == null) break;
                        if (item == 7) { distLeft = v[0];distLeftKnown=v[0]>=0;tripRawChanged=true; }
                        else if (item == 10) { distNext = v[0];rawChanged=true;nextRawKnown=v[0]>=0; }
                        break;
                    }
                    case 3:
                    case 7: {
                        long[] v = n > 0 ? it.getItemLongArray() : null;
                        if (v == null) break;
                        if (item == 5) eta = v[0];
                        else if (item == 6) timeLeft = v[0];
                        break;
                    }
                    case 8: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (v == null) break;
                        switch (item) {
                            case 1: state = v[0]; break;
                            case 2: maneuverState = v[0]; break;
                            case 9: tripUnitChanged|=distLeftUnits!=v[0];distLeftUnits = v[0];tripUnitsKnown=v[0]>=0 && v[0]<=4;break;
                            case 12:
                                unitChanged |= nextUnits != v[0];
                                nextUnits = v[0];
                                nextUnitsKnown = v[0] >= 0 && v[0] <= 4;
                                break;
                        }
                        break;
                    }
                    case 9: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (v == null) break;
                        if (item == 15) visible = v[0] != 0;
                        else if (item == 18) {
                            lanesShown = v[0] != 0;
                            lanesShownKnown = true;
                        }
                        else if (item == 20) supports = v[0] != 0;
                        break;
                    }
                    case 10: {
                        String[] v = n > 0 ? it.getItemStringArray() : null;
                        if (v == null) break;
                        switch (item) {
                            case 3: road = v[0]; break;
                            case 4: destination = v[0];destinationKnown=v[0]!=null;break;
                            case 8: distLeftStr = v[0];distLeftDisplayKnown=true;tripTextChanged=true;break;
                            case 11: nextStr = v[0];textChanged=true;nextDisplayKnown=true;break;
                            case 19: sourceName = v[0]; break;
                        }
                        break;
                    }
                }
            }
            // The newest distance representation wins. Never combine a new display
            // countdown with an old raw value, or reuse the prior primary on promotion.
            if (textChanged && !rawChanged) nextRawKnown=false;
            if ((rawChanged || unitChanged) && !textChanged) nextDisplayKnown=false;
            if (tripTextChanged && !tripRawChanged) distLeftKnown=false;
            if ((tripRawChanged || tripUnitChanged) && !tripTextChanged) distLeftDisplayKnown=false;
            if (state==0 || state==2) nextRawKnown=nextDisplayKnown=false;
            // A speculative HUD plan association cannot reuse old-route geometry
            // after loading/rerouting, even when the phone reuses indices.
            if (state!=oldState && (state==3 || state==5)) hudPlanGeneration++;
        }
    }

    /** one maneuver (DiPO e/v) */
    static final class Maneuver {
        int index, type, distance, distUnits, side, junction, exit, linkedLane, hudExit, hudGeometryGeneration;
        String description, afterRoad, distStr;
        int[] angles;
        boolean exitSupplied, distanceKnown, distUnitsKnown, hudExitKnown;

        Maneuver(int index, int id, Iap2NotiMsg msg) {
            if (msg == null) return;
            clear();
            read(index, id, msg);
        }

        void clear() {
            index = -1;
            description = "";
            type = 0;
            afterRoad = "";
            distance = 0;
            distanceKnown = false;
            distStr = "";
            distUnits = 2;
            distUnitsKnown = false;
            side = 0;
            junction = 0;
            angles = null;
            exit = 0;
            exitSupplied = false;
            hudExit = 0;
            hudExitKnown = false;
            hudGeometryGeneration = -1;
            linkedLane = 0;
        }

        /** as DiPO, the exit angle resets with every update (it's only kept if this one carries it) */
        void read(int index, int id, Iap2NotiMsg msg) {
            Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
            int oldType=type, oldSide=side, oldJunction=junction;
            this.index = index;
            exit = 0;
            exitSupplied = false;
            boolean distanceChanged=false, distanceTextChanged=false, distanceUnitChanged=false;
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                int item = it.getItemId(), n = it.getItemObjCount();
                switch (it.getItemParamType()) {
                    case 1: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (item == 10) {
                            if (v != null) {
                                angles = new int[n];
                                for (int j = 0; j < n; j++) angles[j] = v[j];
                            } else {
                                angles = null;
                            }
                        } else if (item == 11 && v != null) {
                            exit = v[0];
                            exitSupplied = true;
                        }
                        break;
                    }
                    case 5: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (item == 12 && v != null) linkedLane = v[0];
                        break;
                    }
                    case 6: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (item == 5 && v != null) { distance = v[0];distanceChanged=true;distanceKnown=true; }
                        break;
                    }
                    case 8: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (v == null) break;
                        switch (item) {
                            case 3: type = v[0]; break;
                            case 7: distanceUnitChanged|=distUnits!=v[0];distUnits = v[0];distUnitsKnown=v[0]>=0 && v[0]<=4;break;
                            case 8: side = v[0]; break;
                            case 9: junction = v[0]; break;
                        }
                        break;
                    }
                    case 10: {
                        String[] v = n > 0 ? it.getItemStringArray() : null;
                        if (v == null) break;
                        switch (item) {
                            case 2: description = v[0]; break;
                            case 4: afterRoad = v[0]; break;
                            case 6: distStr = v[0];distanceTextChanged=true; break;
                        }
                        break;
                    }
                }
            }
            if ((distanceChanged || distanceUnitChanged) && !distanceTextChanged) distStr="";
            // HUD secondary resolution needs the last supplied geometry across
            // distance/units-only updates. Keep the original per-message exit
            // freshness used by primary/cluster rendering unchanged.
            if (exitSupplied) { hudExit=exit;hudExitKnown=true; }
            else if (oldType!=type || oldSide!=side || oldJunction!=junction) hudExitKnown=false;
            // A valid newer display-only interval must not be hidden by an old
            // raw value. Invalid text cannot displace a measured raw interval.
            if (distanceTextChanged && !distanceChanged && distUnitsKnown) {
                float displayed=displayMetres(distStr,phoneDistanceUnit(distUnits));
                if (!Float.isNaN(displayed) && !Float.isInfinite(displayed)) distanceKnown=false;
            }
        }
    }
}
