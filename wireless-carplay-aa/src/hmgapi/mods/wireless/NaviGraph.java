package hmgapi.mods.wireless;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.ModContext;

/**
 * The car navigation engine's road graph, read from inside com.mnsoft.navi: where
 * Android Auto's side streets come from, since it sends no junction shape. The engine
 * keeps map matching while a phone guides, with no route of its own.
 *
 * Two uses, each its own setting (both off by default):
 *   - navi_road_graph (a test): once a second, the node ahead and its roads go to
 *     HMG-API's log when they change. Nothing goes to the cluster.
 *   - tbt_map_side_streets: answers the phone side's questions (MapJunction, from
 *     AaTurns in GPM): "the junction about M meters ahead, where I turn A degrees?"
 *     It walks forward from the car along the road (find(): straight on through nodes,
 *     never at a fork it can't tell), and of the nodes around that distance takes the
 *     one where the turn picks out one road unambiguously (match()), and answers its
 *     side streets. Otherwise 0, and the turn keeps its plain arrow.
 *
 * Reads go through native/navigraph (libwlnavigraph.so), the engine's own exported
 * functions, on an engine build with a profile (its md5: its mArray module tag);
 * others are left alone. No coordinates, link ids or names are logged or sent.
 * Adapted from ch0iiih/HMG_Phone_Projection_TBT (AGPL-3.0): its native helper,
 * StockMapLookahead and StockMapJunction.
 */
final class NaviGraph {
    static final String ENGINE = "/data/data/com.mnsoft.navi/BIN/libExSLNavi.so";
    static final String LIB = "/system/lib/libwlnavigraph.so";
    /** engine md5 -> {name, mArray module tag} (from the engine's own CInformation code) */
    static final String[][] PROFILES = {
        { "caa8d4c2120ec0a14aafc401c6720971", "US 260507 (Ioniq 5 NEEV23)", "38224" },   // 0x9550
    };
    /** map units per degree (the engine's mLonLat_t<int>) */
    static final double SCALE = 360000.0;
    /** the phone side asks (to com.mnsoft.navi) and gets its answer (to the asking package) */
    static final String ASK = "hmgapi.mods.wireless.JUNCTION_ASK", ANSWER = "hmgapi.mods.wireless.JUNCTION";
    static final String NAVIGATION = "com.mnsoft.navi";
    /**
     * what a question's sender must hold: signature|privileged, so the system's apps have it
     * (GPM runs as the system) and an app installed on the head unit doesn't. As the bridge's
     * iAP2 receiver. The answer back is checked by the token the question gave (MapJunction).
     */
    static final String ASKER = "android.permission.BLUETOOTH_PRIVILEGED";
    /** a question is answered while it's younger than this (ms) */
    static final int ASK_LIFE = 3000;
    /** farthest maneuver asked about (m) */
    static final int MAX_METERS = 600;

    static native String nativeBind(int module);

    static native long[] nativeCurrent();

    static native long[] nativeNode(long parcel, long link, int direction);

    /** the engine's rows for the node a link reaches going in direction (0 toward its first point, 1 its last) */
    interface Reader {
        long[] read(long parcel, long link, int direction);
    }

    static volatile boolean started;
    /** the last question: {elapsed ms, id, meters, angle}, and who asked */
    static volatile long[] question;
    static volatile String asker;
    static volatile long askToken;

    static void start(final ModContext mod, final LoadedApp app, final boolean probe, final boolean answer) {
        if (started) return;
        started = true;
        if (answer) {
            app.context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    String from = i.getStringExtra("reply");
                    if (from == null || !from.matches("[A-Za-z0-9_.]{1,100}")) return;
                    asker = from;
                    askToken = i.getLongExtra("token", 0);
                    question = new long[] { SystemClock.elapsedRealtime(), i.getIntExtra("id", 0), i.getIntExtra("meters", -1),
                            i.getIntExtra("angle", 999) };
                }
            }, new IntentFilter(ASK), ASKER, null);
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loop(mod, app.context, probe, answer);
                } catch (Throwable e) {
                    mod.error("road graph", e);
                }
            }
        }, "hmgapi-navi-graph");
        t.setDaemon(true);
        t.start();
    }

    static void loop(ModContext mod, Context context, boolean probe, boolean answer) throws Exception {
        long until = SystemClock.elapsedRealtime() + 300000;   // the app loads its engine after it starts
        while (!engineLoaded()) {
            if (SystemClock.elapsedRealtime() > until) {
                mod.log("road graph: the navigation engine never loaded in this process");
                return;
            }
            Thread.sleep(2000);
        }
        String md5 = ModContext.md5(ENGINE);
        String[] p = profile(md5);
        if (p == null) {
            mod.log("road graph: no profile for this navigation engine (md5 " + md5 + "), left alone");
            return;
        }
        System.load(LIB);
        String missing = nativeBind(Integer.parseInt(p[2]));
        if (missing != null) {
            mod.log("road graph: engine " + p[1] + " lacks " + missing + ", left alone");
            return;
        }
        mod.log("road graph: reading the " + p[1] + " engine's road graph (read only)"
                + (probe ? ", logging the node ahead" : "") + (answer ? ", answering Android Auto's junction questions" : ""));
        final Cache cache = new Cache();
        Reader reader = new Reader() {
            @Override
            public long[] read(long parcel, long link, int direction) {
                return cache.get(parcel, link, direction);
            }
        };
        String lastProbe = null, lastAnswer = null;
        long answered = 0, probed = 0;
        while (mod.enabled()) {
            long now = SystemClock.elapsedRealtime();
            long[] q = question;
            boolean asked = answer && q != null && now - q[0] < ASK_LIFE;
            boolean probeNow = probe && now - probed >= 1000;
            boolean answerNow = asked && now - answered >= 900;
            if (probeNow || answerNow) {
                long[] cur = nativeCurrent();
                if (probeNow) {
                    probed = now;
                    String line = describe(cur, reader);
                    if (!line.equals(lastProbe)) {
                        mod.log("road graph: " + line);
                        lastProbe = line;
                    }
                }
                if (answerNow) {
                    answered = now;
                    Answer a = answer(cur, (int) q[2], (int) q[3], reader);
                    reply(context, asker, askToken, (int) q[1], a);
                    String line = "junction " + q[2] + " m, turn " + q[3] + ": " + a.reason
                            + (a.sides != 0 ? ", side streets 0x" + Integer.toHexString(a.sides) + " at " + a.meters + " m" : "");
                    if (!line.equals(lastAnswer)) {
                        mod.log("road graph: " + line);
                        lastAnswer = line;
                    }
                }
            }
            cache.trim(now);
            Thread.sleep(asked ? 250 : 1000);
        }
    }

    static void reply(Context context, String to, long token, int id, Answer a) {
        Intent i = new Intent(ANSWER);
        i.setPackage(to);
        i.putExtra("token", token);
        i.putExtra("id", id);
        i.putExtra("sides", a.sides);
        i.putExtra("meters", a.meters);
        context.sendBroadcast(i);
    }

    /** node rows by (parcel, link, direction), kept 30 s: the road graph doesn't change under the car */
    static final class Cache {
        final Map<String, long[]> rows = new LinkedHashMap<String, long[]>();
        final Map<String, Long> at = new LinkedHashMap<String, Long>();

        long[] get(long parcel, long link, int direction) {
            String k = parcel + ":" + link + ":" + direction;
            long[] r = rows.get(k);
            if (r == null) {
                r = nativeNode(parcel, link, direction);
                rows.put(k, r);
                at.put(k, SystemClock.elapsedRealtime());
            }
            return r;
        }

        void trim(long now) {
            java.util.Iterator<Map.Entry<String, Long>> it = at.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (now - e.getValue() > 30000 || rows.size() > 256) {
                    rows.remove(e.getKey());
                    it.remove();
                }
            }
        }
    }

    static boolean engineLoaded() {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"));
            try {
                for (String l; (l = r.readLine()) != null; ) if (l.endsWith("/libExSLNavi.so")) return true;
            } finally {
                r.close();
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    static String[] profile(String md5) {
        for (String[] p : PROFILES) if (p[0].equals(md5)) return p;
        return null;
    }

    // ---- what the engine said: current = {status, parcel, link, direction, overpass, underpass, count,
    //      meters along the link, its length, ...}

    static boolean positioned(long[] cur) {
        return cur != null && cur.length >= 9 && cur[0] == 0 && (cur[3] == 0 || cur[3] == 1);
    }

    /**
     * Meters from the car to the end of its link it's heading for: length - pos. GetLinkPos
     * counts the way the car is going, from the end it came in at, whichever way the link
     * is drawn: on a US Ioniq 5 drive (2026-11-21) it ran from 0 up to the length on 298 of
     * 303 links, 220 of them driven toward the link's first point. -1 when it doesn't add up.
     */
    static int remaining(long[] cur) {
        if (!positioned(cur) || cur[8] <= 0 || cur[7] < 0 || cur[7] > cur[8]) return -1;
        return (int) (cur[8] - cur[7]);
    }

    /** the current link and the node ahead, in words and numbers (no ids or coordinates) */
    static String describe(long[] cur, Reader reader) {
        if (cur == null || cur.length < 1) return "error: no answer";
        if (cur[0] != 0) return cur[0] == -2 || cur[0] == -3 || cur[0] == -4 ? "no position yet (" + cur[0] + ")" : "error " + cur[0];
        if (!positioned(cur)) return "error: short answer";
        Node n = node(reader.read(cur[1], cur[2], (int) cur[3]), (int) cur[3]);
        StringBuilder b = new StringBuilder("on a link of ").append(cur[8]).append(" m, ").append(cur[7]).append(" m along, heading ")
                .append(cur[3] == 1 ? "forward" : "backward").append(", node ahead in ").append(remaining(cur)).append(" m");
        if (cur[4] != 0) b.append(", overpass");
        if (cur[5] != 0) b.append(", underpass");
        b.append(": ");
        if (n.error != null) return b.append(n.error).toString();
        return b.append(n.roads.size()).append(" roads, angles ").append(n.angles()).append(", side streets 0x")
                .append(Integer.toHexString(n.sides)).toString();
    }

    /** a road leaving a node: its angle from the road the car comes in on (degrees, right positive) */
    static final class Road {
        int angle;
        long parcel, link, length;
        /** the direction to go along it away from the node */
        int direction;
    }

    /** a node: the roads leaving it (the one the car comes in on left out) and their side-street bits */
    static final class Node {
        final List<Road> roads = new ArrayList<Road>();
        int sides;
        /** two roads in the same 22.5 degree sector */
        boolean overlap;
        String error;

        List<Integer> angles() {
            List<Integer> a = new ArrayList<Integer>();
            for (Road r : roads) a.add(r.angle);
            java.util.Collections.sort(a);
            return a;
        }
    }

    /**
     * nativeNode's rows: {4, status, rows, roads at the node, 1, rows x 15}, a row being
     * parcel, link, status, length, points, x0, y0, x1, y1, x2, y2, x3, y3 (the first two
     * and last two shape points), overpass, underpass; row 0 the link the car comes in on,
     * going in direction (1: toward its last point, the node is its last point; 0: its first).
     */
    static Node node(long[] v, int direction) {
        Node n = new Node();
        if (v == null || v.length < 5 || v[0] != 4) { n.error = "no answer"; return n; }
        if (v[1] != 0) { n.error = "not read (" + v[1] + ")"; return n; }
        int rows = (int) v[2];
        if (rows < 2 || rows > 9 || v.length != 5 + rows * 15) { n.error = "bad rows"; return n; }
        long[] in = row(v, 0);
        if (in[2] != 0) { n.error = "the car's link not read (" + in[2] + ")"; return n; }
        if (direction != 0 && direction != 1) { n.error = "bad direction"; return n; }
        long x = in[direction == 1 ? 11 : 5], y = in[direction == 1 ? 12 : 6];
        double lat = y / SCALE;
        double from = direction == 1 ? bearing(in[9], in[10], x, y, lat) : bearing(in[7], in[8], x, y, lat);
        for (int i = 1; i < rows; i++) {
            long[] r = row(v, i);
            if (r[0] == in[0] && r[1] == in[1]) continue;   // the car's own link, listed at its node too
            if (r[2] != 0) {
                // a road whose shape didn't load: the others alone could look like one road on a
                // side, or one way straight on, when there are two, so the node isn't used
                n.error = "a road at the node not read (" + r[2] + ")";
                n.roads.clear();
                n.sides = 0;
                return n;
            }
            Road road = new Road();
            double out;
            if (r[5] == x && r[6] == y) {
                out = bearing(x, y, r[7], r[8], lat);
                road.direction = 1;
            } else if (r[11] == x && r[12] == y) {
                out = bearing(x, y, r[9], r[10], lat);
                road.direction = 0;
            } else {
                n.error = "roads don't meet at the node";
                n.roads.clear();
                n.sides = 0;
                return n;
            }
            road.angle = (int) Math.round(angle(out - from));
            road.parcel = r[0];
            road.link = r[1];
            road.length = r[3];
            int bit = roadBit(road.angle);
            if (bit != 0 && (n.sides & bit) != 0) n.overlap = true;
            n.sides |= bit;
            n.roads.add(road);
        }
        return n;
    }

    /** where find() got to: the turn's node near the asked distance (or why not) */
    static final class Found {
        Node node;
        int meters = -1;
        String reason;
        /** the node's match with the turn, when there's a node */
        Answer match;
    }

    /**
     * The phone's distance to a turn reads longer than the map's to its node: by a median of
     * 13 m on the 2026-11-21 drive (95 samples on the last link before 8 turns: -13 to +30 m,
     * nine in ten between 2 and 22). So the node is looked for around the phone's distance
     * less PHONE_LONG, within window(): 25 m near the turn, an eighth of the distance farther
     * out, 45 at most. That covered every sample; the 15 m either side of the phone's own
     * distance it replaces covered two in three.
     */
    static final int PHONE_LONG = 8;

    static int window(int meters) {
        return Math.max(25, Math.min(45, meters / 8));
    }

    /**
     * The intersection a turn of this angle is at, about meters ahead: from the node ahead
     * of the car, straight on through each node (the one road within 30 degrees of straight,
     * the next nearest at least 15 farther off) until the distance passes the window. Of the
     * nodes in the window with more than one road on, the one the turn fits (match()); with
     * several, the nearest to where the phone puts it, if it's 10 m nearer than the next.
     * Picking by the turn first is what lets the window be wide: a side street on the other
     * side 20 m before the turn isn't taken for it. After ch0iiih's StockMapLookahead.
     */
    static Found find(long[] cur, int meters, int angle, Reader reader) {
        Found f = new Found();
        int d = remaining(cur);
        if (d < 0) { f.reason = "no position on the link"; return f; }
        if (meters <= 0 || meters > MAX_METERS) { f.reason = "distance out of range"; return f; }
        int tol = window(meters), want = meters - PHONE_LONG;
        long parcel = cur[1], link = cur[2];
        int direction = (int) cur[3];
        Node best = null;
        Answer bestMatch = null, miss = null;
        int bestErr = 999, secondErr = 999, bestAt = -1, missErr = 999, missAt = -1;
        String stop = null;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int hop = 0; hop < 16; hop++) {
            if (!seen.add(parcel + ":" + link + ":" + direction)) { stop = "went round in a loop"; break; }
            Node n = node(reader.read(parcel, link, direction), direction);
            if (n.error != null) { stop = "node " + hop + ": " + n.error; break; }
            int err = Math.abs(d - want);
            if (n.roads.size() >= 2 && err <= tol) {
                Answer m = match(n, angle);
                if (m.sides != 0) {
                    if (err < bestErr) {
                        secondErr = bestErr;
                        bestErr = err;
                        best = n;
                        bestMatch = m;
                        bestAt = d;
                    } else if (err < secondErr) {
                        secondErr = err;
                    }
                } else if (err < missErr) {
                    missErr = err;
                    miss = m;
                    missAt = d;
                }
            }
            if (d > want + tol) break;
            Road on = null;
            int a1 = 999, a2 = 999;
            for (Road r : n.roads) {
                int a = Math.abs(r.angle);
                if (a < a1) { a2 = a1; a1 = a; on = r; } else if (a < a2) a2 = a;
            }
            if (on == null || a1 > 30 || a2 - a1 < 15 || on.length <= 0) {
                stop = n.roads.isEmpty() ? "the road ends" : "no clear way straight on at " + d + " m";
                break;
            }
            d += on.length;
            parcel = on.parcel;
            link = on.link;
            direction = on.direction;
        }
        if (best == null) {
            f.reason = miss != null ? "junction at " + missAt + " m, " + miss.reason : stop != null ? stop : "no junction near " + meters + " m";
            return f;
        }
        if (secondErr - bestErr < 10) { f.reason = "two junctions the turn fits near " + meters + " m"; return f; }
        f.node = best;
        f.match = bestMatch;
        f.meters = bestAt;
        f.reason = "junction at " + bestAt + " m";
        return f;
    }

    /** what goes back to the phone side: side streets (0: none, keep the plain arrow) and why */
    static final class Answer {
        int sides, meters = -1;
        String reason;
    }

    /**
     * The side streets for a turn at a node, only when the turn picks out one of its roads:
     * angle the turn's (degrees, right positive: 0 straight, 45 slight, 90, 135 sharp), the
     * nearest road within 60 degrees of it (22 straight) and 15 nearer than any other, on the
     * turn's side; for a plain left or right, the only road on that side. A road within 30
     * degrees of straight is the way on, as in find(), not a road on a side: on the
     * 2026-11-21 drive a left was refused where the road ahead bent 18 degrees left beside the
     * street the turn took. Two roads in one sector, or a node with one road on, give 0.
     * After ch0iiih's StockMapJunction.
     */
    static Answer match(Node n, int angle) {
        Answer a = new Answer();
        if (n == null || n.error != null) { a.reason = n == null ? "no node" : n.error; return a; }
        if (n.roads.size() < 2) { a.reason = "not an intersection"; return a; }
        if (n.overlap) { a.reason = "two roads in one sector"; return a; }
        boolean straight = angle == 0, right = angle > 0;
        int best = 999, second = 999, onSide = 0;
        Road pick = null;
        for (Road r : n.roads) {
            int e = (int) Math.abs(angle(r.angle - angle));
            if (e < best) { second = best; best = e; pick = r; } else if (e < second) second = e;
            if (!straight && (right ? r.angle > 30 && r.angle <= 157 : r.angle < -30 && r.angle >= -157)) onSide++;
        }
        int tol = straight ? 22 : 60;
        if (pick == null || best > tol) { a.reason = "no road where the turn goes"; return a; }
        if (second - best < 15) { a.reason = "the turn could be two roads"; return a; }
        if (!straight && (right ? pick.angle <= 12 : pick.angle >= -12)) { a.reason = "the nearest road is on the other side"; return a; }
        if (Math.abs(angle) == 90 && onSide != 1) { a.reason = onSide + " roads on the turn's side"; return a; }
        a.sides = n.sides;
        a.reason = "matched";
        return a;
    }

    static Answer answer(long[] cur, int meters, int angle, Reader reader) {
        Answer a = new Answer();
        if (!positioned(cur)) { a.reason = "no position"; return a; }
        if (angle < -180 || angle > 180) { a.reason = "no turn"; return a; }
        Found f = find(cur, meters, angle, reader);
        if (f.node == null) { a.reason = f.reason; return a; }
        a = f.match;
        a.meters = f.meters;
        return a;
    }

    /**
     * On the phone side (MapJunction): an answer is for this turn when it's for this
     * question's id, under 2.5 s old, and its junction is about where the phone's distance
     * said: as far off as find() allows (PHONE_LONG and its window), plus 40 m the car may
     * have gone between the question and the answer.
     */
    static boolean usable(int id, int meters, int answerId, int answerMeters, long age) {
        if (answerId != id || age < 0 || age > 2500 || answerMeters < 0 || meters < 0) return false;
        return Math.abs(answerMeters - meters) <= PHONE_LONG + window(meters) + 40;
    }

    /**
     * On the phone side: a turn's match, held while the car closes on its junction. The
     * question goes out about once a second and the matcher can miss now and then (another
     * node in tolerance for a moment, the car between links), and on a drive the side
     * streets came and went with each answer. An answer counts when it's usable for the
     * question it came back for; from then on that turn keeps its side streets until a new
     * match replaces them, the turn changes (a new id), or the phone's distance grows by
     * 100 m over where it matched (not the junction the car is heading for any more). A "no
     * match" answer doesn't clear it.
     */
    static final class Held {
        int id = -1, sides, meters;

        /** an answer for the question (id, askedMeters) asked age ms ago */
        void answer(int id, int askedMeters, int sides, int answerMeters, long age) {
            if (sides == 0 || !usable(id, askedMeters, id, answerMeters, age)) return;
            this.id = id;
            this.sides = sides;
            this.meters = askedMeters;
        }

        int sides(int id, int meters) {
            if (id != this.id || meters < 0 || meters > this.meters + 100) return 0;
            return sides;
        }
    }

    static long[] row(long[] v, int i) {
        long[] r = new long[15];
        System.arraycopy(v, 5 + i * 15, r, 0, 15);
        return r;
    }

    /** degrees clockwise from north, from (x0, y0) to (x1, y1) in map units */
    static double bearing(long x0, long y0, long x1, long y1, double lat) {
        return Math.toDegrees(Math.atan2((x1 - x0) * Math.cos(Math.toRadians(lat)), (double) (y1 - y0)));
    }

    static double angle(double a) {
        a %= 360;
        if (a > 180) a -= 360;
        if (a <= -180) a += 360;
        return a;
    }

    /**
     * A road's side-street bit (as CpTurns.roadBit, the engine's SetSideStreetBit): bit 0 ahead
     * (within 12 degrees), bits 1-7 right and 8-14 left in 22.5 degree sectors, none behind.
     */
    static int roadBit(int angle) {
        int cw = ((angle % 360) + 360) % 360;
        if (cw < 12 || cw > 348) return 1;
        int lo = 12, bit = 1;
        for (int k = 0; k < 15; k++) {
            int hi = lo + (k % 2 == 0 ? 22 : 23);
            if (k != 7) {
                if (cw >= lo && cw < hi) return 1 << bit;
                bit++;
            }
            lo = hi;
        }
        return 0;
    }
}
