// Ultra XR: XR_META_boundary_visibility, which the OpenXR headers this module builds with (1.1.42) do not have yet.
// The values are the registry's (extension 529). SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <openxr/openxr.h>
#ifndef XR_META_boundary_visibility
#define XR_META_boundary_visibility 1
#define XR_META_BOUNDARY_VISIBILITY_EXTENSION_NAME "XR_META_boundary_visibility"
#define XR_BOUNDARY_VISIBILITY_SUPPRESSION_NOT_ALLOWED_META ((XrResult)1000528000)
#define XR_TYPE_SYSTEM_BOUNDARY_VISIBILITY_PROPERTIES_META ((XrStructureType)1000528000)
#define XR_TYPE_EVENT_DATA_BOUNDARY_VISIBILITY_CHANGED_META ((XrStructureType)1000528001)
typedef enum XrBoundaryVisibilityMETA {
    XR_BOUNDARY_VISIBILITY_NOT_SUPPRESSED_META = 1,
    XR_BOUNDARY_VISIBILITY_SUPPRESSED_META = 2,
    XR_BOUNDARY_VISIBILITY_MAX_ENUM_META = 0x7FFFFFFF
} XrBoundaryVisibilityMETA;
typedef struct XrSystemBoundaryVisibilityPropertiesMETA { XrStructureType type; const void *next; XrBool32 supportsBoundaryVisibility; } XrSystemBoundaryVisibilityPropertiesMETA;
typedef struct XrEventDataBoundaryVisibilityChangedMETA { XrStructureType type; const void *next; XrBoundaryVisibilityMETA boundaryVisibility; } XrEventDataBoundaryVisibilityChangedMETA;
typedef XrResult (XRAPI_PTR *PFN_xrRequestBoundaryVisibilityMETA)(XrSession session, XrBoundaryVisibilityMETA boundaryVisibility);
#endif
