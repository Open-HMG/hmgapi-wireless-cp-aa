package hmgapi.mods.wireless;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host test of NaviGraph (node geometry, the walk to the junction at a distance, matching a
 * turn to one of its roads) on a made-up map, and of MapJunction's questions and answers.
 * Map units: 1/360000 degree.
 */
public final class NaviGraphTest {
    static int fail;

    /** the token the MapJunction under test asks with */
    static long token;

    /** NaviGraph's answer broadcast as MapJunction gets it */
    static Intent answer(int id, int sides, int meters) {
        return new Intent(NaviGraph.ANSWER).putExtra("token", token).putExtra("id", id).putExtra("sides", sides).putExtra("meters", meters);
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    static final long X = 100L * 360000, Y = 35L * 360000;   // the map's origin, somewhere at 35 degrees north
    /** map units per meter north (and east, at this latitude) */
    static final double NY = 360000.0 / 111195, NX = NY / Math.cos(Math.toRadians(35));

    /** a made-up map: links drawn from a point to a point (meters east, north of the origin) */
    static final class Map2 implements NaviGraph.Reader {
        final Map<Long, long[]> links = new LinkedHashMap<Long, long[]>();   // id -> {x0, y0, x1, y1, length m}
        int reads;

        void link(long id, double e0, double n0, double e1, double n1) {
            long len = Math.round(Math.hypot(e1 - e0, n1 - n0));
            links.put(id, new long[] { X + Math.round(e0 * NX), Y + Math.round(n0 * NY), X + Math.round(e1 * NX), Y + Math.round(n1 * NY), len });
        }

        long[] rowOf(long id) {
            long[] l = links.get(id);
            long[] r = new long[15];
            r[0] = 9;
            r[1] = id;
            r[3] = l[4];
            r[4] = 4;
            // first two and last two points: the ends and a point a fifth of the way in from each
            long mx0 = l[0] + (l[2] - l[0]) / 5, my0 = l[1] + (l[3] - l[1]) / 5, mx1 = l[2] - (l[2] - l[0]) / 5, my1 = l[3] - (l[3] - l[1]) / 5;
            long[] p = { l[0], l[1], mx0, my0, mx1, my1, l[2], l[3] };
            System.arraycopy(p, 0, r, 5, 8);
            return r;
        }

        @Override
        public long[] read(long parcel, long link, int direction) {
            reads++;
            long[] l = links.get(link);
            if (l == null) return new long[] { 4, -5, 0, 0, 0 };
            long x = direction == 1 ? l[2] : l[0], y = direction == 1 ? l[3] : l[1];
            List<long[]> rows = new ArrayList<long[]>();
            rows.add(rowOf(link));
            for (Map.Entry<Long, long[]> e : links.entrySet()) {
                long[] o = e.getValue();
                if ((o[0] == x && o[1] == y) || (o[2] == x && o[3] == y)) rows.add(rowOf(e.getKey()));
            }
            long[] v = new long[5 + rows.size() * 15];
            v[0] = 4;
            v[2] = rows.size();
            v[3] = rows.size() - 1;
            v[4] = 1;
            for (int i = 0; i < rows.size(); i++) System.arraycopy(rows.get(i), 0, v, 5 + i * 15, 15);
            return v;
        }
    }

    /** the engine's current position: on a link, meters along it from its first point, its length */
    static long[] at(long link, int direction, long pos, long len) {
        return new long[] { 0, 9, link, direction, 0, 0, 1, pos, len };
    }

    public static void main(String[] a) {
        // a road north: the car on link 1 (south of the origin), the road on through a bend-free node
        // at 0 m, a T junction (a road to the right) at 100 m, a crossroad at 220 m, a fork at 400 m
        Map2 m = new Map2();
        m.link(1, 0, -100, 0, 0);
        m.link(2, 0, 0, 0, 100);
        m.link(3, 0, 100, 0, 220);
        m.link(10, 0, 100, 150, 100);          // the T's road to the right
        m.link(4, 0, 220, 0, 400);
        m.link(20, 0, 220, 120, 220);          // the crossroad's right
        m.link(21, -120, 220, 0, 220);         // ... and left, drawn toward it
        m.link(5, 0, 400, 60, 560);            // the fork: 20 degrees right and left
        m.link(6, 0, 400, -60, 560);
        long[] car = at(1, 1, 20, 100);        // 20 m along link 1 heading north: 80 m to its end

        check("remaining: length - pos whichever way the link is driven (80), nonsense -1",
                NaviGraph.remaining(car) == 80 && NaviGraph.remaining(at(1, 0, 20, 100)) == 80
                        && NaviGraph.remaining(at(1, 1, 120, 100)) == -1 && NaviGraph.remaining(new long[] { -3 }) == -1);

        NaviGraph.Node n = NaviGraph.node(m.read(9, 3, 1), 1);
        check("node: the crossroad from the south: ahead, right and left, side streets 0x811: " + n.angles(),
                n.error == null && n.angles().toString().equals("[-90, 0, 90]") && n.sides == 0x811 && !n.overlap);
        n = NaviGraph.node(m.read(9, 2, 1), 1);
        check("node: the T: ahead and right: " + n.angles(), n.angles().toString().equals("[0, 90]") && n.sides == (1 | 1 << 4));
        n = NaviGraph.node(m.read(9, 3, 0), 0);
        check("node: the T from the north (link 3 the other way): ahead and left: " + n.angles(), n.angles().toString().equals("[-90, 0]"));
        check("node: no answer, not read, a bad direction: errors",
                NaviGraph.node(null, 1).error != null && NaviGraph.node(new long[] { 4, -36, 0, 0, 0 }, 1).error.contains("-36")
                        && NaviGraph.node(m.read(9, 3, 1), 2).error != null);
        // the crossroad with its left road's shape not loaded (row status 7): what's left would be a T
        final Map2 full = m;
        NaviGraph.Reader leftUnread = new NaviGraph.Reader() {
            @Override
            public long[] read(long parcel, long link, int direction) {
                long[] v = full.read(parcel, link, direction).clone();
                for (int i = 1; v[0] == 4 && i < v[2]; i++) if (v[5 + i * 15 + 1] == 21) v[5 + i * 15 + 2] = 7;
                return v;
            }
        };
        n = NaviGraph.node(leftUnread.read(9, 3, 1), 1);
        check("node: a road at the node not read: the node isn't used (not taken for a T): " + n.error,
                n.error != null && n.error.contains("not read (7)") && n.roads.isEmpty() && n.sides == 0);
        NaviGraph.Found f0 = NaviGraph.find(car, 300, 90, leftUnread);
        check("find: ... so the right at 300 m gets nothing from it: " + f0.reason, f0.node == null && f0.reason.contains("not read"));

        check("window: 25 m near the turn, an eighth of the distance, 45 at most",
                NaviGraph.window(50) == 25 && NaviGraph.window(200) == 25 && NaviGraph.window(280) == 35 && NaviGraph.window(600) == 45);
        NaviGraph.Found f = NaviGraph.find(car, 300, 90, m);
        check("find: right at 300 m: the crossroad (80 + 100 + 120): " + f.reason, f.node != null && f.meters == 300 && f.node.sides == 0x811);
        f = NaviGraph.find(car, 205, 90, m);
        check("find: right at 205 m, the phone reading 25 m long: the T at 180: " + f.reason, f.node != null && f.meters == 180);
        f = NaviGraph.find(car, 165, 90, m);
        check("find: ... and 15 m short: " + f.reason, f.node != null && f.meters == 180);
        f = NaviGraph.find(car, 245, 90, m);
        check("find: nothing around 237 m (30 either side): " + f.reason, f.node == null && f.reason.startsWith("no junction"));
        f = NaviGraph.find(car, 85, 90, m);
        check("find: the node at 80 m has only the road on: not a junction: " + f.reason, f.node == null);
        f = NaviGraph.find(car, 600, 90, m);
        check("find: past the fork it can't tell which way is straight on: " + f.reason, f.node == null && f.reason.contains("straight on"));
        f = NaviGraph.find(car, 480, 45, m);
        check("find: slight right at 480 m: the fork: " + f.reason, f.node != null && f.meters == 480);
        f = NaviGraph.find(car, 190, -90, m);
        check("find: left near the T, which has no left: its reason: " + f.reason,
                f.node == null && f.reason.startsWith("junction at 180 m") && f.reason.contains("no road"));

        // side streets close together: left only at 100 m, right only at 125 m and at 150 m (car at 0)
        Map2 c2 = new Map2();
        c2.link(1, 0, -50, 0, 0);
        c2.link(2, 0, 0, 0, 100);
        c2.link(3, 0, 100, 0, 125);
        c2.link(4, 0, 125, 0, 150);
        c2.link(5, 0, 150, 0, 400);
        c2.link(30, -80, 100, 0, 100);
        c2.link(31, 0, 125, 80, 125);
        c2.link(32, 0, 150, 80, 150);
        long[] car2 = at(1, 1, 50, 50);
        f = NaviGraph.find(car2, 118, 90, c2);
        check("find: a right at 118 m: the right turn at 125, not the nearer left-only street at 100: " + f.reason,
                f.node != null && f.meters == 125);
        f = NaviGraph.find(car2, 118, -90, c2);
        check("find: a left at 118 m: the left-only street at 100: " + f.reason, f.node != null && f.meters == 100);
        f = NaviGraph.find(car2, 146, 90, c2);
        check("find: a right at 146 m, two right turns as near (125 and 150): neither: " + f.reason,
                f.node == null && f.reason.startsWith("two junctions"));
        f = NaviGraph.find(car2, 172, 90, c2);
        check("find: a right at 172 m: the one at 150, well nearer than the other: " + f.reason, f.node != null && f.meters == 150);

        NaviGraph.Answer r = NaviGraph.answer(car, 300, 90, m);
        check("answer: right at the crossroad: its side streets: " + r.reason, r.sides == 0x811 && r.meters == 300);
        r = NaviGraph.answer(car, 300, -90, m);
        check("answer: left at the crossroad: the same roads", r.sides == 0x811);
        r = NaviGraph.answer(car, 180, 90, m);
        check("answer: right at the T: ahead and right", r.sides == (1 | 1 << 4));
        r = NaviGraph.answer(car, 190, -90, m);
        check("answer: left at the T, which has no left: 0: " + r.reason, r.sides == 0 && r.reason.contains("no road"));
        r = NaviGraph.answer(car, 300, 0, m);
        check("answer: straight on through the crossroad", r.sides == 0x811);
        r = NaviGraph.answer(car, 480, 45, m);
        check("answer: slight right at the fork (about 21 and -21 degrees): the right branch, both drawn: " + r.reason,
                r.sides == (1 << 1 | 1 << 14));
        r = NaviGraph.answer(car, 480, 90, m);
        check("answer: right at the fork: the right road is nearest but the left one too close? " + r.reason,
                r.sides == 0);
        r = NaviGraph.answer(at(1, 1, 120, 100), 300, 90, m);
        check("answer: no position on the link: 0", r.sides == 0);
        r = NaviGraph.answer(car, 300, 999, m);
        check("answer: no turn angle: 0", r.sides == 0 && "no turn".equals(r.reason));

        // match() on nodes the map can't make
        NaviGraph.Node two = node(-90, 90, 100);
        check("match: two roads on the right (90, 100): one sector: 0", NaviGraph.match(two, 90).sides == 0);
        NaviGraph.Node rr = node(0, 60, 120);
        NaviGraph.Answer x = NaviGraph.match(rr, 90);
        check("match: a plain right with two roads on the right (60, 120): 0: " + x.reason, x.sides == 0);
        x = NaviGraph.match(rr, 135);
        check("match: a sharp right picks the 120 one: " + x.reason, x.sides == rr.sides);
        x = NaviGraph.match(node(-81, -18, 101), -90);
        check("match: a left where the road ahead bends 18 degrees left: the left street, the bend is the way on: " + x.reason, x.sides != 0);
        x = NaviGraph.match(node(-81, -40, 101), -90);
        check("match: ... but not with a second left road 40 degrees off: " + x.reason, x.sides == 0);
        x = NaviGraph.match(node(0, -60), 45);
        check("match: a slight right where the only turning road is left: 0: " + x.reason, x.sides == 0);

        check("usable: this turn, fresh, about where the phone says; not another id, stale or far",
                NaviGraph.usable(3, 300, 3, 300, 100) && NaviGraph.usable(3, 290, 3, 300, 2000)
                        && NaviGraph.usable(3, 100, 3, 30, 100) && !NaviGraph.usable(3, 100, 3, 20, 100)
                        && !NaviGraph.usable(3, 300, 2, 300, 100) && !NaviGraph.usable(3, 300, 3, 300, 2600)
                        && !NaviGraph.usable(3, 300, 3, 400, 100) && !NaviGraph.usable(3, 300, 3, -1, 100));

        // MapJunction: AaTurns' questions to com.mnsoft.navi, its answers back
        Context c = new Context();
        MapJunction j = new MapJunction(c, new android.os.Handler());
        token = j.token;
        int s = j.sides(7, 300, 90);
        Intent q = c.sent.isEmpty() ? null : c.sent.get(0);
        check("MapJunction: asks com.mnsoft.navi about turn 7 at 300 m, right 90, answer to GPM: " + q,
                s == 0 && q != null && NaviGraph.ASK.equals(q.action) && "com.mnsoft.navi".equals(q.pkg)
                        && q.getIntExtra("id", 0) == 7 && q.getIntExtra("meters", 0) == 300 && q.getIntExtra("angle", 0) == 90
                        && "com.google.android.projection.sink".equals(q.getStringExtra("reply"))
                        && q.getLongExtra("token", 0) == j.token && j.token != 0);
        j.sides(7, 290, 90);
        check("MapJunction: not again within a second", c.sent.size() == 1);
        j.sides(8, 290, -90);
        check("MapJunction: a new turn asks at once", c.sent.size() == 2);
        BroadcastReceiver back = c.receivers.get(0);
        back.onReceive(c, answer(8, 0x811, 288).putExtra("token", j.token + 1));
        check("MapJunction: an answer without the question's token isn't taken (not the navigation's)", j.sides(8, 285, -90) == 0);
        Intent none = answer(8, 0x811, 288);
        none.extras.remove("token");
        back.onReceive(c, none);
        check("MapJunction: nor one with no token", j.sides(8, 285, -90) == 0);
        back.onReceive(c, answer(8, 0x811, 288));
        check("MapJunction: the answer for this turn is used", j.sides(8, 285, -90) == 0x811);
        back.onReceive(c, answer(8, 0, -1));
        check("MapJunction: a no-match answer doesn't clear it, held while the car closes in", j.sides(8, 200, -90) == 0x811);
        back.onReceive(c, answer(8, 0x11, 280));
        check("MapJunction: a newer match replaces it", j.sides(8, 190, -90) == 0x11);
        back.onReceive(c, answer(8, 0x811, 120));
        check("MapJunction: an answer far from the asked distance isn't taken", j.sides(8, 180, -90) == 0x11);
        j.askedAt -= 3000;
        back.onReceive(c, answer(8, 0x811, 288));
        check("MapJunction: nor one for a question over 2.5 s old", j.sides(8, 170, -90) == 0x11);
        check("MapJunction: not when the distance grows 100 m past where it matched", j.sides(8, 400, -90) == 0);
        check("MapJunction: not for another turn", j.sides(9, 285, -90) == 0);
        back.onReceive(c, answer(8, 0x811, 288));
        check("MapJunction: an answer for a turn it isn't asking about is dropped", j.held.sides == 0x11 && j.sides(9, 280, -90) == 0);
        int sentBefore = c.sent.size();
        j.close();
        j.close();
        check("MapJunction: close() unregisters its receiver (once) and stops asking",
                c.receivers.isEmpty() && j.sides(10, 200, 90) == 0 && c.sent.size() == sentBefore);
        Context again = new Context();
        for (int k = 0; k < 5; k++) new MapJunction(again, new android.os.Handler()).close();
        check("MapJunction: reconnects don't pile up receivers", again.receivers.isEmpty());

        NaviGraph.Held h = new NaviGraph.Held();
        check("Held: nothing at first", h.sides(1, 100) == 0);
        h.answer(1, 300, 0, 300, 100);
        check("Held: a no-match answer holds nothing", h.sides(1, 290) == 0);
        h.answer(1, 300, 0x811, 310, 100);
        check("Held: a usable match is held for its turn down to 0 m, and up to 100 m back",
                h.sides(1, 300) == 0x811 && h.sides(1, 0) == 0x811 && h.sides(1, 400) == 0x811
                        && h.sides(1, 401) == 0 && h.sides(2, 300) == 0 && h.sides(1, -1) == 0);

        // the navigation side's receiver: a question becomes NaviGraph.question
        hmgapi.hook.LoadedApp app = new hmgapi.hook.LoadedApp();
        NaviGraph.start(new hmgapi.hook.ModContext(), app, false, true);
        check("NaviGraph: questions only from a sender holding " + NaviGraph.ASKER + " (the system's apps): " + app.context.permissions,
                app.context.permissions.size() == 1 && NaviGraph.ASKER.equals(app.context.permissions.get(0)));
        app.context.receivers.get(0).onReceive(app.context, q);
        long[] got = NaviGraph.question;
        check("NaviGraph: the question is kept (id, meters, angle), who to answer and its token",
                got != null && got[1] == 7 && got[2] == 300 && got[3] == 90 && "com.google.android.projection.sink".equals(NaviGraph.asker)
                        && NaviGraph.askToken == j.token);
        Intent bad = new Intent(NaviGraph.ASK).putExtra("id", 1).putExtra("reply", "x; rm");
        NaviGraph.question = null;
        app.context.receivers.get(0).onReceive(app.context, bad);
        check("NaviGraph: a question with a strange reply package is ignored", NaviGraph.question == null);
        NaviGraph.Answer out = new NaviGraph.Answer();
        out.sides = 0x811;
        out.meters = 300;
        NaviGraph.reply(app.context, "com.google.android.projection.sink", 42L, 7, out);
        Intent sent = app.context.sent.get(0);
        check("NaviGraph: the answer goes to the asker only, with its token: " + sent,
                NaviGraph.ANSWER.equals(sent.action) && "com.google.android.projection.sink".equals(sent.pkg)
                        && sent.getIntExtra("sides", 0) == 0x811 && sent.getIntExtra("id", 0) == 7 && sent.getLongExtra("token", 0) == 42L);

        check("profiles: the US 260507 engine with its module tag 0x9550; others none",
                NaviGraph.profile("caa8d4c2120ec0a14aafc401c6720971") != null
                        && Integer.parseInt(NaviGraph.profile("caa8d4c2120ec0a14aafc401c6720971")[2]) == 0x9550
                        && NaviGraph.profile("0") == null);
        check("roadBit: 0 ahead, 90 right bit 4, -90 left bit 11, 180 none",
                NaviGraph.roadBit(0) == 1 && NaviGraph.roadBit(90) == 1 << 4 && NaviGraph.roadBit(-90) == 1 << 11 && NaviGraph.roadBit(180) == 0);
        System.exit(fail);
    }

    /** a node with roads at these angles */
    static NaviGraph.Node node(int... angles) {
        NaviGraph.Node n = new NaviGraph.Node();
        for (int a : angles) {
            NaviGraph.Road r = new NaviGraph.Road();
            r.angle = a;
            int bit = NaviGraph.roadBit(a);
            if ((n.sides & bit) != 0) n.overlap = true;
            n.sides |= bit;
            n.roads.add(r);
        }
        return n;
    }
}
