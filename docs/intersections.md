# Intersections on the cluster: the car navigation's side streets

Korean cars' own navigation draws each turn's intersection on the cluster: the roads around
the turn arrow ("side streets", the 16-bit `sideStreet` of `updateTBTInfo`;
[cluster-turns.md](cluster-turns.md) has the bits). US and Canadian cars send 0, no roads.
This page covers what the car navigation's engine can give for intersections, the test
setting that turns its side streets on outside Korea, and the tools for the next steps
(side streets under Android Auto). Nothing here is the car's code; it describes what it does.

## Why US cars send no side streets

The navigation engine (`libExSLNavi.so` in `com.mnsoft.navi`, the same MnSoft engine as in
Korea) works the side streets out for every turn:
`Navi::CLUSTER::Turn::CInformation::MakeSideStreet` and its roundabout and "normal"
variants, from the road graph around the turn point. But at start `CInformation` asks
`IsSupportSideStreet()`, which is "the nation is Korea", and keeps the answer in a flag that
all three read first. Everywhere else they return at once, and the turn goes out with 0. On
the US Ioniq 5 (NEEV23, 260507), the engine's `FrameCluster::sendTurnData` sent `TURN` 39
times on a drive, every time with side streets 0.

## Test setting: `navi_side_streets`

Off by default, and independent of the turn-by-turn settings: **"Car navigation:
intersections around the turn arrow (test)"**. In `com.mnsoft.navi`, a memory recipe makes the
three reads of that flag "yes" (`movs r3, #1; nop` for each 4-byte `ldrb.w`), so the engine
always works out the side streets. The file stays stock, and turning it off needs only a
restart. Only engine builds with a recipe change. On any other build the recipe's hashes
don't match, nothing is written, and HMG-API's log says "native patch libExSLNavi.so not
applied".

| Engine (md5) | Cars | Recipe |
|---|---|---|
| `caa8d4c2…` (69,612,256 bytes) | US Ioniq 5 NEEV23, 260507 | `recipes/navi-side-streets-caa8d4c2.recipe` |

**Not yet checked on a car.** What a drive with the car's own navigation should show:

- `adb shell dumpsys com.hkmc.micom.cluster`: the turn frame's side streets non-zero near
  turns. `dumpsys` prints them inverted (0 as every index, 65535 as "None").
- logcat, tag `CoNA`: `FrameCluster::sendTurnData ... TBT_Side_Street` non-zero.
- On the cluster: the roads around the arrow. On the AR HUD: the arrow still animates. This
  is the open question for CarPlay's side streets too (setting `tbt_side_streets`), since
  the AR HUD has only been seen with 0 and 65535.
- The navigation itself unaffected. The engine also checks the nation in a few other
  junction-shape places (complex links, patterned side streets, main links, forks), which
  stay as they are, so a US-specific gap there is possible.

### A recipe for another car

```
python3 wireless-carplay-aa/tools/navi/side-streets-recipe.py <libExSLNavi.so> --out wireless-carplay-aa/recipes/navi-side-streets-<md5:8>.recipe
```

It finds everything by exported symbol and instruction pattern (the flag's offset from
`CInformation`'s constructor, the one read in each of the three functions), so it works on
other builds of the same code. Anything unexpected stops it with an error and no recipe.
Add the recipe to `mod.json` (`hooks.native`, `libExSLNavi.so`) and the engine to the table
above and to `tools/recipes/test.sh`. The engine comes from the car
(`/data/data/com.mnsoft.navi/BIN/libExSLNavi.so`, read-only adb pull). It's the
manufacturer's: never commit it.

## Android Auto: where side streets could come from

Android Auto sends no junction shape at all: its navigation messages carry the maneuver,
road, lanes and cue in every protocol version, and the Car App Library's junction and lane
images only draw the phone's own screen
([open-android-auto](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/nav.md)).
So under Android Auto the side streets have to come from the car's map. While a phone guides, the car's navigation has no route, so its own turn code doesn't
run, but its map matching and its driver-assist (ADASIS) horizon do. Two candidates:

### ADASIS stubs (studied: weak)

The engine keeps sending the ADAS ECU an ADASIS v2 horizon, and logs it (`[ENGINE][POS]`,
`[ENGINE][STUB]` under tag `CoNA`). A stub is a branch off the path ahead, with a turn
angle. `tools/navi/adasis-study.py <logcat>` lines the stubs up with Android Auto's
maneuvers from the mod's `NAVDUMP` lines. It prints no coordinates or road names.

On the US Ioniq 5's three Android Auto drives with ADASIS lines (2026-11-03): **3 of 15
turns** had a side branch near them, all on the right. Of 236 stubs, 19 turned more than 20
degrees off the path, all of them to the right. The rest continue the path (within about 17 degrees). On the
car-navigation drive, 10 of 99 did, 3 of them left. So the horizon, as logged, follows the
road ahead and names few of an intersection's roads. That isn't enough for side streets.
Unless the engine keeps more branches than it logs (`CStubMsgMgr`, not looked at), this
path is a dead end.

### The engine's road graph (being ported)

ch0iiih's HMG_Phone_Projection_TBT reads the road graph around the car's position straight
from the engine (`navigation-api/native`: `CPositionInfo::GetDisplayPosition`,
`CEntityLink::Spawn`, `CEntityLink::GetPosition` …) and matches the phone's next maneuver to a
node (`StockMapJunction`, `CompoundJunction`). It does this on two Korean engine builds only,
pinned by SHA-256 and an address anchor. `tools/navi/engine-check.py <libExSLNavi.so>
[<libExSLAndroidJNI.so>]` prints what a build offers for this, as JSON with no file
bytes, to compare builds across cars.

**On the US Ioniq 5 260507 engine** (md5 `caa8d4c2…`):

- All 28 symbols the helper binds are exported, the geometry functions are the same size
  as on the Korean builds, and the two weak `mArray::clear` symbols are exported, so no
  address anchor or fixed-offset fallback is needed.
- But the head unit's Android 4.4 `dlsym` returns only `STB_GLOBAL` symbols, so on the car
  those two came back missing ("lacks `_ZN6mArrayI13stConnectLinkE5clearEv`", 2026-11-21).
  `navigraph_elf_lookup` now finds them in the loaded engine's own dynamic symbol table
  (`DT_HASH`, `DT_SYMTAB`, `DT_STRTAB`), from the engine's base that `dladdr` gives for one
  of its global functions. The `dladdr` check that every address is in `libExSLNavi.so`
  stays. `NAVI_LIB=<libExSLNavi.so> tools/navigraph/test.sh` binds all 28 from the file that way.
- The helper's layout assumptions, checked against the engine's own code:
  - `Navi::VI::CService::GetVehiclePosition` makes the same calls: `NewComponent(0x7200)`,
    its vtable slot 0x20, then `CPositionInfo::GetDisplayPosition(…, false)`.
  - `mArray<stConnectLink>::operator[]` is `items + index × 0x58`, and entries start with
    parcel then link (u64 each).
  - The engine's own `Spawn` and `GetPosition` callers build `mArray` as {items, count,
    capacity, grow 16, u16 module}.
  - `CVehiclePosition` (about 0x120 bytes), `CEntityLink` and `CEntityNode` (about 0x28)
    fit the 1 KB buffers.
- **One difference:** the array module tag. The engine's `CInformation` uses 0x9550 where
  ch0iiih's Korean builds use 0x2100, so it's per engine profile.

**Step 1, in the mod (test setting `navi_road_graph`, off by default):** read and log only.
- `native/navigraph` (`files/libwlnavigraph.so`, installed in `/system/lib` and loaded only
  with the setting) is ch0iiih's helper without the Korean pins:
  - every symbol through `dlsym(RTLD_DEFAULT)`, and refused unless `dladdr` puts it in
    `libExSLNavi.so`;
  - the module tag from the profile;
  - imports only `dlsym` and `dladdr`.
- `NaviGraph`, in `com.mnsoft.navi`:
  - waits for the engine to load and picks its profile by md5. Any other build is left
    alone and logged.
  - once a second, reads the matched link and the node ahead.
  - writes a line to HMG-API's log when the result changes, e.g. `road graph: on a link
    of 150 m, 40 m along, heading forward, node ahead in 110 m: 3 roads, angles [-90, 0,
    90], side streets 0x811`. No coordinates, link ids or names.

  Nothing goes to the cluster.
- Tested off the car: `tools/navigraph/test.sh` runs the C against a mock engine (call
  order, module tag, refusals, every engine object freed) and the node geometry in Java.

**On the car**, with `navi_road_graph` on, the car's navigation running and a phone
guiding or not:
- HMG-API's log says "reading the US 260507 (Ioniq 5 NEEV23) engine's road graph".
- At intersections the lines match the real roads.
- **The distance to the node ahead counts down as the car drives toward it, and resets at
  each node.** That checks `GetLinkPos`'s meaning. The engine keeps millimetres and the
  getter divides by 1000; the mod takes it as counted from the link's first shape point,
  so heading toward its last point the distance left is length − pos. If the distance
  counts up instead, that's the wrong way round, and step 2 can't find junctions until
  `NaviGraph.remaining()` is changed.
- The navigation keeps working: map, guidance, speed limits and lanes.

If the navigation app crashes or misbehaves, turn the setting off and keep the log.

**Step 2: Android Auto's side streets (setting `tbt_map_side_streets`, off by default,
with `tbt_standard`).** Near a turn, the cluster draws the map's roads around Android
Auto's arrow.
- **The question** (`MapJunction`, in GPM). When `AaTurns` sends a `TURN` within the
  457 m window for a straight, slight, normal or sharp turn, it asks `com.mnsoft.navi` by
  broadcast: the maneuver's id, the phone's meters to it, and the turn's angle (0, ±45,
  ±90, ±135, right positive). It asks again at most once a second, or at once for a new
  maneuver. Ramps, forks, U-turns and roundabouts ask nothing and keep 0.
  - **Who may ask and answer:** the question goes to `com.mnsoft.navi` only, and the
    navigation takes questions only from a sender holding `BLUETOOTH_PRIVILEGED` (the
    system's apps hold it, and GPM runs as the system; an app installed on the head unit
    doesn't). Each Android Auto session asks with a random token, and only an answer
    with that token counts, so only the navigation, which alone got the question, can
    answer it.
  - **When the session ends** (GPM's receiver is destroyed), `AaTurns.end` closes
    `MapJunction`, which unregisters its receiver, so reconnects don't pile them up.
- **The walk** (`NaviGraph.find`, after ch0iiih's `StockMapLookahead`):
  - It starts from the node ahead of the car, the car's remaining meters on its link
    away.
  - At each node it goes straight on: the one road within 30° of straight, with any
    other at least 15° further off. If there's no clear way straight on, it stops.
  - The phone's distance reads longer than the map's (see the drive below), so it looks
    around the asked distance less 8 m, within 25 m near the turn, an eighth of the
    distance farther out, 45 m at most. It stops once past that window.
  - Of the nodes in the window with more than one road on, the junction is the one the
    turn fits (the match below). With several, it's the nearest, if it's 10 m nearer
    than the next. So a side street on the other side just before the turn isn't taken
    for it.
- **The match** (`NaviGraph.match`, after ch0iiih's `StockMapJunction`):
  - The turn has to pick out one road: the nearest to the turn's angle, within 60° (22°
    for straight), 15° nearer than any other, and on the turn's side.
  - For a plain left or right, it must be the only road on that side.
  - Two roads in one 22.5° sector, or a node with one road on, give 0.
  - A node with any road whose shape didn't load isn't used at all, by the walk or the
    match: the roads left could look like the only road on a side, or the one way on.
  - The answer is the junction's side streets: every road leaving it, the turn's included.
- **The answer** goes back to GPM (`MapJunction`). It counts only if it names the maneuver
  being asked about, comes back within 2.5 s of the question, and its junction is where the
  phone's distance said (within the walk's tolerance plus 30 m). A match is then **held for
  that maneuver** (`NaviGraph.Held`) until a newer match replaces it, the maneuver changes,
  or the phone's distance grows 100 m past where it matched. A "no match" answer doesn't
  clear it. Until the first match it sends 0, the plain arrow, as before.

  Without the hold, the side streets came and went on the 2026-11-21 drive. Each answer
  replaced the last, so the matcher missing one second (another node in tolerance for a
  moment, the car between links) blanked them. And a match stopped counting once the
  car had closed more than about 45 m on where it matched, which at speed is under two
  seconds.
- **HMG-API's log** gets one line per change in the navigation app, with numbers only:
  `road graph: junction 300 m, turn 90: matched, side streets 0x811 at 300 m`, or why
  not, e.g. `... no clear way straight on at 180 m` or `... 2 roads on the turn's side`.
- **Tested off the car:** `tools/navigraph/test.sh` runs the walk and the match on a
  made-up map (a T junction, a crossroad, a fork, the refusals), and `MapJunction`'s
  questions and answers through stand-in broadcasts. `tools/aaturns/test.sh` covers
  `AaTurns` asking, using and dropping answers.

**The 2026-11-21 drive** (US Ioniq 5, about 2.5 hours, 3,087 road graph lines, 15
approaches with questions) showed why most turns kept the plain arrow:
- **The distance to the node ahead was wrong on most links.** The engine's position along
  the link counts the way the car is going, from 0 up to the link's length, whichever
  way the link is drawn (298 of 303 links; 220 of them driven toward the link's first
  point). The helper took it as counted from the first point, so on those 220 it used
  the distance already driven. Fixed: it's always length minus position.
- **The phone's distance reads longer than the map's**, by a median of 13 m on the last
  link before a turn (95 samples: -13 to +30 m, nine in ten between 2 and 22 m). The old
  window, 15 m either side near a turn, held two in three of them. The new one held all.
- **Each answer replaced the last**, so the few matches blinked. Fixed with the hold.
- **Not fixed yet: nodes on a map parcel's edge** (`not read (-36)`, 11% of the node
  reads). The helper refuses a node that joins another parcel, and the walk stops there:
  one approach got nothing from 449 m down to 236 m. The engine has the adjacent node
  and parcel ids for it (`CEntityNode::GetAdjNodeId`, `GetAdjParcelId`); their return
  types need checking against the engine's own callers first.

`tools/navi/road-graph-study.py <HMG-API log>` does this reading for any drive: per
approach, what the matcher answered then, what today's rules give for the node ahead of
the car, and the phone's distance against the map's. It prints numbers and angles only.
On this drive's 9 approaches (249 answers):
- **Then: 60 matches. Today's rules, on the node ahead alone: 90**, with at least one in
  8 of the 9, which the hold keeps to the turn. Another 105 answers had the junction
  past the node ahead. The log can't replay the walk for those (it has no link ids), but
  the walk now starts from the right distance.
- **The ninth approach got nothing.** It was a slight left at speed, and a parcel-edge
  node stopped the walk at every distance from 454 m in.
- **The offset is mostly where the two maps put the turn, not delay.** The phone's
  distance less the map's fits 11.4 m + 0.39 s x speed (r 0.31): a median of 10 m with
  the car nearly stopped, 16 m at 7 to 11 m/s, and 18 m for rights against 13 m for
  lefts. It also grows with distance (22 m at 100 to 250 m, 7 samples).
- **One refusal was a rule too strict**: a left where the road ahead bent 18 degrees left
  beside the street the turn took counted as two roads on the left. A road within 30
  degrees of straight is now the way on, as in the walk.
- **One was right to refuse**: the "turn" was a corner with one road on.

Android Auto's own message dumps (how many steps Maps sends, how often it names the
road) are in logcat only, which the restart after the drive cleared. Capture
`logcat -s NAVDUMP AANAV` during the next one.

**On the car**, with `tbt_standard`, `tbt_map_side_streets` (and `navi_road_graph` for the
step 1 lines) on, Android Auto guiding through city turns:
- The cluster draws the junction's roads around the arrow near most turns. In the log,
  `matched` near those turns, and a reason near the others.
- The AR HUD arrow still animates.

**Not yet:** CarPlay turns that come without a junction. They'd need the same question
from `CpTurns`, a copy of `MapJunction` in the DiPO part, which can't share classes with
the main code. Also compound junctions (ch0iiih's `CompoundJunction`: divided roads,
where one crossing is two nodes) and roundabouts. Those refuse today and keep the plain
arrow.

ch0iiih's code is AGPL-3.0 like this mod and is credited where it's used. The risk stays
the same: a wrong constant on a new engine build crashes the car's navigation, so each
build needs its profile checked against its own code first (the list above).
