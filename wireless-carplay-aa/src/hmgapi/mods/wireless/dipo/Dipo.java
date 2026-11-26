package hmgapi.mods.wireless.dipo;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceBTCMManager;
import com.tcc.iap2.IIap2Service;

import hmgapi.hook.Car;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * The Wireless mod in DiPO (the CarPlay app): its app part, linked against DiPO's
 * libraries (com.tcc.iap2, com.broadcom.bt). Wireless.appLoaded starts it.
 *
 * Native patches (in memory, the files stay stock): libCarPlayCore takes Wi-Fi sessions
 * ("bridge"); libCarPlayAudio offers AAC/Opus on Wi-Fi and libCarPlayUtils sends its
 * audio conversion to the mod's libleafiaaac.so ("audio").
 *
 * Bridge ("bridge"): wireless CarPlay's Bluetooth bridge (Bridge), plus what DiPO needs
 * for it: its own wireless CarPlay off (that's Hyundai's, not this one), its iAP2
 * listener ignoring the bridge's sessions, the reconnect crash fix and the first-connect
 * notice fix.
 *
 * Now Playing ("now_playing", with the bridge): song, artist and album art on wireless
 * CarPlay, which DiPO only asks the phone for on USB (nowPlaying).
 *
 * Turn-by-turn is the projection-tbt mod's (AwsomeFox/hmg-api-turn-by-turn), wired or
 * wireless. On wireless, iAP2 moves from the Bluetooth session to the CarPlay channel and
 * the phone's route guidance subscription is lost with it, so this mod still asks the
 * phone for route guidance again after every handover and re-attach, and watches that it
 * flows (RgWatch). The two mods don't require each other; in DiPO they agree on
 * (docs/tbt-contract.md):
 *  - WIRELESS_UP: a system property of this process, "1" while a wireless CarPlay session
 *    is up (Bridge), so the turn-by-turn mod doesn't take the handover's iAP2 disconnects
 *    (components 4 and 5) for the phone leaving.
 *  - TBT_UP: the turn-by-turn mod's, "1" while its route guidance listener runs. Without
 *    it there is no route guidance to ask for or watch (Now Playing is still asked).
 *  - RG_EVENT: its broadcast inside DiPO: "started" (it restarted route guidance),
 *    "stopped" (DiPO stopped it) and "update" (it is flowing), for RgWatch.
 *  - Route guidance restarts run on threads named OWN_THREADS…, so the turn-by-turn mod
 *    can tell them from DiPO's own stop.
 */
public final class Dipo {
    private Dipo() {}

    static final String IAP2_ACTION = "com.remote.service.IAP2SERVICE";
    static final String CP_FIRST_CONNECTED = "com.android.app.PhoneProjection.CP_FIRST_CONNECTED";
    static final String[] RG_COMPONENTS = { "RouteGuidanceComponent0" };
    /** set by this mod in this process: "1" while a wireless CarPlay session is up */
    public static final String WIRELESS_UP = "hmgapi.wireless.carplay";
    /** set by the turn-by-turn mod in this process: "1" while its route guidance listener runs */
    public static final String TBT_UP = "hmgapi.tbt.carplay";
    /** from the turn-by-turn mod, inside DiPO only: extra "event" = started, stopped or update */
    public static final String RG_EVENT = "hmgapi.mods.tbt.CP_RG";
    static final String RG_PERMISSION = "android.permission.BLUETOOTH_PRIVILEGED";
    /** threads of HMG-API mods that restart route guidance themselves (the turn-by-turn mod skips their stops) */
    public static final String OWN_THREADS = "hmgapi-cp-";

    static ModContext mod;
    static LoadedApp app;
    static volatile IIap2Service iap2;
    static boolean bridge, nowPlaying;
    /** Now Playing asked again this long after the CarPlay channel session comes up */
    static final long NP_DELAY = 1500;
    static Handler npHandler;
    /** DiPO's iAP2 manager from the CarPlay channel session's connect (Now Playing asked again through it after a re-attach) */
    static volatile Object npManager;
    /** asks for route guidance after every comp-5 ready, and the watchdog after each Start (RgWatch) */
    static RgWatch rgWatch;
    private static Handler rgHandler;
    /** onSessionDetails' device id while it runs, and whether its address lookup came back empty */
    static final ThreadLocal<String> SESSION = new ThreadLocal<String>();
    static final ThreadLocal<Boolean> NO_ADDRESS = new ThreadLocal<Boolean>();

    static void log(String m) {
        mod.log(m);
    }

    static void error(String m, Throwable t) {
        mod.error(m, t);
    }

    /** Wireless.appLoaded, in DiPO's process */
    public static void start(ModContext m, LoadedApp a) {
        mod = m;
        app = a;
        bridge = m.settingBoolean("bridge", true);
        nowPlaying = bridge && m.settingBoolean("now_playing", true);
        CarPlayLog.on = m.settingBoolean("carplay_log", false);
        CarPlayLog.apply("start");
        // the CarPlay libraries, patched in memory once DiPO loads them (files stay stock)
        if (bridge) m.nativePatch(a, "libCarPlayCore.so");   // AirPlay sessions over Wi-Fi
        if (m.settingBoolean("audio", true)) {
            m.nativePatch(a, "libCarPlayAudio.so");   // offer AAC and Opus on Wi-Fi
            m.nativePatch(a, "libCarPlayUtils.so");   // its AudioConverter calls go to libleafiaaac.so
        }
        if (!bridge) return;
        rgWatch();
        bridge();
        Intent i = new Intent(IAP2_ACTION);
        boolean bound = a.context.bindService(i, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder b) {
                IIap2Service s = IIap2Service.Stub.asInterface(b);
                iap2 = s;
                Bridge.onIap2Bound(s);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                iap2 = null;
                log("DiPO: iAP2 server gone");
            }
        }, Context.BIND_AUTO_CREATE);
        log("DiPO: iAP2 server " + (bound ? "binding" : "can't be bound"));
    }

    /**
     * Stop, then Start, so the phone sends its whole current route guidance state again.
     * Only from RgWatch's thread (named OWN_THREADS…): the turn-by-turn mod's hook on DiPO's
     * stop skips stops made on such a thread.
     */
    static void restartRouteGuidance() {
        IIap2Service s = iap2;
        if (s == null) return;
        try {
            s.Iap2SvcStopRouteGuidanceUpdate(RG_COMPONENTS);
            Thread.sleep(200);
            s.Iap2SvcStartRouteGuidanceUpdate(RG_COMPONENTS, true, true);
        } catch (Throwable t) {
            // the session went away meanwhile
        }
    }

    /** the turn-by-turn mod's route guidance listener runs in this process */
    static boolean tbtUp() {
        return "1".equals(System.getProperty(TBT_UP));
    }

    /** Bridge: a wireless CarPlay session came up or ended (for the turn-by-turn mod) */
    static void wirelessUp(boolean up) {
        System.setProperty(WIRELESS_UP, up ? "1" : "0");
    }

    private static void bridge() {
        final ClassLoader cl = app.classLoader;
        Bridge.cl = cl;
        Bridge.context = app.context;
        // Broadcom's manager: DiPO registers its listener once the service is connected; the bridge's follows
        mod.hook(cl, "dipo.btcm.register_listener", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (!Bridge.isOurs(p.args[0])) Bridge.onBtcmReady((HfDeviceBTCMManager) p.thisObject);
            }
        });
        // DiPO's CarPlay callbacks (observed)
        mod.hook(cl, "dipo.carplay.session_created", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                CarPlayLog.apply("session");
                Bridge.onCpSessionCreated(p.thisObject);
            }
        });
        mod.hook(cl, "dipo.carplay.session_started", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Bridge.onCpSessionStarted((Integer) p.args[0]);
            }
        });
        mod.hook(cl, "dipo.carplay.session_finalized", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Bridge.onCpSessionFinalized((Integer) p.args[0]);
            }
        });
        mod.hook(cl, "dipo.carplay.iap_message", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Bridge.onCpIapIn(p.thisObject, (byte[]) p.args[0], (Integer) p.args[1]);
            }
        });
        // the CarPlay library's disableBluetooth: DiPO disconnects the phone's whole Bluetooth link, as for
        // wired CarPlay. On a wireless session some phones then end it after about a minute (#5), so the
        // link stays up while wireless CarPlay runs (persist.ev6.cpwl.keepbt=0: DiPO's way). A car whose
        // map has no such point yet (made from a Car Report older than HMG-API 1.1.0) goes without: the
        // hooks after this one must still go in.
        if (Points.has("dipo.carplay.disable_bluetooth")) {
            mod.hook(cl, "dipo.carplay.disable_bluetooth", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    if (!Bridge.sCpWireless || "0".equals(Bridge.prop("persist.ev6.cpwl.keepbt", "1"))) return;
                    log("DiPO: Bluetooth kept up for wireless CarPlay (disableBluetooth skipped)");
                    p.setResult(null);
                }
            });
        } else {
            log("DiPO: this car's map has no dipo.carplay.disable_bluetooth: Bluetooth isn't kept up for wireless CarPlay");
        }
        // DiPO's own wireless CarPlay (Hyundai's) stays off: the feature file is there for the other apps
        mod.hook(cl, "dipo.utils.is_support_wireless_carplay", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                p.setResult(false);
            }
        });
        // DiPO's iAP2 listener: a Bluetooth CarPlay session (4) is a phone like USB (1); the
        // CarPlay-channel session (5) only turns its notifications on; the bridge's sessions
        // ending, and the channel session's identification, aren't DiPO's business
        mod.hook(cl, "dipo.iap2_listener.connected", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                int c = (Integer) p.args[0];
                if (c == Bridge.COMPONENT_CARPLAY_CLIENT) {
                    Object manager = Points.get(cl, "dipo.iap2_client.manager", Points.get(cl, "dipo.iap2_listener.owner", p.thisObject));
                    Points.call(cl, "dipo.iap2.set_notify_on", null, manager, true);
                    npManager = manager;
                    if (nowPlaying) nowPlaying(manager, true);
                    p.setResult(null);
                } else if (c == Bridge.COMPONENT_BT_CARPLAY) {
                    p.args[0] = Bridge.COMPONENT_USB;
                }
            }
        });
        mod.hook(cl, "dipo.iap2_listener.disconnected", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                int c = (Integer) p.args[0];
                if (nowPlaying && c == Bridge.COMPONENT_CARPLAY_CLIENT) {
                    nowPlaying(Points.get(cl, "dipo.iap2_client.manager", Points.get(cl, "dipo.iap2_listener.owner", p.thisObject)), false);
                }
                if (Bridge.dipoIgnoreDisconnect(c)) p.setResult(null);
            }
        });
        mod.hook(cl, "dipo.iap2_listener.notify", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (Bridge.dipoDropNoti((Integer) p.args[0])) p.setResult(null);
            }
        });
        reconnectFix(cl);
        firstConnectNotice();
        {
            // the iAP2 server's word that a session's link failed (the CarPlay channel's is attached again), or that a
            // session is identified and authenticated (a re-attach counts only then, and route guidance is asked for
            // again after every one: Bridge, Reattach, RgWatch). Always on now: RgWatch needs it with re-attach off too.
            IntentFilter f = new IntentFilter(Bridge.ACTION_IAP2_CONNECTFAIL);
            f.addAction(Bridge.ACTION_IAP2_CONNECTSUCCESS);
            app.context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    int comp = i.getIntExtra(Bridge.EXTRA_IAP2_COMPONENT, -1);
                    if (Bridge.ACTION_IAP2_CONNECTSUCCESS.equals(i.getAction())) Bridge.onIap2ConnectSuccess(comp);
                    else Bridge.onIap2ConnectFail(comp);
                }
            }, f);
        }
        if (nowPlaying) {
            HandlerThread t = new HandlerThread("hmgapi-cp-np");
            t.start();
            npHandler = new Handler(t.getLooper());
        }
        log("bridge: wireless CarPlay Bluetooth bridge in DiPO");
    }

    /**
     * Now Playing on wireless CarPlay. DiPO asks the phone for Now Playing (Iap2SvcNowPlayingStart)
     * only when a USB CarPlay session starts. On wireless, iAP2 then moves from the Bluetooth
     * session to the CarPlay channel, whose identification the bridge keeps from DiPO, so a
     * subscription made before the move gets one update, then nothing. Once the channel
     * session is up: stop and start Now Playing again, NP_DELAY later and on its own thread (a
     * binder call into the iAP2 server from its own callback could deadlock); stopped when the
     * channel session ends. Through DiPO's own iAP2 connection, so its listener gets the
     * updates as on USB: the manager's one IIap2Service field (EV6 and Ioniq 6 260507), or
     * HMG-API's own binding when there isn't one.
     */
    private static void nowPlaying(final Object manager, final boolean start) {
        npHandler.removeCallbacksAndMessages(null);
        npHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                IIap2Service s = dipoIap2(manager);
                if (s == null) {
                    log("Now Playing: no iAP2 connection");
                    return;
                }
                try {
                    s.Iap2SvcNowPlayingStop();
                    if (start) s.Iap2SvcNowPlayingStart();
                    log(start ? "Now Playing: CarPlay channel session up, asking the phone again" : "Now Playing: CarPlay channel session ended, stopped");
                } catch (Throwable t) {
                    error("Now Playing", t);
                }
            }
        }, start ? NP_DELAY : 0);
    }

    /**
     * After a re-attach of the CarPlay channel's iAP2 session (Bridge, Reattach): the phone is
     * asked for route guidance (Stop, then Start, as a source change does; only with the
     * turn-by-turn mod here) and Now Playing again.
     */
    static void askAgain() {
        restartOnRgThread();
        if (nowPlaying && npHandler != null) nowPlaying(npManager, true);
    }

    private static void restartOnRgThread() {
        Handler h = rgHandler;
        if (h == null || !tbtUp()) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                restartRouteGuidance();
            }
        });
    }

    private static void rgWatch() {
        HandlerThread t = new HandlerThread(OWN_THREADS + "rg");
        t.start();
        rgHandler = new Handler(t.getLooper());
        rgWatch = new RgWatch(new RgWatch.Host() {
            @Override
            public long now() {
                return android.os.SystemClock.elapsedRealtime();
            }

            @Override
            public Object schedule(Runnable r, long delayMs) {
                rgHandler.postDelayed(r, delayMs);
                return r;
            }

            @Override
            public void cancel(Object token) {
                rgHandler.removeCallbacks((Runnable) token);
            }

            @Override
            public void askAgain() {
                Dipo.askAgain();
            }

            @Override
            public void restart() {
                restartOnRgThread();
            }

            @Override
            public void log(String m) {
                Dipo.log("CarPlay: " + m);
            }
        }, tbtUp());
        // the turn-by-turn mod's word on route guidance (it may start after this mod: any word turns the watch on)
        app.context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                RgWatch w = rgWatch;
                String e = i.getStringExtra("event");
                if (w == null || e == null) return;
                w.watch(true);
                if ("update".equals(e)) w.update();
                else if ("started".equals(e)) w.started();
                else if ("stopped".equals(e)) w.stopped();
            }
        }, new IntentFilter(RG_EVENT), RG_PERMISSION, rgHandler);
    }

    /** Bridge: the CarPlay channel's session (RgWatch) */
    static void rgSessionUp() {
        RgWatch w = rgWatch;
        if (w != null) w.sessionUp();
    }

    static void rgSessionEnd() {
        RgWatch w = rgWatch;
        if (w != null) w.sessionEnd();
    }

    static void rgLinkDown(String why) {
        RgWatch w = rgWatch;
        if (w != null) w.linkDown(why);
    }

    /** Reattach's ask after a re-attach is ready: through RgWatch (logged, watched) */
    static void rgAsk(String how) {
        RgWatch w = rgWatch;
        if (w != null) w.ask(how);
        else askAgain();
    }

    /** DiPO's iAP2 connection: the one IIap2Service field of its iAP2 manager (else HMG-API's) */
    private static IIap2Service dipoIap2(Object manager) {
        if (manager != null) {
            for (Class<?> c = manager.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() != IIap2Service.class) continue;
                    try {
                        f.setAccessible(true);
                        Object s;
                        synchronized (manager) {
                            s = f.get(manager);
                        }
                        if (s != null) return (IIap2Service) s;
                    } catch (Throwable t) {
                        error("Now Playing: DiPO's iAP2 connection", t);
                    }
                }
            }
        }
        return iap2;
    }

    /**
     * DiPO crash on a wireless reconnect (stock bug): an iAP2 drop clears the phone's iAP2
     * Bluetooth address, and a wireless session can restart before iAP2 is back;
     * onSessionDetails upper-cases that address unchecked. As the bridge build: when it's
     * missing there, the session's own device id stands in, or (no id either) the stored
     * CarPlay address is kept. Only inside onSessionDetails; every other caller checks.
     */
    private static void reconnectFix(final ClassLoader cl) {
        mod.hook(cl, "dipo.session_listener.session_details", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                SESSION.set(p.args[0] == null ? null : (String) p.args[0]);
            }

            @Override
            protected void afterHookedMethod(Param p) {
                SESSION.remove();
                NO_ADDRESS.remove();
            }
        });
        mod.hook(cl, "dipo.iap2.bt_address", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                String id = SESSION.get();
                if (id == null || p.getResult() != null) return;
                p.setResult(id);
                if (id.isEmpty()) NO_ADDRESS.set(Boolean.TRUE);
                log("bridge: no iAP2 Bluetooth address on session details, using the session's (" + (id.isEmpty() ? "none, keeping the stored one" : id) + ")");
            }
        });
        mod.hook(cl, "dipo.bt.set_carplay_bt_address", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (NO_ADDRESS.get() != null) p.setResult(null);
            }
        });
    }

    /**
     * CarPlay's first-connect notice: DiPO looks it up by the phone's Bluetooth address
     * (before iAP2 identification), but saves it under the iPhone's serial, so on a
     * wireless connect it came up every time. As the bridge build: also saved under the
     * Bluetooth address of the last iAP2 session.
     */
    private static void firstConnectNotice() {
        app.context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String id = Bridge.sLastBtId;
                if (id == null) return;
                try {
                    SharedPreferences sp = c.getSharedPreferences("pref_dipo", 0);
                    if (!sp.edit().putBoolean(id, true).commit()) log("bridge: first-connect notice not saved for " + id);
                } catch (Throwable t) {
                    error("bridge: first-connect notice", t);
                }
            }
        }, new IntentFilter(CP_FIRST_CONNECTED));
    }
}
