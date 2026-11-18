#!/bin/sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
N="$HERE/../../native/india-tbt"
python3 "$HERE/check.py"
if [ -z "${INDIA_STOCK_LIBRARY:-}" ]; then
 echo 'India private-stock MemRecipe checks skipped (set INDIA_STOCK_LIBRARY)'
 exit 0
fi
: "${HMGAPI_SDK:?set HMGAPI_SDK}"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
javac -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" -d "$TMP" "$N/IndiaRecipeTest.java"
java -cp "$TMP:$HMGAPI_SDK/lib/hmgapi-engine.jar" IndiaRecipeTest "$INDIA_STOCK_LIBRARY" "$HERE/../../recipes/aa-tbt-b7940034.recipe"
