# Palisade USA 260507 baseline

Enables `LX2PE.USA.260507` with the Wi-Fi requirement and selects `lx2pe_us`
for the existing wireless projection and turn-by-turn implementation.

Requires the matching Palisade hook map in
[HMG-API #199](https://github.com/Open-HMG/HMG-API/pull/199), which proceeds
independently. Its optional PE03/PE12 observations are not used here. The mod's
minimum HMG-API version alone does not establish that the map is installed.

Java, native recipes, settings, release version and SDK pin are unchanged from
upstream. This adds no lane decoder, lane presentation, navigation rewrite,
cluster-ownership changes or simulator. Existing upstream CarPlay identity and
lane handling remain as-is; this does not claim Palisade lane support.

## Vehicle validation pending

Earlier local packages included additional changes and do not validate this
baseline. Before marking support verified, test a package from this exact branch
with the required map installed and the previous lane/navigation package inactive.
Record the source revision, package SHA-256, HMG-API version and firmware.

Verify wireless CarPlay connection, audio and reconnect while parked; then
ordinary left/right turns, road name and distance on the cluster and HUD on a
short route. Check route stop and disconnect. Leave the alternative AR turn
format off. Record Android Auto separately if tested. Share sanitized results
without vehicle identifiers or private captures.

Off-car build and regression results are recorded in the PR. They do not establish
wireless connection or ordinary TBT operation in the vehicle.
