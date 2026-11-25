package hmgapi.mods.wireless;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.SystemClock;

/**
 * Android Auto's side of the map's side streets (setting tbt_map_side_streets): AaTurns,
 * in GPM, asks the car navigation (NaviGraph in com.mnsoft.navi) about the junction of the
 * turn it's sending. An answer that's fresh and for that question (NaviGraph.usable) is
 * held for the turn (NaviGraph.Held) until a newer match, a new turn, or the car is no
 * longer closing on it. Asks at most about once a second, only within NaviGraph.MAX_METERS.
 * Everything comes and goes on AaTurns' handler. Broadcasts go to one package each
 * (com.mnsoft.navi, and back to this one). Only com.mnsoft.navi gets a question, so the
 * random token each one carries, which its answer must give back, is what makes an answer
 * the navigation's; the answer must also name this turn's id and be where its distance
 * says. NaviGraph takes questions only from a sender holding NaviGraph.ASKER (GPM runs as
 * the system). close() (AaTurns.end, the Android Auto session's end) unregisters it.
 */
final class MapJunction implements AaTurns.Junctions {
    final Context context;
    final String self;
    final NaviGraph.Held held = new NaviGraph.Held();
    /** given with every question, wanted back with its answer */
    final long token = new java.security.SecureRandom().nextLong();
    final BroadcastReceiver receiver;
    boolean closed;
    int askedId = -1, askedMeters = -1;
    long askedAt;

    MapJunction(Context context, Handler handler) {
        this.context = context;
        this.self = context.getPackageName();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (closed || i.getLongExtra("token", 0) != token || token == 0) return;
                int id = i.getIntExtra("id", -1);
                if (id != askedId) return;
                held.answer(id, askedMeters, i.getIntExtra("sides", 0) & 0x7fff, i.getIntExtra("meters", -1),
                        SystemClock.elapsedRealtime() - askedAt);
            }
        };
        context.registerReceiver(receiver, new IntentFilter(NaviGraph.ANSWER), null, handler);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException e) {
            // not registered
        }
    }

    @Override
    public int sides(int id, int meters, int angle) {
        long now = SystemClock.elapsedRealtime();
        if (closed) return 0;
        if (meters > 0 && meters <= NaviGraph.MAX_METERS && (id != askedId || now - askedAt >= 900)) {
            Intent i = new Intent(NaviGraph.ASK);
            i.setPackage(NaviGraph.NAVIGATION);
            i.putExtra("id", id);
            i.putExtra("meters", meters);
            i.putExtra("angle", angle);
            i.putExtra("reply", self);
            i.putExtra("token", token);
            context.sendBroadcast(i);
            askedId = id;
            askedMeters = meters;
            askedAt = now;
        }
        return held.sides(id, meters);
    }
}
