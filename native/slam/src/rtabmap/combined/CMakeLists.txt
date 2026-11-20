# One shared library holding rtabmap_core + rtabmap_utilite and every static dependency
# (PCL, Boost, FLANN, g2o, SQLite). Only libopencv_java4.so, libc++_shared.so and NDK system libs stay dynamic.
cmake_minimum_required(VERSION 3.16) # <3.21 keeps the NDK legacy toolchain file path
project(rtabmap_android_combined C CXX)
set(DEPS_PREFIX "" CACHE PATH "install prefix holding the static libraries")
set(RTABMAP_BUILD "" CACHE PATH "rtabmap build tree (librtabmap_core.a / librtabmap_utilite.a)")
find_package(OpenCV REQUIRED)
find_package(PCL 1.15 REQUIRED COMPONENTS common io kdtree search surface filters registration sample_consensus segmentation)
# g2oConfig.cmake insists on OpenGL, so the g2o archives are listed directly
file(GLOB G2O_LIBS "${DEPS_PREFIX}/lib/libg2o_*.a")
find_package(ZLIB REQUIRED)
file(GLOB_RECURSE RTAB_CORE_A "${RTABMAP_BUILD}/librtabmap_core.a")
file(GLOB_RECURSE RTAB_UTIL_A "${RTABMAP_BUILD}/librtabmap_utilite.a")
if(NOT RTAB_CORE_A OR NOT RTAB_UTIL_A)
  message(FATAL_ERROR "librtabmap_core.a / librtabmap_utilite.a not found under ${RTABMAP_BUILD}")
endif()
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/empty.cpp" "// all code comes from the whole-archived static libraries\n")
add_library(rtabmap_core SHARED "${CMAKE_CURRENT_BINARY_DIR}/empty.cpp")
list(REMOVE_ITEM PCL_LIBRARIES debug optimized)
target_link_libraries(rtabmap_core PRIVATE
  -Wl,--whole-archive ${RTAB_CORE_A} ${RTAB_UTIL_A} -Wl,--no-whole-archive
  ${PCL_LIBRARIES}
  -Wl,--start-group ${G2O_LIBS} -Wl,--end-group
  "${DEPS_PREFIX}/lib/libsqlite3.a"
  ${OpenCV_LIBS} ZLIB::ZLIB log android
  -Wl,--gc-sections -Wl,--no-undefined -Wl,-z,max-page-size=16384)
