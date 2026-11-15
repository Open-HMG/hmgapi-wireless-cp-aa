#!/bin/sh
# Simulated Android broadcast delivery around the actual cluster handoff class.
set -e
cd "$(dirname "$0")"
CLASSES=$(mktemp -d); trap 'rm -rf "$CLASSES"' EXIT
javac --release 8 -d "$CLASSES" $(find stubs test -name '*.java') ../../src/hmgapi/mods/wireless/ClusterHandoff.java
java -cp "$CLASSES" hmgapi.mods.wireless.ClusterHandoffTest
