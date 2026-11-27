#!/bin/sh
# Host test of the Wireless mod's own hooks (Wireless.appLoaded with recording HMG-API and
# Android stand-ins): which hooks each app gets per setting, and what the GPM USB-drive hook,
# the iAP2 bridge hooks and the BtSettings menu hook do. No manufacturer files.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" $(find stubs test -name '*.java') ../../src/hmgapi/mods/wireless/Wireless.java
java -cp "$CP" hmgapi.mods.wireless.HooksTest
