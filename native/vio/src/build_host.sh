#!/usr/bin/env bash
# Builds libultra_vio.so + uvio_sim_test for the host (Linux x86_64) from clean
# clones and (RUN_TEST=1, default) runs the simulation test.
#   WORK   build directory (default: $PWD/_build_vio)
#   JOBS   parallel compile jobs (default: 2)
#   CALIB_DIR  directory with basalt_calib_640x480.json / basalt_vio_config_640x480.json
#              (default: the XR Gate ZBBM5DZFMP unified_480_ccw90_device_time profile)
#   TEST_OUT   where the test log / CSV go (default: <this dir>/../test/output)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${WORK:-$PWD/_build_vio}"
JOBS="${JOBS:-2}"
RUN_TEST="${RUN_TEST:-1}"
CALIB_DIR="${CALIB_DIR:-/home/user/ref/vladoshub_xr-gate/devices/xreal_ultra/configs/calibration_dataset/final/xreal_air2ultra/ZBBM5DZFMP/unified_480_ccw90_device_time}"
TEST_OUT="${TEST_OUT:-$HERE/../test/output}"

"$HERE/fetch_deps.sh" "$WORK" --with-opencv

# Minimal static OpenCV for the host (the VIO path only needs cv::FAST, i.e.
# core + imgproc + features2d; flann is a build dependency of features2d).
if [ ! -f "$WORK/host/opencv-install/lib/cmake/opencv4/OpenCVConfig.cmake" ]; then
  cmake -G Ninja -S "$WORK/deps/opencv" -B "$WORK/host/opencv-build" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$WORK/host/opencv-install" \
    -DBUILD_LIST=core,imgproc,features2d,flann -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_opencv_apps=OFF \
    -DBUILD_JAVA=OFF -DBUILD_opencv_python3=OFF -DBUILD_opencv_python2=OFF \
    -DWITH_IPP=OFF -DWITH_ITT=OFF -DWITH_OPENCL=OFF -DWITH_TBB=OFF -DWITH_OPENMP=OFF \
    -DWITH_LAPACK=OFF -DWITH_EIGEN=OFF -DWITH_PROTOBUF=OFF -DWITH_ADE=OFF -DWITH_QUIRC=OFF \
    -DWITH_JPEG=OFF -DWITH_PNG=OFF -DWITH_TIFF=OFF -DWITH_WEBP=OFF -DWITH_OPENJPEG=OFF \
    -DWITH_JASPER=OFF -DWITH_OPENEXR=OFF -DWITH_FFMPEG=OFF -DWITH_GSTREAMER=OFF -DWITH_V4L=OFF \
    -DWITH_GTK=OFF -DWITH_1394=OFF -DWITH_VTK=OFF -DWITH_KLEIDOCV=OFF -DWITH_OPENVX=OFF \
    -DBUILD_ZLIB=ON -DCV_TRACE=OFF
  cmake --build "$WORK/host/opencv-build" --target install -j "$JOBS"
fi

cmake -G Ninja -S "$HERE" -B "$WORK/host/build" -DCMAKE_BUILD_TYPE=Release \
  -DUVIO_BASALT_DIR="$WORK/basalt" -DUVIO_DEPS_DIR="$WORK/deps" \
  -DOpenCV_DIR="$WORK/host/opencv-install/lib/cmake/opencv4" -DUVIO_BUILD_TESTS=ON
cmake --build "$WORK/host/build" -j "$JOBS"

if [ "$RUN_TEST" = "1" ]; then
  "$HERE/../test/run_sim_test.sh" "$WORK/host/build/uvio_sim_test" "$CALIB_DIR" "$TEST_OUT"
fi
