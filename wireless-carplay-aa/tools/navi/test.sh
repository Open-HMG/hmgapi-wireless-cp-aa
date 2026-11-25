#!/bin/sh
# Host test of the car-navigation tools (tools/navi) on synthetic inputs, no car files:
# the side-streets recipe generator, the engine profile, the ADASIS study and the road graph
# study. With the
# HMG-API mod SDK (HMGAPI_SDK), the synthetic recipe also goes through MemRecipeTest.
set -e
cd "$(dirname "$0")"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
python3 -I test_navi.py "$T"
if [ -n "$HMGAPI_SDK" ] && [ -f "$HMGAPI_SDK/test/hmgapi-engine-test.jar" ]; then
    java -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar:$HMGAPI_SDK/test/hmgapi-engine-test.jar" hmgapi.engine.MemRecipeTest \
        "$T/navi-synthetic.so" "$T/navi-synthetic.patched.so" "$T/navi-synthetic.recipe"
fi
