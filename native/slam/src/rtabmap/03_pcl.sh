#!/usr/bin/env bash
# PCL 1.15.0, static, minimum module set for rtabmap corelib (no VTK/visualization/Qhull/OpenNI/PNG/pcap/usb).
. "$(dirname "$0")/common.sh"
OFF=""; for m in visualization apps tools examples global_tests benchmarks cuda gpu keypoints recognition tracking stereo outofcore people simulation; do OFF="$OFF -DBUILD_$m=OFF"; done
for w in CUDA LIBUSB PNG QHULL GLEW VTK PCAP OPENGL OPENNI OPENNI2 ENSENSO DAVIDSDK DSSDK RSSDK RSSDK2 QT DOCS OPENMP; do OFF="$OFF -DWITH_$w=OFF"; done
cm "$SRC/pcl" "$BLD/pcl" $OFF -DPCL_SHARED_LIBS=OFF -DPCL_ONLY_CORE_POINT_TYPES=ON \
  -DPCL_ENABLE_SSE=OFF -DPCL_ENABLE_AVX=OFF -DPCL_ENABLE_MARCHNATIVE=OFF \
  -DPCL_FLANN_REQUIRED_TYPE=STATIC -DBoost_USE_STATIC_LIBS=ON \
  -DEigen3_DIR="$PREFIX/share/eigen3/cmake" -DBoost_DIR="$PREFIX/lib/cmake/Boost-1.86.0"
bi "$BLD/pcl"
