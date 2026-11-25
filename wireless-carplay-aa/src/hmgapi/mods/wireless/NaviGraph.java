package hmgapi.mods.wireless;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.ModContext;

/**
 * The car navigation engine's road graph at the intersection ahead, read from inside
 * com.mnsoft.navi (setting navi_road_graph, a test, off by default). Android Auto sends no
 * junction shape, so this is where its side streets will come from: the engine keeps map
 * matching while a phone guides, with no route of its own.
 *
 * This first step only reads and logs, it sends nothing to the cluster. Once a second:
 * the map-matched link (native/navigraph, libwlnavigraph.so, through the engine's own
 * exported functions), the node it reaches, every road there as an angle from the road
 * the car is on (right positive), and those as the cluster's side streets. A line goes to
 * HMG-API's log when that changes. No coordinates, link ids or names are logged.
 *
 * Only on an engine build with a profile (its md5: the mArray module tag its own code
 * uses); others are left alone. Every engine function must be there and in
 * libExSLNavi.so. Adapted from ch0iiih/HMG_Phone_Projection_TBT (AGPL-3.0): its native
 * helper and StockMapJunction's node geometry.
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

    static native String nativeBind(int module);

    static native long[] nativeCurrent();

    static native long[] nativeNode(long parcel, long link, int direction);

    static volatile boolean started;

    static void start(final ModContext mod, final LoadedApp app) {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loop(mod);
                } catch (Throwable e) {
                    mod.error("road graph", e);
                }
            }
        }, "hmgapi-navi-graph");
        t.setDaemon(true);
        t.start();
    }

    static void loop(ModContext mod) throws Exception {
        // the engine first: the app loads it after it starts
        long until = SystemClock.elapsedRealtime() + 300000;
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
        mod.log("road graph: reading the " + p[1] + " engine's road graph (read only, log only)");
        String last = null;
        int errors = 0;
        while (mod.enabled()) {
            String line = describe(nativeCurrent());
            if (!line.equals(last)) {
                mod.log("road graph: " + line);
                last = line;
            }
            errors = line.startsWith("no position") || line.startsWith("error") ? errors + 1 : 0;
            Thread.sleep(errors > 30 ? 5000 : 1000);
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

    /** the current link and the node ahead, in words and numbers (no ids or coordinates) */
    static String describe(long[] cur) {
        if (cur == null || cur.length < 1) return "error: no answer";
        if (cur[0] != 0) return cur[0] == -2 || cur[0] == -3 || cur[0] == -4 ? "no position yet (" + cur[0] + ")" : "error " + cur[0];
        if (cur.length < 7) return "error: short answer";
        long[] rows = nativeNode(cur[1], cur[2], (int) cur[3]);
        Node n = node(rows);
        StringBuilder b = new StringBuilder("on a link with ").append(cur[6]).append(" onward");
        if (cur[4] != 0) b.append(", overpass");
        if (cur[5] != 0) b.append(", underpass");
        b.append("; node ahead: ");
        if (n.error != null) return b.append(n.error).toString();
        return b.append(n.angles.size()).append(" roads, angles ").append(n.angles)
                .append(", side streets 0x").append(Integer.toHexString(n.sides)).toString();
    }

    /** the node ahead: the angles of the roads leaving it (degrees, right positive) and their side-street bits */
    static final class Node {
        final List<Integer> angles = new ArrayList<Integer>();
        int sides;
        String error;
    }

    /**
     * nativeNode's rows: {4, status, rows, roads at the node, 1, rows x 15}, a row being
     * parcel, link, status, length, points, x0, y0, x1, y1, x2, y2, x3, y3 (the first two
     * and last two shape points), overpass, underpass; row 0 the car's link.
     */
    static Node node(long[] v) {
        Node n = new Node();
        if (v == null || v.length < 5 || v[0] != 4) { n.error = "no answer"; return n; }
        if (v[1] != 0) { n.error = "not read (" + v[1] + ")"; return n; }
        int rows = (int) v[2];
        if (rows < 2 || rows > 9 || v.length != 5 + rows * 15) { n.error = "bad rows"; return n; }
        long[] cur = row(v, 0);
        if (cur[2] != 0) { n.error = "car's link not read (" + cur[2] + ")"; return n; }
        // the node: the end of the car's link that every other road there starts or ends at
        long[] node = null;
        for (int end = 0; end < 2 && node == null; end++) {
            long x = cur[end == 0 ? 5 : 11], y = cur[end == 0 ? 6 : 12];
            boolean all = true;
            for (int i = 1; i < rows && all; i++) {
                long[] r = row(v, i);
                if (r[2] != 0) continue;
                all = (r[5] == x && r[6] == y) || (r[11] == x && r[12] == y);
            }
            if (all) node = new long[] { x, y, end };
        }
        if (node == null) { n.error = "roads don't meet at an end of the car's link"; return n; }
        double lat = node[1] / SCALE;
        // the car comes in along its link's last segment to the node
        double in = node[2] == 1 ? bearing(cur[9], cur[10], node[0], node[1], lat) : bearing(cur[7], cur[8], node[0], node[1], lat);
        for (int i = 1; i < rows; i++) {
            long[] r = row(v, i);
            if (r[2] != 0) continue;
            if (r[0] == cur[0] && r[1] == cur[1]) continue;   // the car's own link, listed at its node too
            double out;
            if (r[5] == node[0] && r[6] == node[1]) out = bearing(node[0], node[1], r[7], r[8], lat);
            else out = bearing(node[0], node[1], r[9], r[10], lat);
            int a = (int) Math.round(angle(out - in));
            n.angles.add(a);
            n.sides |= roadBit(a);
        }
        java.util.Collections.sort(n.angles);
        return n;
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
