# How this mod and the Phone Turn-by-Turn mod work together

The Wireless CarPlay + Android Auto mod (`wireless-carplay-aa`, 3.0 or newer) and the
Phone Turn-by-Turn mod (`projection-tbt`) don't require each other. A car can have either
one, or both. Android Auto needs nothing between them. In the CarPlay app (DiPO) they
share one problem, and agree on four things to solve it.

> Not yet checked on a car. Both sides are host-tested; the agreement itself has only
> been read through, not run in DiPO.

## The problem

On wireless CarPlay, iAP2 starts on a Bluetooth session and this mod's bridge
then moves it to the CarPlay session's own channel. Two things follow:

1. The phone's route guidance subscription is made on the Bluetooth session and is lost
   in the move, so somebody has to ask for it again after every handover and re-attach.
2. The Bluetooth session and, on a re-attach, the channel's session end while CarPlay
   keeps running. A listener that takes those ends for the phone leaving clears the turns.

The bridge knows when a handover is done; the turn-by-turn mod knows whether route
guidance is flowing. Before the split this was one class talking to another.

## The agreement

All of it lives inside DiPO's process. Names are constants in each mod's `dipo/Dipo.java`.

| What | Who sets or sends it | Who reads it | Meaning |
|---|---|---|---|
| System property `hmgapi.wireless.carplay` | this mod (`Bridge`) | turn-by-turn mod | `"1"` while a wireless CarPlay session is up. The turn-by-turn mod's listener then ignores iAP2 disconnects of components 4 (Bluetooth CarPlay) and 5 (the CarPlay channel). |
| System property `hmgapi.tbt.carplay` | turn-by-turn mod | this mod | `"1"` once that mod's route guidance listener runs. Without it this mod asks only for Now Playing after a handover, and watches nothing. |
| Broadcast `hmgapi.mods.tbt.CP_RG`, extra `event` | turn-by-turn mod | this mod (`RgWatch`) | `started`: that mod restarted route guidance (a source change). `stopped`: DiPO stopped it. `update`: route guidance is arriving (at most one every 2 s). Sent only to DiPO's own package, and only accepted from senders holding `BLUETOOTH_PRIVILEGED`. |
| Thread names starting `hmgapi-cp-` | both | turn-by-turn mod | A route guidance Stop made on such a thread is a mod's own restart (Stop, 200 ms, Start). That mod's hook on DiPO's Stop skips those, so only DiPO's own Stop clears the turns. |

## What each mod does with it

- **This mod:** after every "CarPlay channel ready", its watchdog asks the phone for
  route guidance again (and Now Playing), then checks at 3, 10, 25 and 55 s that an
  `update` arrived, asking again if not. It does this on its own iAP2 connection, so it
  works whichever mod started first.
- **The turn-by-turn mod:** keeps its own restart for a change of navigation app, tells
  the watchdog about it, and never needs to know what a handover is.

## Without the other mod

- **Without this mod (wired CarPlay):** nobody reads the property or the broadcast. The
  disconnect of the one wired session ends the turns, as it should.
- **No turn-by-turn mod:** this mod's watchdog stays off; Now Playing is still asked for
  after a handover.

## Why not `requires`

HMG-API's `requires` would turn the turn-by-turn mod off on a car without this mod, and
`ModContext.use()` only works for a required mod. An optional requirement
(`"optional": true`) is honoured by the NEMESIS package builder but not by HMG-API's
engine or hook runtime (as of 1.1.0-rc.10). Once HMG-API has optional requirements, this
agreement can become a small published API instead.
