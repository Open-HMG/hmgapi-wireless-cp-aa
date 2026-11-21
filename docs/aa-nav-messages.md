# What Android Auto sends for turn-by-turn, and the full decoder

These notes come from four drives' NAVDUMP logs (US Ioniq 5, NEEV23, 260507, 2026-11-03; phone protocol 1.6), from the open protocol work (aa-proxy-rs's `protos.proto`; open-android-auto's (OOA) decompiles of Android Auto up to 17.3), and from emulating the turn-by-turn library. Nothing here is a manufacturer's or Google's code. The wider research (EV routing, CarPlay, a ranked list of changes) is in egmp-aa-ev's [docs/AA-INTEGRATION.md](https://github.com/AwsomeFox/egmp-aa-ev-hmg-api/blob/main/docs/AA-INTEGRATION.md).

## The messages

The library patch asks for protocol 1.6. From 1.6 the phone sends the "modern" pair, which the library's parser hands to Java as the event-33 dump, as well as the turn events it packs into GPM's callbacks:

| | Fields (wire numbers) |
|---|---|
| `S` = 0x8006, navigation state | 1 step* {1 maneuver {1 type 0..50, 2 roundabout exit number, 3 exit angle}, 2 {1 text}, 3 lane* {1 direction* {1 shape 0..9, 2 recommended}}, 4 {1 text*}}; 2 destination* {1 address, 2 charging stop {1 departure Wh, 2 max power W, 3 planned charging s}} |
| `P` = 0x8007, current position | 1 {1 distance {1 meters, 2 display text, 3 unit}, 2 time to step s}; 2 destination* {1 distance, 2 ETA text, 3 time to arrival s}; 3 current road {1 name} |

What Maps sent in the 2026-11-03 captures (26 unique S messages, 338 unique P):
- **Steps:** exactly one per message, so there are no upcoming steps to read.
- **Maneuvers seen:** 1 depart, 7/8 normal left/right, 11 U-turn left, 41/42 destination left/right. Those routes had no ramps, forks or roundabouts.
- **Step texts:** fields 2 and 4 carried the same text in 25 of 26 steps.
- **Lanes:** 2–3 lanes with shapes 1/4/5 (straight, left, right) and one recommended. They were on about a third of the steps.
- **P messages:**
  - Distance came with display text and a unit: 5 = tenths of a mile, 6 = feet.
  - Time to the step was there most of the time.
  - Around a departure, neither distance nor time was sent.
  - There was one destination, with ETA text and time to arrival.
  - The current road (field 3) was **never** sent.

The library's parser maps the type onto the legacy turn events (TURN plus side, and so on). That loses keep against slight turns, the ramp kind and side, fork and merge sides, destination side, and roundabout direction. `AaNavState` reads the dump instead.

## Newer protocol versions

No version above 1.6 adds turn data (OOA's gate tables, AA 17.3):
- **4.3:** UI options. One of them, `NAVIGATION_TURN_DATA_AVAILABLE`, tells the phone that the cluster shows the turn, so the phone draws no duplicate turn card.
- **5.0:** audio without acks. This is a risk to the library's audio path.
- **5.1:** the EV energy forecast (0x8008) and audio MediaOptions (0x8014).
- **6.0:** video and codec changes.

Lane images, junction images and turn icons are not sent to the head unit at any version. A cluster video display isn't usable: the Gen5W cluster has no video input from projection, and AA's `reject_clusters_for_unsupported_nav_apps` flag lists hyundai, kia and genesis.

In emulation, the turn-by-turn library's nav endpoint returns −253 for 0x8008, fires no callbacks, and handles the next 0x8007 normally. What the channel layer does with −253 hasn't been checked.

## The decoder

- **`AaNavState`** (`src/hmgapi/mods/wireless/AaNavState.java`) decodes S and P in full: the exact maneuver and roundabout data, lanes with the recommended flag, every destination with its charging stop, and distances and times. It also has helpers to map a maneuver to the legacy event and side.
- **`AaTurns`** keeps the latest decode (`navState`, `navPosition`) and logs a summary with no text (`AANAV`) when the step changes. Nothing drawn uses it yet. Drawing from it (exact turn types, time-to-step smoothing, charging-stop text) is the next step and needs a car.
- **`tools/aanav/test.sh`** tests it on synthetic messages shaped like the captures. Given logcat files, it also parses every NAVDUMP line in them and prints only counts. All 1,050 lines of the four captures parsed.
