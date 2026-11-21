package hmgapi.mods.wireless;

/** Host test of NaviGraph's node geometry on made-up rows (map units, 1/360000 degree). */
public final class NaviGraphTest {
    static int fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    static final long X = 100L * 360000, Y = 35L * 360000;   // the node, somewhere at 35 degrees north

    /** a row: link id, status, then the first two and last two shape points */
    static long[] r(long link, int status, long... p) {
        long[] v = new long[15];
        v[0] = 9;
        v[1] = link;
        v[2] = status;
        v[3] = 100;
        v[4] = 4;
        System.arraycopy(p, 0, v, 5, 8);
        return v;
    }

    static long[] rows(long[]... rows) {
        long[] v = new long[5 + rows.length * 15];
        v[0] = 4;
        v[1] = 0;
        v[2] = rows.length;
        v[3] = rows.length - 1;
        v[4] = 1;
        for (int i = 0; i < rows.length; i++) System.arraycopy(rows[i], 0, v, 5 + i * 15, 15);
        return v;
    }

    public static void main(String[] a) {
        // the car comes north along link 1 to the node; roads north (2), east (3, drawn toward the node), west (4)
        long[] car = r(1, 0, X, Y - 200, X, Y - 100, X, Y - 50, X, Y);
        long[] north = r(2, 0, X, Y, X, Y + 100, X, Y + 150, X, Y + 300);
        long[] east = r(3, 0, X + 300, Y, X + 200, Y, X + 100, Y, X, Y);
        long[] west = r(4, 0, X, Y, X - 100, Y, X - 150, Y, X - 300, Y);
        NaviGraph.Node n = NaviGraph.node(rows(car, car, north, east, west));
        check("a crossroad: ahead, right and left, side streets 0x811: " + n.angles + " 0x" + Integer.toHexString(n.sides),
                n.error == null && n.angles.toString().equals("[-90, 0, 90]") && n.sides == 0x811);

        // the car's link drawn away from the node (the node is its first point); a slight right fork
        long[] carRev = r(1, 0, X, Y, X, Y - 50, X, Y - 100, X, Y - 200);
        long[] slight = r(5, 0, X, Y, X + 100, Y + 100, X + 200, Y + 200, X + 300, Y + 300);
        n = NaviGraph.node(rows(carRev, carRev, north, slight));
        int ne = (int) Math.round(Math.toDegrees(Math.atan2(100 * Math.cos(Math.toRadians(35)), 100)));
        check("the car's link drawn the other way; a slight right at the latitude's angle (" + ne + "): " + n.angles,
                n.error == null && n.angles.size() == 2 && n.angles.get(0) == 0 && n.angles.get(1) == ne
                        && n.sides == (1 | NaviGraph.roadBit(ne)));

        n = NaviGraph.node(rows(car, car, r(3, -5, 0, 0, 0, 0, 0, 0, 0, 0), west));
        check("a road the engine couldn't read is left out: " + n.angles, n.error == null && n.angles.toString().equals("[-90]"));
        n = NaviGraph.node(rows(car, car, r(6, 0, X + 999, Y, X + 1, Y, X + 2, Y, X + 3, Y)));
        check("roads that don't meet at the car's link: an error", n.error != null && n.angles.isEmpty());
        check("not read, a bad answer, no answer: errors",
                NaviGraph.node(new long[] { 4, -36, 0, 0, 0 }).error.contains("-36")
                        && NaviGraph.node(new long[] { 4, 0, 3, 2, 1 }).error != null && NaviGraph.node(null).error != null);
        check("roadBit: 0 ahead, 90 right bit 4, -90 left bit 11, 180 none",
                NaviGraph.roadBit(0) == 1 && NaviGraph.roadBit(90) == 1 << 4 && NaviGraph.roadBit(-90) == 1 << 11 && NaviGraph.roadBit(180) == 0);
        check("profiles: the US 260507 engine with its module tag 0x9550; others none",
                NaviGraph.profile("caa8d4c2120ec0a14aafc401c6720971") != null
                        && Integer.parseInt(NaviGraph.profile("caa8d4c2120ec0a14aafc401c6720971")[2]) == 0x9550
                        && NaviGraph.profile("0") == null);
        check("describe: errors from the engine read as words, no native call",
                NaviGraph.describe(new long[] { -3 }).startsWith("no position yet") && NaviGraph.describe(new long[] { -6 }).equals("error -6"));
        System.exit(fail);
    }
}
