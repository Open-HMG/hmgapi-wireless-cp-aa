#!/bin/sh
# Build libwlnavigraph.so: reads the car navigation engine's road graph around the car,
# read only, inside com.mnsoft.navi (navigraph.c; Java side hmgapi.mods.wireless.NaviGraph).
#   native/navigraph/build.sh [output]   (default: ../../files/libwlnavigraph.so)
# No libc or NDK: it only imports dlsym and dladdr from the head unit's libdl.so (a stub
# library with that name is linked in to record the dependency). Needs clang + lld and a
# JDK's jni.h (JAVA_HOME).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/../../files/libwlnavigraph.so}
case "$OUT" in /*) ;; *) OUT=$(pwd)/$OUT ;; esac
CLANG=${CLANG:-clang}
J=${JAVA_HOME:?set JAVA_HOME to a JDK}
case "$(uname -s)" in Darwin) JOS=darwin ;; *) JOS=linux ;; esac
T="--target=armv7a-linux-androideabi -mthumb -march=armv7-a -mfloat-abi=softfp"
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
echo 'void *dlsym(void *h, const char *s) { return 0; } int dladdr(const void *a, void *i) { return 0; }' > "$W/dl.c"
"$CLANG" $T -shared -nostdlib -fuse-ld=lld -Wl,-soname,libdl.so -o "$W/libdl.so" "$W/dl.c"
cp "$HERE/navigraph.c" "$HERE/navigraph_jni.c" "$W/"
# -ffreestanding: no memset/memcpy from the compiler (zero() is ours); -fno-builtin likewise
(cd "$W" && "$CLANG" $T -Os -fPIC -ffreestanding -fno-builtin -fno-exceptions -fno-asynchronous-unwind-tables \
    -I"$J/include" -I"$J/include/$JOS" \
    -shared -nostdlib -fuse-ld=lld -Wl,-z,now -Wl,-z,defs -Wl,--no-rosegment -Wl,--hash-style=sysv -Wl,--build-id=none \
    -Wl,-soname,libwlnavigraph.so -o "$OUT" navigraph_jni.c libdl.so)
md5sum "$OUT" 2>/dev/null || md5 -r "$OUT"
