#!/usr/bin/env bash
# libultra_slam.so (native/slam/src/ultra_slam.cpp: the app's C API over RTAB-Map) and its phone test, built against
# the finished librtabmap_core.so. OUT gets the two stripped libraries the app ships.
. "$(dirname "$0")/common.sh"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$HERE/../../../app/src/main/jniLibs/$ABI}"
cm "$HERE" "$BLD/uslam" -DDEPS_PREFIX="$PREFIX" -DRTABMAP_SO="$ROOT/out/$ABI/librtabmap_core.so" \
  -DOpenCV_DIR="$SCRIPTS/opencv_cfg" -DOPENCV_ANDROID="$OPENCV_ANDROID"
"$CMAKE" --build "$BLD/uslam" -j "$JOBS"
"$LLVM/llvm-strip.exe" --strip-unneeded -o "$OUT/libultra_slam.so" "$BLD/uslam/libultra_slam.so"
cp "$ROOT/out/$ABI/librtabmap_core.so" "$OUT/librtabmap_core.so"
ls -l "$OUT/libultra_slam.so" "$OUT/librtabmap_core.so"
"$LLVM/llvm-readelf.exe" -d "$OUT/libultra_slam.so" | grep NEEDED
