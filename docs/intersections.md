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

### The engine's road graph (next)

ch0iiih's HMG_Phone_Projection_TBT reads the road graph around the car's position straight
from the engine (`navigation-api/native`: `CPositionInfo::GetDisplayPosition`,
`CEntityLink::Spawn`, `CEntityLink::GetPosition` …) and matches the phone's next maneuver to a
node (`StockMapJunction`, `CompoundJunction`). It does this on two Korean engine builds only,
pinned by SHA-256 and an address anchor. `tools/navi/engine-check.py <libExSLNavi.so>
[<libExSLAndroidJNI.so>]` prints what a build offers for this, as JSON with no file
bytes, to compare builds across cars:

- **US Ioniq 5 260507:** all 28 symbols the helper binds are exported, and the geometry
  functions are the same size as on the Korean builds. The two weak `mArray::clear` symbols
  are exported (no fixed-offset fallback needed). `NewComponent`, the helper's anchor, is at
  another address (`0x2242465`). The position callback the helper hooks
  (`NativeCall.sendGISInfo`) is there with the same signature.
- **Still to verify on this build:** that component 0x7200 is `CPositionInfo` and its
  vtable slot 0x20 is the call the helper makes, that `stConnectLink` is 0x58 bytes, and
  that `CVehiclePosition` fits 1024 bytes.

A port means:

1. a per-engine profile (hash, anchor, layout constants) in place of the helper's Korean pins;
2. the native helper built and staged in `com.mnsoft.navi`;
3. a `sendGISInfo` hook point in HMG-API's catalogue;
4. the matcher in Java. ch0iiih's code is AGPL-3.0 like this mod, so it can be reused
   with attribution.

The risk: a wrong constant crashes the car's navigation.
