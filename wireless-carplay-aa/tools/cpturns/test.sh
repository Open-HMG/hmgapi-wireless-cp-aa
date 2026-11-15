#!/bin/sh
# Host test of the Wireless mod's CarPlay turn-by-turn (dipo/CpTurns), with the Android
# Auto test's stand-ins for Android and HMG-API's Cluster/Car, and the mod's own iAP2
# message stub to build messages with.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" $(find stubs test ../aaturns/stubs -name '*.java') \
    ../../stubs/com/tcc/iap2/Iap2NotiMsg.java ../../src/hmgapi/mods/wireless/dipo/CpTurns.java
java -cp "$CP" hmgapi.mods.wireless.dipo.LaneVisibilityTest
java -cp "$CP" hmgapi.mods.wireless.dipo.LanePrefetchTest
java -cp "$CP" hmgapi.mods.wireless.dipo.PalisadeHudTest
java -cp "$CP" hmgapi.mods.wireless.dipo.HudDistanceTest
java -cp "$CP" hmgapi.mods.wireless.dipo.FollowingPlanTest
java -cp "$CP" hmgapi.mods.wireless.dipo.CpTurnsTest
java -cp "$CP" hmgapi.mods.wireless.dipo.Issue12HudTest
java -cp "$CP" hmgapi.mods.wireless.dipo.StraightGuidanceTest
java -cp "$CP" hmgapi.mods.wireless.dipo.ManeuverValidationTest
java -cp "$CP" hmgapi.mods.wireless.dipo.RoundaboutAngleTest
java -cp "$CP" hmgapi.mods.wireless.dipo.ClusterGeometryTest
java -cp "$CP" hmgapi.mods.wireless.dipo.ClusterTapeTest
java -cp "$CP" hmgapi.mods.wireless.dipo.ApproachReadabilityTest
java -cp "$CP" hmgapi.mods.wireless.dipo.ClusterRouteStateTest
java -cp "$CP" hmgapi.mods.wireless.dipo.ClusterContextTest
for fixture in "$@"; do
    if [ -f "$fixture/pair-10-20.parcel" ]; then
        java -cp "$CP" hmgapi.mods.wireless.dipo.Issue12HudTest "$fixture"
    else
        java -cp "$CP" hmgapi.mods.wireless.dipo.NativeLanePipelineTest "$fixture"
    fi
done
sh ../cluster-handoff/test.sh
