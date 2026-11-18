#!/bin/sh
# Host test of FocusPolicy: startup/connect focus arbitration, mid-session driver
# selection and passenger Bluetooth streaming freedom, radio preservation, and
# PGS + explicit camera package deferral.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" \
    $(find test -name '*.java') \
    ../../src/hmgapi/mods/wireless/FocusPolicy.java
java -cp "$CP" hmgapi.mods.wireless.FocusPolicyTest
