#!/bin/sh
# Host test of native/navigraph (the navigation engine's road graph, read only) against a
# mock engine, and of its Java side (NaviGraph: profiles, the node's roads as angles and
# side streets) with the Android Auto test's stand-ins. NAVI_LIB=<a car's libExSLNavi.so> also binds
# every engine function from that file's symbol table (weak ones too), as on the car.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
${CC:-clang} -O1 -Wall -Wextra -Werror -Wno-unused-function "$HERE/navigraph_test.c" -o "$T/navigraph_test"
"$T/navigraph_test" "$HERE/../../files/libwlnavigraph.so" ${NAVI_LIB:+"$NAVI_LIB"}
javac --release 8 -Xlint:-options -nowarn -d "$T" $(find "$HERE/test" "$HERE/../aaturns/stubs" -name '*.java') \
    "$HERE/../../src/hmgapi/mods/wireless/NaviGraph.java"
java -cp "$T" hmgapi.mods.wireless.NaviGraphTest
