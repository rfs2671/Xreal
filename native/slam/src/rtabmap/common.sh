#!/usr/bin/env bash
# Shared settings for the RTAB-Map Android arm64-v8a cross-build on Windows (Git Bash).
set -euo pipefail
ROOT="${ROOT:-C:/uvio/rtab}"
NDK="${NDK:-C:/Android/ndk/27.2.12479018}"
CMAKE_BIN="${CMAKE_BIN:-C:/Android/cmake/3.22.1/bin}"
OPENCV_ANDROID="${OPENCV_ANDROID:-C:/uvio/ocv}"
JOBS="${JOBS:-4}"
ABI=arm64-v8a
API=29
SRC="$ROOT/src"; BLD="$ROOT/build"; PREFIX="$ROOT/install"; SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # ROOT: a short path for sources and build trees
CMAKE="$CMAKE_BIN/cmake.exe"; NINJA="$CMAKE_BIN/ninja.exe"
LLVM="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
mkdir -p "$SRC" "$BLD" "$PREFIX"
# cm <source> <build> [extra -D...]   configure with the NDK toolchain, static libs, Release, no debug info
cm() {
  local s="$1" b="$2"; shift 2
  "$CMAKE" -G Ninja -S "$s" -B "$b" -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_C_FLAGS="-g0" -DCMAKE_CXX_FLAGS="-g0" \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_FIND_ROOT_PATH="$PREFIX" -DCMAKE_PREFIX_PATH="$PREFIX" \
    "$@"
}
bi() { "$CMAKE" --build "$1" -j "$JOBS" && "$CMAKE" --install "$1" >/dev/null; }
