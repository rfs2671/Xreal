#!/usr/bin/env bash
# Reproduces android/arm64-v8a/libultra_vio.so from clean clones.
#   WORK    build directory            (default: $PWD/_build_vio)
#   NDK     Android NDK root           (default: /opt/android-sdk/ndk/27.2.12479018)
#   OPENCV_ANDROID  unpacked OpenCV Android AAR (default: /home/user/deps/opencv)
#   JOBS    parallel compile jobs      (default: 2; each Basalt TU needs ~1.5-2.5 GB)
#   OUT     where to put the .so       (default: <this dir>/../android/arm64-v8a)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${WORK:-$PWD/_build_vio}"
NDK="${NDK:-/opt/android-sdk/ndk/27.2.12479018}"
OPENCV_ANDROID="${OPENCV_ANDROID:-/home/user/deps/opencv}"
JOBS="${JOBS:-2}"
ABI=arm64-v8a
OUT="${OUT:-$HERE/../android/$ABI}"

"$HERE/fetch_deps.sh" "$WORK"

cmake -G Ninja -S "$HERE" -B "$WORK/android-$ABI" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DUVIO_BASALT_DIR="$WORK/basalt" -DUVIO_DEPS_DIR="$WORK/deps" \
  -DUVIO_OPENCV_ANDROID_SO="$OPENCV_ANDROID/jni/$ABI/libopencv_java4.so" \
  -DUVIO_OPENCV_INCLUDE="$OPENCV_ANDROID/prefab/modules/opencv_java4/include"
cmake --build "$WORK/android-$ABI" --target ultra_vio -j "$JOBS"

BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
mkdir -p "$OUT"
# The NDK toolchain always adds -g; ship the stripped library (unstripped is ~200 MB).
"$BIN/llvm-strip" --strip-unneeded -o "$OUT/libultra_vio.so" "$WORK/android-$ABI/libultra_vio.so"
ls -l "$OUT/libultra_vio.so"
"$BIN/llvm-readelf" -d "$OUT/libultra_vio.so" | grep -E "NEEDED|SONAME"
"$BIN/llvm-readelf" -lW "$OUT/libultra_vio.so" | grep LOAD
echo "exported symbols:"
"$BIN/llvm-nm" -D --defined-only "$OUT/libultra_vio.so"
