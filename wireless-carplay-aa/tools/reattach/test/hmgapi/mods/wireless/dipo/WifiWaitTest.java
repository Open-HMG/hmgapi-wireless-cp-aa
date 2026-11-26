package hmgapi.mods.wireless.dipo;

import java.util.ArrayList;
import java.util.List;

/**
 * Host test of WifiWait (the attach again once the hotspot's interface is back) on a fake
 * clock: a car whose hotspot is up (nothing happens), an adapter plugged in late or an hour
 * late, an adapter power-cycled by DiPO's resetUSB, an interface with no hostapd behind it,
 * one that flickers, an adapter pulled long after the attach, the limit on attaches in a row
 * and what starts the count over, and a CarPlay session or a new attach ending the watch.
 */
public final class WifiWaitTest {
    static int fail, oks;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (ok) oks++;
        else fail = 1;
    }

    /** a fake Bridge: a clock, timers, the interface, hostapd and the session state, every retry and log line recorded */
    static class Fake implements WifiWait.Host {
        long t;
        boolean iface, hostapd, pending = true;
        final List<Object[]> due = new ArrayList<Object[]>();   // {when, runnable}
        final List<String> logs = new ArrayList<String>();
        final List<Long> retryAt = new ArrayList<Long>();

        @Override
        public void schedule(Runnable r, long delayMs) {
            due.add(new Object[] { t + delayMs, r });
        }

        @Override
        public boolean ifaceThere() {
            return iface;
        }

        @Override
        public boolean hotspotUp() {
            return iface && hostapd;
        }

        @Override
        public boolean pending() {
            return pending;
        }

        @Override
        public void attachAgain(int n) {
            retryAt.add(t);
        }

        @Override
        public void log(String m) {
            logs.add(m);
        }

        /** the clock forward to `to`, running every timer that comes due, in order */
        void runTo(long to) {
            while (true) {
                Object[] next = null;
                for (Object[] d : due) if ((Long) d[0] <= to && (next == null || (Long) d[0] < (Long) next[0])) next = d;
                if (next == null) break;
                due.remove(next);
                t = (Long) next[0];
                ((Runnable) next[1]).run();
            }
            t = to;
        }

        boolean logged(String part) {
            return count(part) > 0;
        }

        int count(String part) {
            int n = 0;
            for (String l : logs) if (l.contains(part)) n++;
            return n;
        }
    }

    public static void main(String[] a) {
        final long check = WifiWait.CHECK_MS, settle = WifiWait.SETTLE_MS, poll = WifiWait.POLL_MS;
        final long hour = 60 * 60 * 1000;

        // a car whose Wi-Fi works: the hotspot is up at the check (the phone may still be joining), and stays
        Fake h = new Fake();
        WifiWait w = new WifiWait(h);
        h.iface = h.hostapd = true;
        w.attached();
        h.runTo(hour);
        check("hotspot up for an hour: no retry, no log", h.retryAt.isEmpty() && h.logs.isEmpty());
        h.pending = false;
        h.runTo(hour + 10000);
        check("...and no timer left once a CarPlay session is up (or Bluetooth went)", h.due.isEmpty());

        // the adapter is out at the attach and plugged in 20 s later
        h = new Fake();
        w = new WifiWait(h);
        w.attached();
        h.runTo(check);
        check("no hotspot at the check: waiting, logged", h.logged("the hotspot isn't up 12 s after the attach: waiting for the Wi-Fi interface") && h.retryAt.isEmpty());
        h.runTo(20000);
        check("still out: no retry", h.retryAt.isEmpty());
        h.iface = true;
        h.runTo(40000);
        check("plugged in: one retry", h.retryAt.size() == 1 && h.logged("attaching again (#1)"));
        check("...only after it stayed for the settle time", h.retryAt.get(0) >= 20000 + settle && h.retryAt.get(0) <= 20000 + poll + settle);
        check("...and that watch is over (the new attach brings its own)", h.due.isEmpty());

        // ...or an hour later: there is no time limit
        h = new Fake();
        w = new WifiWait(h);
        w.attached();
        h.runTo(hour);
        check("out for an hour: still waiting, no retry", h.retryAt.isEmpty() && !h.due.isEmpty());
        h.iface = true;
        h.runTo(hour + 10000);
        check("plugged in after an hour: one retry", h.retryAt.size() == 1);

        // DiPO's resetUSB: the hotspot is up at the attach, the port is power-cycled 9.5 s later for 2.5 s
        h = new Fake();
        w = new WifiWait(h);
        h.iface = h.hostapd = true;
        w.attached();
        h.runTo(9500);
        h.iface = h.hostapd = false;
        h.runTo(12000);
        h.iface = true;   // the adapter is back; hostapd is not
        h.runTo(40000);
        check("port power-cycled before the check: one retry", h.retryAt.size() == 1);

        // the interface is there but nothing runs on it (the adapter came after the hotspot was started)
        h = new Fake();
        w = new WifiWait(h);
        h.iface = true;
        w.attached();
        h.runTo(check + settle);
        check("interface with no hostapd at the check: one retry, the settle time later", h.retryAt.size() == 1 && h.retryAt.get(0) == check + settle);

        // an interface that comes and goes before it settles is not taken
        h = new Fake();
        w = new WifiWait(h);
        w.attached();
        h.runTo(14500);
        h.iface = true;
        h.runTo(16500);
        h.iface = false;
        h.runTo(25000);
        check("there for 2 s only: no retry yet", h.retryAt.isEmpty());
        h.iface = true;
        h.runTo(35000);
        check("...one once it stays", h.retryAt.size() == 1);

        // the hotspot is up at the check; the adapter is pulled 5 minutes later and plugged in a minute after that
        h = new Fake();
        w = new WifiWait(h);
        h.iface = h.hostapd = true;
        w.attached();
        h.runTo(300000);
        h.iface = h.hostapd = false;
        h.runTo(360000);
        check("pulled long after the attach: noticed, no retry while it is out", h.logged("the Wi-Fi interface went away: waiting for it") && h.retryAt.isEmpty());
        h.iface = true;
        h.runTo(370000);
        check("...plugged in again: one retry", h.retryAt.size() == 1);

        // an interface that never gets a hotspot: MAX_RETRIES attaches in a row, then it is only watched
        h = new Fake();
        w = new WifiWait(h);
        h.iface = true;
        for (int i = 0; i < WifiWait.MAX_RETRIES + 1; i++) {
            w.attached();
            h.runTo(h.t + check + settle);
        }
        h.runTo(h.t + hour);
        check("at most " + WifiWait.MAX_RETRIES + " attaches in a row, said once", h.retryAt.size() == WifiWait.MAX_RETRIES && h.count("attaches in a row brought no hotspot") == 1);
        h.iface = false;
        h.runTo(h.t + 10000);
        h.iface = true;
        h.runTo(h.t + 10000);
        check("pulled and plugged in again: the count starts over", h.retryAt.size() == WifiWait.MAX_RETRIES + 1);
        for (int i = 0; i < WifiWait.MAX_RETRIES; i++) {
            w.attached();
            h.runTo(h.t + check + settle);
        }
        check("...and stops at the limit again", h.retryAt.size() == 2 * WifiWait.MAX_RETRIES);
        w.reset();
        w.attached();
        h.runTo(h.t + check + settle);
        check("a new Bluetooth connection starts the count over too", h.retryAt.size() == 2 * WifiWait.MAX_RETRIES + 1);

        // an attach this asked for finds the hotspot up, but no session comes of it (DiPO's attempt was over before the hotspot was up)
        h = new Fake();
        w = new WifiWait(h);
        h.iface = true;
        w.attached();
        h.runTo(check + settle);
        h.hostapd = true;   // the retry's attach started the hotspot
        long t0 = h.t;
        w.attached();
        h.runTo(t0 + check + WifiWait.JOIN_MS - 1);
        check("hotspot up after a retry, no session yet: not asked again before the join time is over", h.retryAt.size() == 1);
        h.runTo(t0 + check + WifiWait.JOIN_MS);
        check("...then asked again", h.retryAt.size() == 2 && h.logged("the hotspot is up but no CarPlay session came: attaching again (#2)"));
        for (int i = 0; i < 2; i++) {
            w.attached();
            h.runTo(h.t + hour);
        }
        check("...up to the limit of " + WifiWait.MAX_RETRIES + " in a row", h.retryAt.size() == WifiWait.MAX_RETRIES);

        // ...and a session that does come ends it
        h = new Fake();
        w = new WifiWait(h);
        h.iface = true;
        w.attached();
        h.runTo(check + settle);
        h.hostapd = true;
        w.attached();
        h.runTo(h.t + check + 2000);
        h.pending = false;
        h.runTo(h.t + hour);
        check("hotspot up after a retry and a session came: not asked again", h.retryAt.size() == 1 && h.due.isEmpty());

        // a CarPlay session came of the attach (or Bluetooth went) while waiting: the watch stops
        h = new Fake();
        w = new WifiWait(h);
        w.attached();
        h.runTo(check + 1000);
        h.pending = false;
        h.iface = true;
        h.runTo(60000);
        check("not pending any more: no retry, no timer left", h.retryAt.isEmpty() && h.due.isEmpty());

        // a newer attach takes over from the older watch
        h = new Fake();
        w = new WifiWait(h);
        w.attached();
        h.runTo(check + 1000);
        w.attached();
        h.runTo(20000);
        h.iface = true;
        h.runTo(60000);
        check("two attaches, one watch left: one retry", h.retryAt.size() == 1);

        System.out.println(fail == 0 ? "WifiWait: " + oks + " checks ok" : "WifiWait: FAILED");
        System.exit(fail);
    }
}
