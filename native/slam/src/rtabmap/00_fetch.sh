#!/usr/bin/env bash
# Fetch all sources (pinned).
. "$(dirname "$0")/common.sh"
cd "$SRC"
g() { [ -d "$3" ] || git clone -q --depth 1 -b "$2" "$1" "$3"; }
[ -d rtabmap ] || { git clone -q https://github.com/introlab/rtabmap.git rtabmap && git -C rtabmap checkout -q 832eaaff2444282789cdfc9eef2332c5a7830f7b; }
g https://github.com/PointCloudLibrary/pcl.git pcl-1.15.0 pcl
g https://github.com/flann-lib/flann.git 1.9.1 flann
g https://github.com/RainerKuemmerle/g2o.git 20230806_git g2o
g https://gitlab.com/libeigen/eigen.git 3.4.0 eigen
if [ ! -d boost ]; then
  curl -sL -o boost.tar.xz https://github.com/boostorg/boost/releases/download/boost-1.86.0/boost-1.86.0-cmake.tar.xz
  mkdir boost && tar -xJf boost.tar.xz -C boost --strip-components=1
fi
if [ ! -d sqlite ]; then
  curl -sL -o sqlite.zip https://www.sqlite.org/2026/sqlite-amalgamation-3540100.zip
  mkdir sqlite && unzip -q -j sqlite.zip -d sqlite
fi
# patches (idempotent: skipped when already applied)
ap() { git -C "$1" apply --check "$2" 2>/dev/null && git -C "$1" apply "$2" || echo "patch $2: already applied or not applicable"; }
ap flann "$SCRIPTS/flann-1.9.1-cxx17.patch"
ap rtabmap "$SCRIPTS/rtabmap-android-opencv-java4.patch"
