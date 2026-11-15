# Palisade cluster and HUD simulator

A local workbench for replaying navigation events through the **current production
`CpTurns.java` and `ClusterHandoff.java` source**, then viewing independently decoded
output as schematic cluster and HUD displays. It never connects to a car or writes
an installation package. Python's standard library and a JDK are the only dependencies.

## Start

From the repository root:

```sh
python3 tools/palisade/simulator/run.py
```

Open <http://127.0.0.1:8765>. The launcher compiles the current sources into a temporary
directory, starts a loopback-only server, and cleans up when stopped with Ctrl-C.
Restart the launcher after changing Java sources; reloading the browser picks up
viewer changes. Each replay runs in a fresh Java process. No fixture or import is
saved automatically.

The launcher finds `JAVA_HOME`, the existing private Temurin toolchain, or a working
`javac` on `PATH`. To select a JDK explicitly or change the port:

```sh
python3 tools/palisade/simulator/run.py --java-home /path/to/jdk17 --port 8766
```

Use the scenario menu, event list, previous/next buttons, or timeline to inspect a
drive. Playback supports 0.25×–4× speed. Space toggles playback and arrow keys step
when focus is outside a control. The Output tab includes complete call arguments
and untrimmed F1/F2 frame hex; Input and Trace expose the corresponding event and
production diagnostics. The selection inspector is diagnostic only: it does not
feed the display model.

Scenario JSON opens an editable input. Apply runs a new Java replay; imports accept
the same input format. Export replay downloads inputs, raw outputs, diagnostics,
snapshots, checks, model assumptions and compiled source hashes. A replay export
is an evidence artifact, not an importable scenario; use its `scenario` member to
replay it. Route names in custom inputs remain local, but an exported file contains
those strings, so keep private captures in ignored `private/` or `out/` storage.

## Included scenarios

| Scenario | Purpose |
| --- | --- |
| Two close turns | Primary countdown, explicit 250 ft following distance, promotion, single turn and cancellation |
| Route without turn details | Destination/trip updates independent of directional icon availability |
| Lane selection and visibility | Hidden prefetch, exact lane selection, future events and missing events |
| Reroute and late messages | Unknown-primary clearing and cancellation that late details cannot undo |
| Distance ambiguity · failing control | Deliberate failure when vehicle-relative raw data is interpreted as a between-turn measurement |
| Turn icons and primary countdown · #4 | Raw-only countdown, all five formatted units, promotion, unavailable distance, U-turn, highway change and arrival |
| Roundabouts and missing geometry · #7 | Numbered exits with supplied geometry, existing traffic-side mappings, missing angles and unsupported fallback |
| Returned roundabout angles -87 and -15 · #7 | Both vehicle-failure inputs retain primary icons/countdown, then promote and clear |

The positive scenarios check independently decoded display expectations. The
negative control has two intentionally failed expectations: the modeled HUD receives
1.0 mi where the independently defined route geometry requires 250 ft. Its input
semantics are explicitly synthetic; this does not identify the semantics of an
uncorrelated car capture or establish a vehicle fix.

## Input contract: version 1

All inputs have `schemaVersion: 1`, `events`, and optional `id`, `title`,
`description`, `provenance` and `units` (`mi`, default, or `km`). Each event has an
integer `at` in monotonic milliseconds, a `kind`, and optional `label`, `truth` and
`expect`. Timestamps must be nondecreasing; same-time events execute in array order.
Omitted fields represent partial updates, explicit zero is supplied data, empty
selected lists clear a selection, and empty strings clear supplied text fields.

| Event kind | Fields |
| --- | --- |
| `route` | `component` (default 1), `state`, `destination`, `remainingMetres`, `remainingText`, `remainingUnit`, `distanceMetres`, `distanceText`, `distanceUnit`, `selected` (indices), `laneIndex`, `lanesShown`, `visible`, `source`, `etaEpoch` |
| `maneuver` | `component`, required `index`, `type`, `road`, `distanceMetres`, `distanceText`, `distanceUnit`, `side`, `junction`, `exit` |
| `lanes` | `component`, required `index` and `lanes`; each lane has optional `status`, `angles` and `preferred` angle arrays |
| `compass` | Required `heading` in degrees; passes a stock-shaped compass invocation through the real handoff state |
| `stop`, `disconnect` | No additional fields |
| `tick` | Advance the clock without supplying a message; this cannot trigger an incoming-message refresh |

Phone distance-unit values are **0 km, 1 mi, 2 m, 3 yd, 4 ft**. Emitted cluster
units use **0 m, 1 km, 2 mi, 3 ft, 4 yd**. These are different enumerations.
Common maneuver types are 1 left, 2 right, 3 straight, 4 U-turn; `exit` supplies
the angle (right +90, left −90). See the production bridge for the supported icon
mapping. Optional maneuver fields remain optional so late and partial data can
be tested. `etaEpoch` supplies a fixed epoch accepted by the bridge; relative ETA
inputs are intentionally excluded because production uses wall-clock Calendar
time for that path. The simulated monotonic clock controls HUD/lane coalescing.

`expect` compares independently decoded display values, never bridge caches:

```json
{
  "schemaVersion": 1,
  "title": "Route context without icons",
  "units": "mi",
  "events": [
    {"at": 0, "kind": "compass", "heading": 315},
    {"at": 1000, "kind": "route", "state": 1,
     "destination": "Museum", "remainingMetres": 1609,
     "expect": {"destination": "Museum", "remaining": "1.0 mi",
                "routeActive": true, "hudCount": 0}},
    {"at": 3000, "kind": "stop",
     "expect": {"destination": "", "remaining": null,
                "routeActive": false, "compassHeading": 315}}
  ]
}
```

Supported expectations: `hudPrimary`, `hudSecondary`, `remaining` (formatted
distance or null); `clusterDistance` (formatted distance or null);
`hudCount`, `laneCount`, `clusterIcon`, `hudPrimaryIcon`, `hudSecondaryIcon`; `destination`, `road`;
`routeActive`; `compassHeading`. `truth` is an independent explanatory note and is
never translated into bridge input. Inputs are bounded to 1 MiB / 2000 events / 24
hours, with bounded strings, indices, lanes and angle arrays. Unknown fields,
duplicate JSON keys, nonfinite JSON numbers and out-of-order timestamps are refused.
String distance fields can contain deliberately invalid display text for testing.

## Backend boundary and Android extension

```text
Scenario JSON v1
      │
      ▼
ReplayBackend.replay(scenario)      backend.py
      │   JavaBackend now; an AndroidBackend can implement the same contract
      ▼
Emitted call stream v1              SimulatorRunner.java / simulator Cluster.java
      │
      ▼
Independent display decoder         model.py
      │
      ▼
Snapshots + independent checks      run.py → web/app.js
```

`ReplayBackend.describe()` returns backend identity, limitations and source
evidence. `replay()` returns this transport-independent shape:

```json
{
  "schemaVersion": 1,
  "backend": "java-host",
  "steps": [{
    "at": 1000,
    "input": {"at": 1000, "kind": "route"},
    "calls": [{"method": "tbtSubInfo2C", "args": [2, 0, 1, 3, 0, 250, 0, 0, 0]}],
    "diagnostics": ["production trace strings"],
    "bridge": {"routeActive": true, "component": 1, "current": 10, "following": 20}
  }]
}
```

Call names and argument order match `hmgapi.hook.Cluster`; byte arrays are unsigned
integer arrays inside `args`. `turnList` contains arrays of serialized `Cluster.Turn`
fields. `routeOwnership` is an additional simulator observation of the bridge's
actual route-state callback. The production compass handoff is executed using
the existing modeled permission-aware Android broadcast queue. The replay decoder
reads only `calls`; `bridge` and `diagnostics` are for inspection.

A future Android adapter can deliver these inputs through instrumentation and
capture the same output boundary over a local transport. Add that adapter and
select it in the launcher; the model and browser viewer remain unchanged. No
Android implementation or firmware emulation is included in this version.

## Checks and headless use

```sh
python3 -m unittest discover -s tools/palisade/simulator -p 'test_*.py' -v
python3 tools/palisade/simulator/run.py \
  --replay tools/palisade/simulator/scenarios/01-close-turns.json \
  --output /tmp/palisade-replay.json
```

Headless replay exits 0 when all supplied expectations pass, 1 when any fail.
The negative-control fixture deliberately exits 1. Integration tests require the
negative control to fail and the positive fixtures to pass. They also check
determinism/process isolation, full frame preservation, cancellation ordering,
virtual-clock refresh, malformed input/frame rejection and alternate-backend use.
The existing `wireless-carplay-aa/tools/cpturns/test.sh` regression suite
continues to run separately.

## What the preview establishes

It verifies navigation logic and emitted display arguments against explicit
expectations. The display is a schematic model: vehicle-specific loading rules,
timeouts, icon artwork, exact palettes and secondary-distance interpretation need
calibration against synchronized car evidence. F2 icons and 2C distances are shown
as independent channels; this is not evidence of physical display pairing.

All-zero distance triplets are shown as cleared because this boundary cannot
distinguish them from a legitimate zero-metres value. Displays retain their last
emitted value until changed; no undocumented hardware timeout or loading-state
rule is invented. Unmapped icons show their numeric code. Cluster gauges are
decorative, fixed at zero, and carry no vehicle telemetry. The simulator runs
CarPlay bridge events; it does not run a live phone handshake, a complete head-unit
image, native iAP2 decoding or the physical cluster/HUD firmware.
