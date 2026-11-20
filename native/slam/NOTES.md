# The saved map of the room: RTAB-Map on the phone (`libultra_slam.so`, `librtabmap_core.so`)

What it is for, how it is wired into the app and what was measured: `docs/SPATIAL.md`, section 7. This file is about
building it.

## What is built

- `librtabmap_core.so` (19 MB stripped): RTAB-Map's `corelib` and `utilite` with PCL, Boost, FLANN, g2o and SQLite
  linked in statically. It needs only `libopencv_java4.so` (the one the app already ships), `libc++_shared.so` and
  system libraries. No GUI, no Qt, no camera drivers, no ROS.
- `libultra_slam.so` (60 kB): `src/ultra_slam.cpp`, the C API the app calls (`src/ultra_slam.h`): rectifies the two
  fisheye pictures, runs RTAB-Map on a thread of its own at low priority, and reports where the estimator's world lies
  in the saved map's.
- `test/ultra_slam_replay.c`: the phone test through that same API (make a map from a stretch of a recording, save
  it, open it as a new session with an unknown offset, see how well the head is found).

| component | version |
|---|---|
| RTAB-Map | master `832eaaff2444282789cdfc9eef2332c5a7830f7b` (reports 0.24.5), BSD-3-Clause |
| PCL | 1.15.0, static, core point types only, BSD-3-Clause |
| Boost | 1.86.0, static, BSL-1.0 |
| FLANN | 1.9.1, patched for C++17, BSD |
| Eigen | 3.4.0, MPL-2.0 |
| g2o | `20230806_git`, Eigen and PCG solvers only (no CHOLMOD, no CSparse), BSD |
| SQLite | 3.54.1 amalgamation, public domain |
| OpenCV | the app's 4.12.0 `libopencv_java4.so`, linked dynamically, Apache-2.0 |

Left out on purpose: TORO (RTAB-Map's configure labels it Creative Commons non-commercial share-alike, which does not
fit a GPL-3.0 app), GTSAM, Ceres, OpenGV, libpointmatcher, OctoMap.

## Building (Windows, Git Bash; NDK 27.2.12479018 and CMake 3.22.1 under `C:/Android`)

    bash native/slam/src/rtabmap/build_all.sh

Sources and build trees go to `ROOT` (default `C:/uvio/rtab`: Windows wants short paths), the two libraries to
`app/src/main/jniLibs/arm64-v8a`. About half an hour with four jobs. The steps are separate scripts (`00_fetch.sh` to
`07_ultra_slam.sh`); after a change to `ultra_slam.cpp` only the last one is needed.

What had to be worked around:

1. `flann-1.9.1-cxx17.patch`: `std::random_shuffle` and `std::binary_function` are gone from C++17's library.
2. `rtabmap-android-opencv-java4.patch` (with `-DRTABMAP_NO_OPENCV_STITCHING`): `libopencv_java4.so` has no
   `cv::detail::ExposureCompensator`, so `util2d::stereoExposureCompensation` is compiled out. Not used here.
3. `res_tool.py`: RTAB-Map embeds its SQL schema with a host program (`rtabmap-res_tool`); there is no host C++
   compiler on the laptop, so this is that program in Python.
4. `opencv_cfg/OpenCVConfig.cmake`: the unpacked OpenCV AAR has no CMake package file.
5. g2o's own package file refuses to load without OpenGL; RTAB-Map's `FindG2O.cmake` is used instead.
6. In the wrapper projects `cmake_minimum_required` stays under 3.21 (the NDK's legacy toolchain path), and no cache
   variable is called `PREFIX` (it breaks CMake's compiler identification).

Not done: `build_all.sh` has not been run from an empty directory in one go; each step was run by itself.

## What this build can and cannot do

- Corners and descriptors: GFTT + ORB (used), ORB, BRISK, KAZE, SIFT. No SURF, no BRIEF/FREAK (they are in OpenCV's
  contrib modules, which the app's OpenCV does not have).
- Matching a pair to a place: bag of words, then PnP with RANSAC on the place's 3D points (RTAB-Map's own), then g2o
  bundle adjustment. `g2o/Solver` must be 3 (Eigen): the default asks for CSparse, which is not built.
- Stereo depth of a corner: optical flow from the left picture to the right one, on the rectified pair.

## The phone test

    python tools/tracking/hand_pairs_export.py REC.uxrec OUT.pairs
    python tools/tracking/slam_export.py BASALT_CALIB.json TAG-vio.csv OUT_DIR       # rig.txt, poses.txt
    adb push ... /data/local/tmp/slam/        # libultra_slam.so, librtabmap_core.so, libopencv_java4.so, libc++_shared.so, ultra_slam_replay
    LD_LIBRARY_PATH=/data/local/tmp/slam ./ultra_slam_replay a.pairs a.poses a.pairs a.poses rig.txt map.db out.csv 2 45 45 83 3
