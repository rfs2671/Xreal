#!/usr/bin/env bash
# Build libultra_hands.so for Android arm64-v8a (Release -O3, 16 KB pages).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export WORK="${WORK:-/home/user/build/hands}"
NDK="${ANDROID_NDK:-/opt/android-sdk/ndk/27.2.12479018}"
CMAKE="${CMAKE:-/opt/android-sdk/cmake/3.22.1/bin/cmake}"
NINJA="${NINJA:-/opt/android-sdk/cmake/3.22.1/bin/ninja}"
OPENCV_AAR="${OPENCV_AAR:-/home/user/deps/opencv}"          # unpacked OpenCV 4.12.0 AAR
ORT_AAR="${ORT_AAR:-/home/user/deps/onnxruntime}"           # unpacked onnxruntime-android 1.22.0 AAR
OUT="${OUT:-$HERE/..}"
JOBS="${JOBS:-2}"
ABI=arm64-v8a

FETCH_MODELS="${FETCH_MODELS:-1}" bash "$HERE/prepare_sources.sh"

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

STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
mkdir -p "$OUT/android/$ABI" "$OUT/include" "$OUT/models"
"$STRIP" --strip-unneeded -o "$OUT/android/$ABI/libultra_hands.so" "$BUILD/libultra_hands.so"
cp "$WORK/monado/src/xrt/tracking/hand/mercury/xr_mercury_runtime_c_api.h" "$HERE/ext/ultra_hands_ext.h" "$OUT/include/"
if [[ -d "$WORK/models" ]]; then
  cp "$WORK/models/grayscale_detection_160x160.onnx" "$WORK/models/grayscale_keypoint_jan18.onnx" "$OUT/models/"
  (cd "$OUT/models" && sha256sum *.onnx > SHA256SUMS.txt)
fi
ls -l "$OUT/android/$ABI/libultra_hands.so"
"$READELF" -d "$OUT/android/$ABI/libultra_hands.so" | grep -E "NEEDED|SONAME|SYMBOLIC|FLAGS"
"$READELF" -lW "$OUT/android/$ABI/libultra_hands.so" | grep LOAD
"$READELF" --dyn-syms -W "$OUT/android/$ABI/libultra_hands.so" | awk '$7!="UND" && $5=="GLOBAL"{print "export:", $8}'
