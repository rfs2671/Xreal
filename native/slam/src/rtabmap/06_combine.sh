#!/usr/bin/env bash
# Link everything into one librtabmap_core.so, strip it, print its dynamic section.
. "$(dirname "$0")/common.sh"
cm "$SCRIPTS/combined" "$BLD/combined" -DDEPS_PREFIX="$PREFIX" -DRTABMAP_BUILD="$BLD/rtabmap" \
  -DOpenCV_DIR="$SCRIPTS/opencv_cfg" -DOPENCV_ANDROID="$OPENCV_ANDROID" \
  -DPCL_DIR="$PREFIX/share/pcl-1.15" -DBoost_DIR="$PREFIX/lib/cmake/Boost-1.86.0" -DBoost_USE_STATIC_LIBS=ON \
  -DEigen3_DIR="$PREFIX/share/eigen3/cmake" -DFLANN_ROOT="$PREFIX"
"$CMAKE" --build "$BLD/combined" -j "$JOBS"
OUT="$ROOT/out/$ABI"; mkdir -p "$OUT"
"$LLVM/llvm-strip.exe" --strip-unneeded -o "$OUT/librtabmap_core.so" "$BLD/combined/librtabmap_core.so"
ls -l "$BLD/combined/librtabmap_core.so" "$OUT/librtabmap_core.so"
"$LLVM/llvm-readelf.exe" -d "$OUT/librtabmap_core.so" | grep -E "NEEDED|SONAME"
"$LLVM/llvm-readelf.exe" -lW "$OUT/librtabmap_core.so" | grep LOAD
