# Palisade navigation port: review and validation

This is a draft port onto the current standalone mod repository, from the
[local Palisade workspace](https://github.com/dweaver33/hmg-leafia-palisade).
It targets the 2023 Hyundai Palisade Limited USA, firmware
`LX2_PE.USA.S5W_M.V015.012.260507`. Main HMG-API carries its hook map.
Release versions and the SDK pin are retained for maintainer coordination.

## Changes

- Add the LX2PE USA 260507 support selector and a checksum-guarded patch set.
- Decode bounded native iAP2 lane groups and normalize packed selected-maneuver
  fields before the vendor Parcel conversion; bind the recipe to its stock MD5
  and ten verified regions. The original SDK paths and mismatch refusals remain.
- Preserve indexed lane prefetch and explicit visibility, refresh unchanged HUD
  lists on incoming guidance, and keep primary/secondary icon and distance pairing.
- Publish destination and trip context independently of available turn details;
  clear it before releasing cluster ownership on cancellation or service loss.
- Coordinate the stock compass/navigation handoff with permission-checked Android
  broadcasts, retaining Android Auto priority and real heading data.
- Render supported approach geometry and roundabout sectors, with owner-unit
  distance rounding and an unrounded ten-percent tape field.
- Add a local production-Java replay simulator with an independent display decoder,
  synthetic scenarios and an intentionally failing distance-ambiguity control.

Upstream's ordinary-turn fallbacks for both traffic sides and departure NO_INFO,
its Android Auto changes, Bluetooth/native-navigation suppression, and CarPlay
short-read fixes are retained. Missing-angle primary updates retain a proven
instruction when appropriate; a new ordinary turn can use upstream's generic
left/right icon. Adjacent-secondary inference still requires nonzero supplied
geometry, so the broader upstream fallback cannot validate an ambiguous pair.

## Evidence and limits

Vehicle observations apply to separately signed local packages on **HMG-API 1.0.2**,
not to this rebased upstream branch. The owner confirmed wireless CarPlay on local
2.1.0.1, lane colors on 2.2.6, idle compass return on 2.2.8, active destination/trip
context on 2.2.9, and close-turn secondary distance on 2.2.10. Angled cluster geometry
worked on 2.2.14. These local numbers are not this repository's release numbers.

The latest 2.2.15 readability/schematic-last candidate did not install in its two
returned attempts: a separate local collector required `wc`, absent on the unit.
The collector was repaired locally; the candidate still needs a vehicle run.
Cluster tape visibility, dedicated roundabout exit ordinals, HUD interruptions,
uncaptured geometries, and cross-model behavior remain unresolved. Reroute and
arrival vehicle tests were waived by the owner for the earlier local merge;
they are not claimed as passed. No new upstream vehicle test is claimed.

The simulator models Android messages, IPC and display reception. It does not run
the native phone handshake or physical display firmware. Its previews are schematic.

## Off-car validation

Run the SDK build and standard mod/recipe/script checks described in the README,
plus all `wireless-carplay-aa/tools/*/test.sh` scripts. The new `route-bounds` suite
checks the native trace and normalizer with synthetic inputs; `simulator` checks
production Java replay. Existing stock-only tests skip without their reference trees.

`tools/palisade/build_iap2_decoder.py --library <private libiap2session.so> --output
<ignored output>` rebuilds the guarded recipe using clang and LLVM lld or ARM GNU
binutils (`ARM_LD` may select a linker). Stock files and generated firmware-derived
images must remain in ignored `private/` or `out/` storage.
`Iap2DecoderRecipeTest.java` exercises the production MemRecipe with the exact stock
library: four placements, refusal before writes for each changed fingerprint, and
no unintended firmware changes. The earlier full ARM/Parcel pipeline evidence is
in the local workspace; a fresh cross-model pipeline run is still needed here.

The optional PE03/PE12 hooks in the companion HMG-API PR support a separate local
stock-navigation recorder; this mod does not require those hooks. Local USB delivery,
recovery tools, signing keys, stock reference files and private car captures are
not part of this upstream port. The recorder and installation investigation remain
in the linked source workspace.
