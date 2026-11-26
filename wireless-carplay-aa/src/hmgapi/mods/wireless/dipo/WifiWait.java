package hmgapi.mods.wireless.dipo;

/**
 * The Bluetooth attach went out, but there is no hotspot for the phone to join: wait for the
 * Wi-Fi interface, then attach again.
 *
 * Seen with a USB Wi-Fi adapter on a head unit with no Wi-Fi chip (Santa Fe TM23). The phone
 * connects over Bluetooth while the adapter is out, or DiPO's resetUSB() power-cycles the USB
 * port about 6 s after startCarPlaySession (9 to 11 s after the attach) and the adapter is
 * gone for about 2.5 s. DiPO's attempt fails ("Phone is not responding") and nothing starts
 * another one when the interface is back: CarPlay stays off until Bluetooth reconnects.
 *
 * CHECK_MS after an attach (past that reset) the hotspot is looked at: the interface is
 * there and hostapd runs. From then on, for as long as the attach is pending (Bluetooth up,
 * no CarPlay session), the interface is polled every POLL_MS: one file test, on every car.
 * Whenever the hotspot wasn't up at the first look, or the interface went away later, the
 * host detaches and attaches again once the interface has been there for SETTLE_MS (its
 * attach restarts the hotspot). So the adapter can be plugged in at any time.
 *
 * An attach asked for here can find the hotspot up and still bring no session: DiPO gives up
 * on its attempt after 6 s, and the hotspot can take longer (the port was power-cycled once
 * more). So after such an attach, JOIN_MS past the first look, the host attaches again. A
 * first attach never gets this: a phone that doesn't come is left alone, as on stock.
 *
 * An interface that is there but never gets a hotspot would be retried for ever: after
 * MAX_RETRIES attaches in a row the watch only goes on looking, and counts afresh once the
 * interface has really gone away (it was pulled and plugged in again), on a new Bluetooth
 * connection, or after a CarPlay session.
 *
 * No Android here (host-tested): Bridge is the host. Every method is synchronized; the host
 * runs the timers on one thread.
 */
final class WifiWait {
    interface Host {
        /** run r after delayMs */
        void schedule(Runnable r, long delayMs);

        /** the hotspot's network interface exists */
        boolean ifaceThere();

        /** the interface exists and hostapd runs */
        boolean hotspotUp();

        /** the Bluetooth attach is still up and no CarPlay session came of it */
        boolean pending();

        /** detach and attach again (attached() is called when the new attach goes out) */
        void attachAgain(int n);

        void log(String m);
    }

    static final long CHECK_MS = 12000, POLL_MS = 2000, SETTLE_MS = 3000, JOIN_MS = 8000;
    static final int MAX_RETRIES = 3;

    private final Host host;
    private long attach;   // counts attaches: the watch of an older one stops
    private int retries;   // attaches in a row that this asked for

    WifiWait(Host host) {
        this.host = host;
    }

    /** a new Bluetooth connection, or a CarPlay session that started: no watch, and the retries start over */
    synchronized void reset() {
        attach++;
        retries = 0;
    }

    /** an attach went out: look at the hotspot CHECK_MS from now */
    synchronized void attached() {
        host.schedule(new Watch(++attach), CHECK_MS);
    }

    private final class Watch implements Runnable {
        final long mine;
        boolean looked, waiting, seen, told;
        long up;   // how long the hotspot has been up with no session, since the first look

        Watch(long mine) {
            this.mine = mine;
        }

        @Override
        public void run() {
            synchronized (WifiWait.this) {
                if (mine != attach || !host.pending()) return;
                if (!waiting) {
                    // the first look is at the whole hotspot; after it only the interface, which is what a pulled adapter takes away
                    if (looked ? host.ifaceThere() : host.hotspotUp()) {
                        looked = true;
                        // An attach this asked for found the hotspot up, and still no session JOIN_MS later: DiPO
                        // gave up on its attempt (6 s) while the hotspot was still coming up, and nothing else
                        // asks the phone again. Never after a first attach: there a phone that doesn't come is left alone.
                        if (retries > 0 && retries < MAX_RETRIES && (up += POLL_MS) > JOIN_MS) {
                            retries++;
                            host.log("the hotspot is up but no CarPlay session came: attaching again (#" + retries + ")");
                            host.attachAgain(retries);
                            return;
                        }
                        host.schedule(this, POLL_MS);
                        return;
                    }
                    waiting = true;
                    host.log(looked ? "the Wi-Fi interface went away: waiting for it"
                            : "the hotspot isn't up " + CHECK_MS / 1000 + " s after the attach: waiting for the Wi-Fi interface");
                    looked = true;
                }
                boolean there = host.ifaceThere();
                if (!there) {
                    // really gone: when it comes back, that is a new try
                    retries = 0;
                    told = false;
                } else if (seen) {
                    if (retries < MAX_RETRIES) {
                        retries++;
                        host.log("the Wi-Fi interface is there: attaching again (#" + retries + ")");
                        host.attachAgain(retries);
                        return;
                    }
                    if (!told) {
                        told = true;
                        host.log("the Wi-Fi interface is there, but " + MAX_RETRIES + " attaches in a row brought no hotspot: left alone until it goes away and comes back");
                    }
                }
                seen = there;
                host.schedule(this, there && retries < MAX_RETRIES ? SETTLE_MS : POLL_MS);
            }
        }
    }
}
