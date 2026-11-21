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
- **`AaTurns`** keeps the latest decode (`navState`, `navPosition`) and logs a summary with no text (`AANAV`) when the step changes.
- **Setting `tbt_route_details`** (off by default; a test setting until checked on a car) uses the decode for what the library's events drop. In emulation the library calls back with the dump first, then the turn, for the same message, so the decode always matches the turn it came with:
  - **Keep left/right:** the library sends a slight turn. With the setting it becomes the fork icon (173/174), and near the turn the car's FORK.
  - **Clockwise roundabouts** (left-hand traffic): the library marks plain clockwise roundabouts with side RIGHT, but sends no side at all for the newer clockwise enter/exit types. With the setting they use the car's clockwise set (192–203, and 33 near the turn). That includes the plain ROUNDABOUT_EXIT that follows once the car is in the roundabout.
  - **On-ramps near them** (with `tbt_standard`): TURN at the ramp's real angle (normal 90, sharp or U-turn ramp 135) instead of always 45.
  - **The next charging stop:** its planned minutes go after its name ("… - 24 min charge"), and are sent again when Maps changes them.
  - **Distance between updates:** within 914 m of the turn, the distance counts down between the phone's updates (about 1 s apart) at the speed its distance and time give. Feet and metres go in 10s, miles and km in tenths. It holds after 2.5 s with no update.
- Host tests: `tools/aaturns/test.sh` (`AaRouteDetailsTest`, which feeds the library's callbacks in that order).
- **`tools/aanav/test.sh`** tests it on synthetic messages shaped like the captures. Given logcat files, it also parses every NAVDUMP line in them and prints only counts. All 1,050 lines of the four captures parsed.
