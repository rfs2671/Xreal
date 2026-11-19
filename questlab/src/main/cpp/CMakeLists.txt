cmake_minimum_required(VERSION 3.22.1)
project(questlab CXX)
set(CMAKE_CXX_STANDARD 17)
find_package(OpenXR REQUIRED CONFIG)
# The layer: answers XR_FB_passthrough, XR_FB_spatial_entity*, XR_FB_scene and XR_FB_hand_tracking_aim itself.
add_library(uxr_meta_layer SHARED meta_layer.cpp)
target_link_libraries(uxr_meta_layer OpenXR::headers log EGL GLESv3)
# The test: calls those extensions as a Quest game would.
add_library(questlab SHARED lab.cpp)
target_link_libraries(questlab OpenXR::openxr_loader log EGL GLESv3 android vulkan)
