cmake_minimum_required(VERSION 3.22.1)
project(ultraxr C CXX)

# Prebuilt tracking libraries (built by the scripts under native/, see native/README.md).
set(PREBUILT ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
add_library(ultra_vio SHARED IMPORTED)
set_target_properties(ultra_vio PROPERTIES IMPORTED_LOCATION ${PREBUILT}/libultra_vio.so)
add_library(ultra_hands SHARED IMPORTED)
set_target_properties(ultra_hands PROPERTIES IMPORTED_LOCATION ${PREBUILT}/libultra_hands.so)

add_library(ultra_slam SHARED IMPORTED)
set_target_properties(ultra_slam PROPERTIES IMPORTED_LOCATION ${PREBUILT}/libultra_slam.so)

add_library(ultraxr SHARED iso.c tracking_jni.cpp)
target_include_directories(ultraxr PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_options(ultraxr PRIVATE -Wall -Wextra -O2)
target_link_libraries(ultraxr ultra_vio ultra_hands ultra_slam log)
target_link_options(ultraxr PRIVATE "-Wl,-z,max-page-size=16384")
