cmake_minimum_required(VERSION 3.22)
project(ultra_vio LANGUAGES C CXX)

# Minimal build of the Basalt VIO path (optical flow + sqrt VIO estimator) as
# libultra_vio.so with a C API. Works for Android (NDK toolchain file) and host.
#
# Required cache variables:
#   UVIO_BASALT_DIR  patched checkout of basalt @ 0f3b2b52
#   UVIO_DEPS_DIR    directory with eigen/ sophus/ cereal/ fmt/ oneTBB/
#                    magic_enum/ json/ basalt-headers/ checkouts
# OpenCV (only cv::FAST / core / imgproc are used by the VIO path):
#   Android: UVIO_OPENCV_ANDROID_SO + UVIO_OPENCV_INCLUDE (libopencv_java4.so)
#   host:    OpenCV_DIR (find_package)

set(UVIO_BASALT_DIR "" CACHE PATH "patched basalt checkout")
set(UVIO_DEPS_DIR "" CACHE PATH "dependency checkouts")
set(UVIO_OPENCV_ANDROID_SO "" CACHE FILEPATH "libopencv_java4.so for the target ABI")
set(UVIO_OPENCV_INCLUDE "" CACHE PATH "OpenCV include dir for Android")
set(UVIO_BASALT_REV "0f3b2b5" CACHE STRING "short basalt revision for uvio_version()")
option(UVIO_BUILD_TESTS "Build the host simulation test" OFF)

if(NOT EXISTS "${UVIO_BASALT_DIR}/src/vi_estimator/sqrt_keypoint_vio.cpp")
  message(FATAL_ERROR "UVIO_BASALT_DIR does not point to a basalt checkout")
endif()

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

find_package(Threads REQUIRED)

# ---- oneTBB (static) --------------------------------------------------------
set(TBB_TEST OFF CACHE BOOL "" FORCE)
set(TBB_EXAMPLES OFF CACHE BOOL "" FORCE)
set(TBB_STRICT OFF CACHE BOOL "" FORCE)
set(TBBMALLOC_BUILD OFF CACHE BOOL "" FORCE)
set(TBB_INSTALL OFF CACHE BOOL "" FORCE)
set(TBB_ENABLE_IPO OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${UVIO_DEPS_DIR}/oneTBB ${CMAKE_BINARY_DIR}/oneTBB EXCLUDE_FROM_ALL)

# ---- OpenCV ------------------------------------------------------------------
add_library(uvio_opencv INTERFACE)
if(ANDROID)
  if(NOT EXISTS "${UVIO_OPENCV_ANDROID_SO}")
    message(FATAL_ERROR "UVIO_OPENCV_ANDROID_SO not found: ${UVIO_OPENCV_ANDROID_SO}")
  endif()
  add_library(opencv_java4 SHARED IMPORTED)
  set_target_properties(opencv_java4 PROPERTIES
    IMPORTED_LOCATION "${UVIO_OPENCV_ANDROID_SO}"
    IMPORTED_SONAME "libopencv_java4.so")
  target_include_directories(uvio_opencv SYSTEM INTERFACE "${UVIO_OPENCV_INCLUDE}")
  target_link_libraries(uvio_opencv INTERFACE opencv_java4)
else()
  find_package(OpenCV REQUIRED COMPONENTS core imgproc features2d)
  target_include_directories(uvio_opencv SYSTEM INTERFACE ${OpenCV_INCLUDE_DIRS})
  target_link_libraries(uvio_opencv INTERFACE ${OpenCV_LIBS})
endif()

# ---- Basalt VIO core (static) -----------------------------------------------
set(B ${UVIO_BASALT_DIR})
add_library(basalt_vio_core STATIC
  ${B}/src/linearization/landmark_block.cpp
  ${B}/src/linearization/linearization_abs_qr.cpp
  ${B}/src/linearization/linearization_abs_sc.cpp
  ${B}/src/linearization/linearization_base.cpp
  ${B}/src/linearization/linearization_rel_sc.cpp
  ${B}/src/optical_flow/optical_flow.cpp
  ${B}/src/utils/keypoints.cpp
  ${B}/src/utils/system_utils.cpp
  ${B}/src/utils/time_utils.cpp
  ${B}/src/utils/vio_config.cpp
  ${B}/src/vi_estimator/ba_base.cpp
  ${B}/src/vi_estimator/landmark_database.cpp
  ${B}/src/vi_estimator/marg_helper.cpp
  ${B}/src/vi_estimator/sc_ba_base.cpp
  ${B}/src/vi_estimator/sqrt_ba_base.cpp
  ${B}/src/vi_estimator/sqrt_keypoint_vio.cpp
  ${B}/src/vi_estimator/sqrt_keypoint_vo.cpp
  ${B}/src/vi_estimator/vio_estimator.cpp
)
target_include_directories(basalt_vio_core PUBLIC ${B}/include)
target_include_directories(basalt_vio_core SYSTEM PUBLIC
  ${UVIO_DEPS_DIR}/basalt-headers/include
  ${UVIO_DEPS_DIR}/eigen
  ${UVIO_DEPS_DIR}/sophus
  ${UVIO_DEPS_DIR}/cereal/include
  ${UVIO_DEPS_DIR}/fmt/include
  ${UVIO_DEPS_DIR}/magic_enum/include
  ${UVIO_DEPS_DIR}/json/include
)
target_compile_definitions(basalt_vio_core PUBLIC
  # float only: XR Gate runs the estimator with use_double=false. Dropping the
  # double instantiation halves compile time and code size.
  BASALT_INSTANTIATIONS_FLOAT
  BASALT_NO_OPENGV          # see patches/0001: relative-pose RANSAC is mapper-only
  EIGEN_DONT_PARALLELIZE
  FMT_HEADER_ONLY
)
target_compile_options(basalt_vio_core PRIVATE
  -Wall -Wextra -Wno-unused-parameter -Wno-deprecated-declarations -Wno-parentheses)
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
  target_compile_options(basalt_vio_core PUBLIC
    -Wno-exceptions -Wno-missing-template-arg-list-after-template-kw -Wno-unknown-warning-option
    -Wno-misleading-indentation -Wno-deprecated-copy -Wno-unused-but-set-variable -Wno-unused-variable)
  if(CMAKE_CXX_COMPILER_VERSION VERSION_LESS 20)
    target_compile_options(basalt_vio_core PUBLIC -frelaxed-template-template-args)
  endif()
else()
  target_compile_options(basalt_vio_core PRIVATE -Wno-maybe-uninitialized -Wno-deprecated-copy)
endif()
target_link_libraries(basalt_vio_core PUBLIC TBB::tbb uvio_opencv Threads::Threads)

# ---- libultra_vio.so ---------------------------------------------------------
add_library(ultra_vio SHARED ${CMAKE_CURRENT_SOURCE_DIR}/ultra_vio.cpp)
target_include_directories(ultra_vio PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/../include)
target_compile_definitions(ultra_vio PRIVATE UVIO_BASALT_REV="${UVIO_BASALT_REV}")
target_compile_options(ultra_vio PRIVATE -Wall -Wextra)
target_link_libraries(ultra_vio PRIVATE basalt_vio_core)
set_target_properties(ultra_vio PROPERTIES LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/ultra_vio.map)
target_link_options(ultra_vio PRIVATE
  -Wl,--exclude-libs,ALL
  -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/ultra_vio.map
  -Wl,--no-undefined
  -Wl,-z,max-page-size=16384
  -Wl,--gc-sections)
if(ANDROID)
  target_link_libraries(ultra_vio PRIVATE log)
endif()

if(UVIO_BUILD_TESTS AND NOT ANDROID)
  add_executable(uvio_sim_test ${CMAKE_CURRENT_SOURCE_DIR}/../test/uvio_sim_test.cpp)
  # The test talks to the estimator through the uvio_* C API only. Eigen/Sophus
  # and basalt-headers are used for the generator (camera model, calibration
  # JSON), not for estimation.
  target_include_directories(uvio_sim_test SYSTEM PRIVATE
    ${UVIO_DEPS_DIR}/basalt-headers/include
    ${UVIO_DEPS_DIR}/eigen
    ${UVIO_DEPS_DIR}/sophus
    ${UVIO_DEPS_DIR}/cereal/include
    ${UVIO_DEPS_DIR}/fmt/include)
  target_compile_definitions(uvio_sim_test PRIVATE FMT_HEADER_ONLY)
  target_link_libraries(uvio_sim_test PRIVATE ultra_vio Threads::Threads)
endif()
