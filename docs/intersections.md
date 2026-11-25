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
    with 2 onward; node ahead: 3 roads, angles [-90, 0, 90], side streets 0x811`. No
    coordinates, link ids or names.

  Nothing goes to the cluster.
- Tested off the car: `tools/navigraph/test.sh` runs the C against a mock engine (call
  order, module tag, refusals, every engine object freed) and the node geometry in Java.

**On the car**, with `navi_road_graph` on, the car's navigation running and a phone
guiding or not:
- HMG-API's log says "reading the US 260507 (Ioniq 5 NEEV23) engine's road graph".
- At intersections the lines match the real roads.
- The navigation keeps working: map, guidance, speed limits and lanes.

If the navigation app crashes or misbehaves, turn the setting off and keep the log.

**Next, once the car confirms step 1:**
- Ask only near the phone's next maneuver. The phone side (`AaTurns` in GPM, `CpTurns` in
  DiPO) asks `com.mnsoft.navi` for the junction at its maneuver's distance, and the answer
  comes back across processes. ch0iiih uses `Settings.Global` with a short lease; a
  broadcast also works.
- Port the matcher (`StockMapLookahead`, `StockMapJunction`, later `CompoundJunction`):
  walk forward to the node at the phone's distance, then accept only an unambiguous
  match with the phone's maneuver side and angle.
- Put its side streets in the `TURN` frame, for Android Auto and for CarPlay turns that
  came without a junction.

ch0iiih's code is AGPL-3.0 like this mod and is credited where it's used. The risk stays
the same: a wrong constant on a new engine build crashes the car's navigation, so each
build needs its profile checked against its own code first (the list above).
