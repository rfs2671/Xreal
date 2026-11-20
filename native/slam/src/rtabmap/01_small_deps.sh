#!/usr/bin/env bash
# Eigen 3.4.0 (headers), SQLite 3.54.1 (static), FLANN 1.9.1 (static).
. "$(dirname "$0")/common.sh"
# --- Eigen: header-only, install headers + config without running its tests
cm "$SRC/eigen" "$BLD/eigen" -DBUILD_TESTING=OFF -DEIGEN_BUILD_DOC=OFF -DEIGEN_BUILD_PKGCONFIG=OFF
"$CMAKE" --install "$BLD/eigen" >/dev/null
# --- SQLite (same flags as rtabmap's docker/noble/android/Dockerfile)
mkdir -p "$BLD/sqlite" "$PREFIX/lib" "$PREFIX/include"
"$LLVM/clang.exe" --target=aarch64-linux-android$API -O2 -fPIC -DSQLITE_THREADSAFE=1 -DSQLITE_OMIT_LOAD_EXTENSION \
  -c "$SRC/sqlite/sqlite3.c" -o "$BLD/sqlite/sqlite3.o"
rm -f "$PREFIX/lib/libsqlite3.a"
"$LLVM/llvm-ar.exe" rcs "$PREFIX/lib/libsqlite3.a" "$BLD/sqlite/sqlite3.o"
cp "$SRC/sqlite/sqlite3.h" "$SRC/sqlite/sqlite3ext.h" "$PREFIX/include/"
# --- FLANN
cm "$SRC/flann" "$BLD/flann" -DBUILD_PYTHON_BINDINGS=OFF -DBUILD_MATLAB_BINDINGS=OFF -DBUILD_EXAMPLES=OFF \
  -DBUILD_TESTS=OFF -DBUILD_DOC=OFF -DBUILD_C_BINDINGS=OFF -DUSE_OPENMP=OFF
bi "$BLD/flann"
rm -f "$PREFIX"/lib/libflann*.so   # keep only the static FLANN so nothing links the shared one
