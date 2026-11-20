#!/usr/bin/env bash
# Build libultra_hands.so for Android arm64-v8a on Windows (Git Bash), with the Android SDK's own NDK, CMake and Ninja.
# The same build as build_android.sh; only the tool paths differ. Sources must be prepared first:
#   WORK=C:/uvio/hb FETCH_MODELS=0 bash prepare_sources.sh
# Output: $OUT/libultra_hands.so (stripped). It is not copied into the app: do that once it has been measured.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${WORK:-C:/uvio/hb}"
SDK="${ANDROID_SDK:-C:/Android}"
NDK="${ANDROID_NDK:-$SDK/ndk/27.2.12479018}"
CMAKE="$SDK/cmake/3.22.1/bin/cmake.exe"
NINJA="$SDK/cmake/3.22.1/bin/ninja.exe"
OPENCV_AAR="${OPENCV_AAR:-C:/uvio/ocv}"            # unpacked OpenCV AAR
ORT_AAR="${ORT_AAR:-$WORK/ort}"                    # unpacked onnxruntime-android 1.22.0 AAR
OUT="${OUT:-$WORK/out}"
JOBS="${JOBS:-6}"
ABI=arm64-v8a
BUILD="$WORK/build-android-$ABI"
"$CMAKE" -S "$HERE" -B "$BUILD" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DMONADO_DIR="$WORK/monado" -DEIGEN_DIR="$WORK/eigen" \
  -DOPENCV_INCLUDE_DIR="$OPENCV_AAR/prefab/modules/opencv_java4/include" \
  -DOPENCV_LIBRARY="$OPENCV_AAR/jni/$ABI/libopencv_java4.so" \
  -DORT_INCLUDE_DIR="$ORT_AAR/headers" \
  -DORT_LIBRARY="$ORT_AAR/jni/$ABI/libonnxruntime.so"
"$CMAKE" --build "$BUILD" -j"$JOBS"
mkdir -p "$OUT"
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" --strip-unneeded -o "$OUT/libultra_hands.so" "$BUILD/libultra_hands.so"
ls -l "$OUT/libultra_hands.so"
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe" --dyn-syms -W "$OUT/libultra_hands.so" | awk '$7!="UND" && $5=="GLOBAL"{print "export:", $8}'
