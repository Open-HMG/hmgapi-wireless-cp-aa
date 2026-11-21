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
 * turn it's sending, and uses the answer once it's back, fresh and for that turn
 * (NaviGraph.usable). Asks at most about once a second, only within NaviGraph.MAX_METERS.
 * Everything comes and goes on AaTurns' handler. Broadcasts go to one package each
 * (com.mnsoft.navi, and back to this one); the worst a stray one can do is a wrong
 * drawing for a second, the answer must name this turn's id and distance.
 */
final class MapJunction implements AaTurns.Junctions {
    final Context context;
    final String self;
    int askedId = -1;
    long askedAt;
    int answerId = -1, answerSides, answerMeters = -1;
    long answerAt;

    MapJunction(Context context, Handler handler) {
        this.context = context;
        this.self = context.getPackageName();
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                answerId = i.getIntExtra("id", -1);
                answerSides = i.getIntExtra("sides", 0) & 0x7fff;
                answerMeters = i.getIntExtra("meters", -1);
                answerAt = SystemClock.elapsedRealtime();
            }
        }, new IntentFilter(NaviGraph.ANSWER), null, handler);
    }

    @Override
    public int sides(int id, int meters, int angle) {
        long now = SystemClock.elapsedRealtime();
        if (meters > 0 && meters <= NaviGraph.MAX_METERS && (id != askedId || now - askedAt >= 900)) {
            Intent i = new Intent(NaviGraph.ASK);
            i.setPackage(NaviGraph.NAVIGATION);
            i.putExtra("id", id);
            i.putExtra("meters", meters);
            i.putExtra("angle", angle);
            i.putExtra("reply", self);
            context.sendBroadcast(i);
            askedId = id;
            askedAt = now;
        }
        return NaviGraph.usable(id, meters, answerId, answerMeters, now - answerAt) ? answerSides : 0;
    }
}
