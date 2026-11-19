if(NOT TARGET OpenXR::headers)
add_library(OpenXR::headers INTERFACE IMPORTED)
set_target_properties(OpenXR::headers PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "C:/Users/asddd/.gradle/caches/8.14.3/transforms/ea7bca15bd89e7e35c01decaa179b56c/transformed/openxr_loader_for_android-1.1.42/prefab/modules/headers/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

if(NOT TARGET OpenXR::openxr_loader)
add_library(OpenXR::openxr_loader SHARED IMPORTED)
set_target_properties(OpenXR::openxr_loader PROPERTIES
    IMPORTED_LOCATION "C:/Users/asddd/.gradle/caches/8.14.3/transforms/ea7bca15bd89e7e35c01decaa179b56c/transformed/openxr_loader_for_android-1.1.42/prefab/modules/openxr_loader/libs/android.arm64-v8a/libopenxr_loader.so"
    INTERFACE_LINK_LIBRARIES "OpenXR::headers"
)
endif()

