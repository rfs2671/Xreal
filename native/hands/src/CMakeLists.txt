# libultra_hands: Monado "Mercury" optical hand tracking + XR Gate C API as one
# self-contained shared library. Only the Mercury tracker and the handful of
# Monado aux sources it needs are compiled; nothing else of Monado is built.
# SPDX-License-Identifier: BSL-1.0
cmake_minimum_required(VERSION 3.20)
project(ultra_hands LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
if(NOT CMAKE_BUILD_TYPE)
	set(CMAKE_BUILD_TYPE Release)
endif()
string(REPLACE "-O2" "-O3" CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE}")
string(REPLACE "-O2" "-O3" CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE}")

set(MONADO_DIR "" CACHE PATH "Patched Monado checkout (commit 6c98049 + XR Gate overlay/patches + ultra_hands patch)")
set(EIGEN_DIR "" CACHE PATH "Eigen 3.4.0 source dir (header only)")
set(ORT_INCLUDE_DIR "" CACHE PATH "ONNX Runtime headers")
set(ORT_LIBRARY "" CACHE FILEPATH "libonnxruntime.so")
set(OPENCV_INCLUDE_DIR "" CACHE PATH "OpenCV headers (empty: find_package(OpenCV))")
set(OPENCV_LIBRARY "" CACHE FILEPATH "OpenCV library, e.g. libopencv_java4.so")
set(ULTRA_HANDS_MONADO_DESC "6c9804934324+xrgate+ultra_hands" CACHE STRING "")
option(ULTRA_HANDS_BUILD_SMOKE_TEST "Build the C-API smoke test executable" OFF)

foreach(v MONADO_DIR EIGEN_DIR ORT_INCLUDE_DIR ORT_LIBRARY)
	if(NOT ${v})
		message(FATAL_ERROR "${v} must be set")
	endif()
endforeach()

if(OPENCV_INCLUDE_DIR AND OPENCV_LIBRARY)
	set(UH_OPENCV_INCLUDES "${OPENCV_INCLUDE_DIR}")
	set(UH_OPENCV_LIBS "${OPENCV_LIBRARY}")
else()
	find_package(OpenCV REQUIRED COMPONENTS core imgproc calib3d)
	set(UH_OPENCV_INCLUDES ${OpenCV_INCLUDE_DIRS})
	set(UH_OPENCV_LIBS ${OpenCV_LIBS})
endif()

set(X "${MONADO_DIR}/src/xrt")
set(M "${X}/tracking/hand/mercury")
set(A "${X}/auxiliary")
configure_file(u_git_tag.c.in "${CMAKE_CURRENT_BINARY_DIR}/u_git_tag.c" @ONLY)

add_library(
	ultra_hands SHARED
	# Mercury + XR Gate C API
	${M}/hg_sync.cpp
	${M}/hg_model.cpp
	${M}/hg_image_distorter.cpp
	${M}/kine_lm/lm_main.cpp
	${M}/kine_lm/lm_hand_init_guesser.cpp
	${M}/xr_mercury_runtime_c_api.cpp
	# project-owned extension (thread knobs, create error)
	ext/ultra_hands_ext.cpp
	# Monado aux subset
	${A}/onnx/onnx_wrapper.cpp
	${A}/tracking/t_data_utils.c
	${A}/math/m_base.cpp
	${A}/math/m_hash.cpp
	${A}/math/m_quatexpmap.cpp
	${A}/math/m_optics.c
	${A}/math/m_space.cpp
	${A}/math/m_filter_one_euro.c
	${A}/os/os_time.cpp
	${A}/util/u_debug.c
	${A}/util/u_logging.c
	${A}/util/u_misc.c
	${A}/util/u_format.c
	${A}/util/u_frame.c
	${A}/util/u_hand_tracking.c
	${A}/util/u_time.cpp
	${A}/util/u_trace_marker.c
	${A}/util/u_var.cpp
	${A}/util/u_worker.c
	${A}/util/u_worker.cpp
	"${CMAKE_CURRENT_BINARY_DIR}/u_git_tag.c"
	# bundled third party
	${MONADO_DIR}/src/external/cjson/cjson/cJSON.c
	)

target_compile_definitions(ultra_hands PRIVATE XR_MERCURY_RUNTIME_BUILD)
target_include_directories(
	ultra_hands
	PRIVATE
		${CMAKE_CURRENT_SOURCE_DIR}/config
		${CMAKE_CURRENT_SOURCE_DIR}/ext
		${X}/include
		${A}
		${M}
		${MONADO_DIR}/src/external/cjson
		${MONADO_DIR}/src/external/tinyceres/include
	)
target_include_directories(
	ultra_hands SYSTEM PRIVATE ${EIGEN_DIR} ${UH_OPENCV_INCLUDES} ${ORT_INCLUDE_DIR}
	)
target_compile_options(ultra_hands PRIVATE -Wall -Wno-unused-parameter -Wno-missing-braces)
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
	target_compile_options(ultra_hands PRIVATE -ftemplate-backtrace-limit=20)
endif()

find_package(Threads REQUIRED)
target_link_libraries(ultra_hands PRIVATE ${UH_OPENCV_LIBS} ${ORT_LIBRARY} Threads::Threads)
if(ANDROID)
	target_link_libraries(ultra_hands PRIVATE log)
endif()
target_link_options(
	ultra_hands
	PRIVATE
		-Wl,--no-undefined
		-Wl,--exclude-libs,ALL
		-Wl,-Bsymbolic
		-Wl,-z,max-page-size=16384
		-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/ultra_hands.map
		-Wl,--gc-sections
	)
target_compile_options(ultra_hands PRIVATE -ffunction-sections -fdata-sections)
set_target_properties(ultra_hands PROPERTIES LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/ultra_hands.map)

if(ULTRA_HANDS_BUILD_SMOKE_TEST)
	# Uses only the public C headers + libultra_hands.so (no OpenCV/ORT/Monado).
	add_executable(ultra_hands_smoke ../test/ultra_hands_smoke.c)
	target_include_directories(ultra_hands_smoke PRIVATE ${M} ${CMAKE_CURRENT_SOURCE_DIR}/ext)
	target_link_libraries(ultra_hands_smoke PRIVATE ultra_hands m)
endif()
