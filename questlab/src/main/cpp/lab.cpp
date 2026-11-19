// Ultra XR: a synthetic Quest API test. It does what a Quest mixed-reality game does on start-up and in its first
// seconds (ask for Meta's extensions, start passthrough, read the room through the Scene API, make and find an
// anchor, read the hand aim state) and writes down what each call answered.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <dlfcn.h>
#include <unistd.h>
#include <cmath>
#include <cstdarg>
#include <cstring>
#include <string>
#include <vector>

static std::string report; static int passed = 0, failed = 0;
static void line(const char *fmt, ...) {
    char b[1024]; va_list a; va_start(a, fmt); vsnprintf(b, sizeof b, fmt, a); va_end(a);
    report += b; report += "\n"; __android_log_print(ANDROID_LOG_INFO, "QuestLab", "%s", b);
}
static bool check(bool ok, const char *what, const char *fmt = "", ...) {
    char b[512] = ""; va_list a; va_start(a, fmt); vsnprintf(b, sizeof b, fmt, a); va_end(a);
    line("%s  %s%s%s%s", ok ? "PASS" : "FAIL", what, b[0] ? "  [" : "", b, b[0] ? "]" : ""); (ok ? passed : failed)++; return ok;
}
static const char *res(XrResult r) { static char b[32]; snprintf(b, sizeof b, "%d", (int)r); return b; }

static XrInstance inst; static XrSession sess; static XrSpace local, view;
static std::vector<XrEventDataBuffer> seen;
static XrSessionState state = XR_SESSION_STATE_UNKNOWN; static bool begun = false;
static XrTime frameTime = 0;

template <class F> static F fn(const char *name) { PFN_xrVoidFunction f = nullptr; xrGetInstanceProcAddr(inst, name, &f); return (F)f; }

static PFN_xrCreatePassthroughLayerFB noop;
static XrPassthroughLayerFB passLayer = XR_NULL_HANDLE;

static void pump() {
    XrEventDataBuffer e{XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(inst, &e) == XR_SUCCESS) {
        if (e.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            state = ((XrEventDataSessionStateChanged *)&e)->state;
            if (state == XR_SESSION_STATE_READY && !begun) { XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO}; bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO; begun = XR_SUCCEEDED(xrBeginSession(sess, &bi)); }
            if (state == XR_SESSION_STATE_STOPPING && begun) { xrEndSession(sess); begun = false; }
        } else seen.push_back(e);
        e = {XR_TYPE_EVENT_DATA_BUFFER};
    }
}
// One frame: no picture of our own, only (when there is one) the passthrough layer, as a game's first frames have.
static void frame() {
    pump();
    if (!begun) { usleep(20000); return; }
    XrFrameState fs{XR_TYPE_FRAME_STATE}; if (XR_FAILED(xrWaitFrame(sess, nullptr, &fs))) { usleep(20000); return; }
    frameTime = fs.predictedDisplayTime;
    xrBeginFrame(sess, nullptr);
    XrCompositionLayerPassthroughFB pl{XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB}; pl.layerHandle = passLayer; pl.flags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
    const XrCompositionLayerBaseHeader *layers[1] = {(const XrCompositionLayerBaseHeader *)&pl};
    XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO}; ei.displayTime = fs.predictedDisplayTime; ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    ei.layerCount = passLayer ? 1 : 0; ei.layers = passLayer ? layers : nullptr;
    static int endFails = 0; XrResult r = xrEndFrame(sess, &ei); if (XR_FAILED(r) && endFails++ == 0) line("note  xrEndFrame answered %s", res(r));
}
static bool waitEvent(XrStructureType type, XrAsyncRequestIdFB id, XrEventDataBuffer *out, int frames = 90) {
    for (int i = 0; i < frames; i++) {
        for (size_t k = 0; k < seen.size(); k++) {
            // every FB completion event starts: type, next, requestId
            struct Head { XrStructureType type; const void *next; XrAsyncRequestIdFB requestId; } *h = (Head *)&seen[k];
            if (h->type == type && h->requestId == id) { *out = seen[k]; seen.erase(seen.begin() + k); return true; }
        }
        frame();
    }
    return false;
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_ultraxr_questlab_LabActivity_run(JNIEnv *env, jobject, jobject activity, jint seconds) {
    report.clear(); passed = failed = 0; seen.clear(); state = XR_SESSION_STATE_UNKNOWN; begun = false; passLayer = XR_NULL_HANDLE;
    JavaVM *vm; env->GetJavaVM(&vm); jobject act = env->NewGlobalRef(activity);
    line("Quest API test: what a Quest mixed-reality game asks of its runtime, asked here");

    // ---- the loader and what is on offer
    PFN_xrInitializeLoaderKHR init = nullptr; xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", (PFN_xrVoidFunction *)&init);
    XrLoaderInitInfoAndroidKHR li{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR}; li.applicationVM = vm; li.applicationContext = act;
    if (!check(init && XR_SUCCEEDED(init((const XrLoaderInitInfoBaseHeaderKHR *)&li)), "the OpenXR loader starts and finds a runtime")) return env->NewStringUTF(report.c_str());
    uint32_t n = 0; xrEnumerateInstanceExtensionProperties(nullptr, 0, &n, nullptr);
    std::vector<XrExtensionProperties> ext(n, {XR_TYPE_EXTENSION_PROPERTIES}); xrEnumerateInstanceExtensionProperties(nullptr, n, &n, ext.data());
    auto offered = [&](const char *name) { for (auto &e : ext) if (strcmp(e.extensionName, name) == 0) return true; return false; };
    const char *meta[] = {"XR_FB_passthrough", "XR_FB_spatial_entity", "XR_FB_spatial_entity_query", "XR_FB_spatial_entity_storage", "XR_FB_spatial_entity_container", "XR_FB_scene", "XR_FB_hand_tracking_aim"};
    const char *base[] = {"XR_KHR_android_create_instance", "XR_KHR_opengl_es_enable", "XR_EXT_hand_tracking"};
    // what Quest games commonly also ask for; listed so a missing one is known before a real game trips on it
    const char *alsoAsked[] = {"XR_FB_display_refresh_rate", "XR_FB_color_space", "XR_FB_swapchain_update_state", "XR_FB_swapchain_update_state_opengl_es", "XR_FB_foveation", "XR_FB_foveation_configuration",
        "XR_FB_composition_layer_alpha_blend", "XR_FB_composition_layer_settings", "XR_KHR_composition_layer_color_scale_bias", "XR_KHR_composition_layer_cylinder", "XR_KHR_composition_layer_equirect2",
        "XR_FB_hand_tracking_mesh", "XR_FB_hand_tracking_capsules", "XR_META_performance_metrics", "XR_EXT_performance_settings", "XR_KHR_android_thread_settings", "XR_FB_touch_controller_pro",
        "XR_META_touch_controller_plus", "XR_FB_haptic_pcm", "XR_FB_triangle_mesh", "XR_META_spatial_entity_mesh", "XR_META_environment_depth", "XR_FB_scene_capture", "XR_FB_spatial_entity_sharing",
        "XR_FB_spatial_entity_user", "XR_META_boundary_visibility", "XR_FB_passthrough_keyboard_hands", "XR_META_passthrough_preferences", "XR_FB_body_tracking", "XR_FB_face_tracking2", "XR_EXT_local_floor"};
    std::vector<const char *> want;
    for (auto e : base) { check(offered(e), "offered", "%s", e); if (offered(e)) want.push_back(e); }
    for (auto e : meta) { check(offered(e), "offered (a Meta mixed-reality extension)", "%s", e); if (offered(e)) want.push_back(e); }
    std::string missing, present; for (auto e : alsoAsked) { (offered(e) ? present : missing) += std::string(e + 3) + " "; }
    line("info  also offered: %s", present.c_str()); line("info  NOT offered (a game that requires one of these will not start): %s", missing.c_str());

    // ---- instance, system, session
    XrInstanceCreateInfoAndroidKHR ai{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR}; ai.applicationVM = vm; ai.applicationActivity = act;
    XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO}; ci.next = &ai; strcpy(ci.applicationInfo.applicationName, "QuestApiLab"); ci.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
    ci.enabledExtensionCount = (uint32_t)want.size(); ci.enabledExtensionNames = want.data();
    XrResult r = xrCreateInstance(&ci, &inst);
    if (!check(XR_SUCCEEDED(r), "an instance with all of them enabled", "%s", res(r))) return env->NewStringUTF(report.c_str());
    XrSystemGetInfo sg{XR_TYPE_SYSTEM_GET_INFO}; sg.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY; XrSystemId sys = 0; r = xrGetSystem(inst, &sg, &sys);
    if (!check(XR_SUCCEEDED(r), "a head-mounted system", "%s", res(r))) return env->NewStringUTF(report.c_str());
    XrSystemSpatialEntityPropertiesFB sp{XR_TYPE_SYSTEM_SPATIAL_ENTITY_PROPERTIES_FB}; XrSystemPassthroughPropertiesFB pp{XR_TYPE_SYSTEM_PASSTHROUGH_PROPERTIES_FB, &sp};
    XrSystemHandTrackingPropertiesEXT hp{XR_TYPE_SYSTEM_HAND_TRACKING_PROPERTIES_EXT, &pp}; XrSystemProperties props{XR_TYPE_SYSTEM_PROPERTIES, &hp};
    xrGetSystemProperties(inst, sys, &props);
    check(pp.supportsPassthrough, "the system says it supports passthrough"); check(sp.supportsSpatialEntity, "the system says it supports spatial entities");
    check(hp.supportsHandTracking, "the system says it supports hand tracking", "%s, %ux%u a view", props.systemName, props.graphicsProperties.maxSwapchainImageWidth, props.graphicsProperties.maxSwapchainImageHeight);

    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY); eglInitialize(dpy, nullptr, nullptr);
    const EGLint ca[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig cfg; EGLint nc = 0; eglChooseConfig(dpy, ca, &cfg, 1, &nc);
    const EGLint xa[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE}; EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, xa);
    const EGLint pa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE}; EGLSurface pb = eglCreatePbufferSurface(dpy, cfg, pa); eglMakeCurrent(dpy, pb, pb, ctx);
    auto greq = fn<PFN_xrGetOpenGLESGraphicsRequirementsKHR>("xrGetOpenGLESGraphicsRequirementsKHR"); XrGraphicsRequirementsOpenGLESKHR gr{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR}; if (greq) greq(inst, sys, &gr);
    XrGraphicsBindingOpenGLESAndroidKHR gb{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR}; gb.display = dpy; gb.config = cfg; gb.context = ctx;
    XrSessionCreateInfo si{XR_TYPE_SESSION_CREATE_INFO}; si.next = &gb; si.systemId = sys; r = xrCreateSession(inst, &si, &sess);
    if (!check(XR_SUCCEEDED(r), "a session (OpenGL ES)", "%s", res(r))) { xrDestroyInstance(inst); return env->NewStringUTF(report.c_str()); }
    XrReferenceSpaceCreateInfo rs{XR_TYPE_REFERENCE_SPACE_CREATE_INFO}; rs.poseInReferenceSpace = {{0, 0, 0, 1}, {0, 0, 0}};
    rs.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL; xrCreateReferenceSpace(sess, &rs, &local); rs.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW; xrCreateReferenceSpace(sess, &rs, &view);
    for (int i = 0; i < 150 && !begun; i++) frame();
    check(begun, "the session begins", "state %d", (int)state);

    // ---- passthrough, as a game turns it on
    auto cPass = fn<PFN_xrCreatePassthroughFB>("xrCreatePassthroughFB"); auto cLayer = fn<PFN_xrCreatePassthroughLayerFB>("xrCreatePassthroughLayerFB");
    auto pStart = fn<PFN_xrPassthroughStartFB>("xrPassthroughStartFB"); auto lResume = fn<PFN_xrPassthroughLayerResumeFB>("xrPassthroughLayerResumeFB");
    XrPassthroughFB pass = XR_NULL_HANDLE; XrPassthroughCreateInfoFB pci{XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
    r = cPass ? cPass(sess, &pci, &pass) : XR_ERROR_FUNCTION_UNSUPPORTED; check(XR_SUCCEEDED(r), "xrCreatePassthroughFB", "%s", res(r));
    r = pStart ? pStart(pass) : XR_ERROR_FUNCTION_UNSUPPORTED; check(XR_SUCCEEDED(r), "xrPassthroughStartFB", "%s", res(r));
    XrPassthroughLayerCreateInfoFB plc{XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB}; plc.passthrough = pass; plc.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB; plc.flags = XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB;
    r = cLayer ? cLayer(sess, &plc, &passLayer) : XR_ERROR_FUNCTION_UNSUPPORTED; check(XR_SUCCEEDED(r), "xrCreatePassthroughLayerFB (reconstruction)", "%s", res(r));
    if (lResume && passLayer) lResume(passLayer);
    int framesBefore = 0; for (int i = 0; i < 30; i++) { frame(); framesBefore++; }
    check(begun && state >= XR_SESSION_STATE_SYNCHRONIZED, "frames with a passthrough layer are accepted", "%d frames, session state %d", framesBefore, (int)state);

    // ---- the room through the Scene API
    auto query = fn<PFN_xrQuerySpacesFB>("xrQuerySpacesFB"); auto retrieve = fn<PFN_xrRetrieveSpaceQueryResultsFB>("xrRetrieveSpaceQueryResultsFB");
    auto enumComp = fn<PFN_xrEnumerateSpaceSupportedComponentsFB>("xrEnumerateSpaceSupportedComponentsFB"); auto setComp = fn<PFN_xrSetSpaceComponentStatusFB>("xrSetSpaceComponentStatusFB");
    auto getComp = fn<PFN_xrGetSpaceComponentStatusFB>("xrGetSpaceComponentStatusFB"); auto labels = fn<PFN_xrGetSpaceSemanticLabelsFB>("xrGetSpaceSemanticLabelsFB");
    auto box2 = fn<PFN_xrGetSpaceBoundingBox2DFB>("xrGetSpaceBoundingBox2DFB"); auto layout = fn<PFN_xrGetSpaceRoomLayoutFB>("xrGetSpaceRoomLayoutFB");
    auto container = fn<PFN_xrGetSpaceContainerFB>("xrGetSpaceContainerFB"); auto boundary = fn<PFN_xrGetSpaceBoundary2DFB>("xrGetSpaceBoundary2DFB");
    // the room data comes from the Ultra XR app a couple of times a second: give it a moment
    for (int i = 0; i < 90; i++) frame();
    std::vector<XrSpaceQueryResultFB> found;
    if (check(query && retrieve, "the spatial entity query calls exist")) {
        XrSpaceQueryInfoFB qi{XR_TYPE_SPACE_QUERY_INFO_FB}; qi.queryAction = XR_SPACE_QUERY_ACTION_LOAD_FB; qi.maxResultCount = 64;
        XrAsyncRequestIdFB id = 0; r = query(sess, (const XrSpaceQueryInfoBaseHeaderFB *)&qi, &id); check(XR_SUCCEEDED(r), "xrQuerySpacesFB (everything stored)", "%s", res(r));
        XrEventDataBuffer ev; bool avail = waitEvent(XR_TYPE_EVENT_DATA_SPACE_QUERY_RESULTS_AVAILABLE_FB, id, &ev); bool done = waitEvent(XR_TYPE_EVENT_DATA_SPACE_QUERY_COMPLETE_FB, id, &ev);
        check(avail && done, "the query's two events arrive (results available, complete)");
        XrSpaceQueryResultsFB qr{XR_TYPE_SPACE_QUERY_RESULTS_FB}; retrieve(sess, id, &qr); found.resize(qr.resultCountOutput); qr.resultCapacityInput = (uint32_t)found.size(); qr.results = found.data();
        r = retrieve(sess, id, &qr); check(XR_SUCCEEDED(r) && !found.empty(), "xrRetrieveSpaceQueryResultsFB", "%u entities", qr.resultCountOutput);
    }
    int floors = 0, ceilings = 0, walls = 0, tables = 0, rooms = 0, located = 0; XrSpace roomSpace = XR_NULL_HANDLE; float floorY = NAN;
    for (auto &f : found) {
        uint32_t cn = 0; XrSpaceComponentTypeFB comps[16]; enumComp(f.space, 16, &cn, comps);
        bool loc = false, sem = false, b2 = false, lay = false;
        for (uint32_t i = 0; i < cn; i++) { loc |= comps[i] == XR_SPACE_COMPONENT_TYPE_LOCATABLE_FB; sem |= comps[i] == XR_SPACE_COMPONENT_TYPE_SEMANTIC_LABELS_FB; b2 |= comps[i] == XR_SPACE_COMPONENT_TYPE_BOUNDED_2D_FB; lay |= comps[i] == XR_SPACE_COMPONENT_TYPE_ROOM_LAYOUT_FB; }
        if (lay) { rooms++; roomSpace = f.space; continue; }
        char lab[64] = ""; if (sem) { XrSemanticLabelsFB sl{XR_TYPE_SEMANTIC_LABELS_FB}; sl.bufferCapacityInput = 63; sl.buffer = lab; labels(sess, f.space, &sl); lab[sl.bufferCountOutput < 63 ? sl.bufferCountOutput : 63] = 0; }
        XrRect2Df rect{}; if (b2) box2(sess, f.space, &rect);
        XrSpaceLocation l{XR_TYPE_SPACE_LOCATION}; bool ok = false;
        if (loc) {
            XrSpaceComponentStatusSetInfoFB set{XR_TYPE_SPACE_COMPONENT_STATUS_SET_INFO_FB}; set.componentType = XR_SPACE_COMPONENT_TYPE_LOCATABLE_FB; set.enabled = XR_TRUE; XrAsyncRequestIdFB id = 0;
            XrResult sr = setComp(f.space, &set, &id); XrEventDataBuffer ev; if (XR_SUCCEEDED(sr)) waitEvent(XR_TYPE_EVENT_DATA_SPACE_SET_STATUS_COMPLETE_FB, id, &ev, 30);
            ok = XR_SUCCEEDED(xrLocateSpace(f.space, local, frameTime, &l)) && (l.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT);
        }
        if (ok) located++;
        if (!strcmp(lab, "FLOOR")) { floors++; if (ok) floorY = l.pose.position.y; } else if (!strcmp(lab, "CEILING")) ceilings++; else if (!strcmp(lab, "WALL_FACE")) walls++; else if (!strcmp(lab, "TABLE")) tables++;
        line("info  entity %-9s %.2f x %.2f m at (%.2f, %.2f, %.2f) in the local space%s", lab, rect.extent.width, rect.extent.height, l.pose.position.x, l.pose.position.y, l.pose.position.z, ok ? "" : "  (not located)");
    }
    check(rooms == 1 && floors == 1 && ceilings == 1, "the scene has one room, one floor and one ceiling", "%d rooms, %d floors, %d ceilings, %d walls, %d tables", rooms, floors, ceilings, walls, tables);
    check(located == (int)found.size() - rooms && located > 0, "every plane can be made locatable and is located", "%d of %d", located, (int)found.size() - rooms);
    check(!std::isnan(floorY) && floorY < -0.5f && floorY > -2.5f, "the floor is below the local space's origin by a person's eye height", "%.2f m", floorY);
    if (roomSpace != XR_NULL_HANDLE) {
        XrUuidEXT w[32]; XrRoomLayoutFB rl{XR_TYPE_ROOM_LAYOUT_FB}; rl.wallUuidCapacityInput = 32; rl.wallUuids = w; r = layout(sess, roomSpace, &rl);
        XrUuidEXT u[64]; XrSpaceContainerFB sc{XR_TYPE_SPACE_CONTAINER_FB}; sc.uuidCapacityInput = 64; sc.uuids = u; XrResult r2 = container(sess, roomSpace, &sc);
        check(XR_SUCCEEDED(r) && XR_SUCCEEDED(r2) && (int)rl.wallUuidCountOutput == walls && (int)sc.uuidCountOutput == floors + ceilings + walls + tables, "the room's layout and container list the same planes", "%u walls, %u contained", rl.wallUuidCountOutput, sc.uuidCountOutput);
    }
    check(walls > 0, "at least one wall has been found by the room model (needs the glasses' cameras looking at a room)", "%d", walls);

    // ---- an anchor: made half a metre in front of the head, found again where it was put
    auto cAnchor = fn<PFN_xrCreateSpatialAnchorFB>("xrCreateSpatialAnchorFB"); auto save = fn<PFN_xrSaveSpaceFB>("xrSaveSpaceFB");
    if (check(cAnchor != nullptr, "the anchor call exists")) {
        XrSpatialAnchorCreateInfoFB aci{XR_TYPE_SPATIAL_ANCHOR_CREATE_INFO_FB}; aci.space = view; aci.poseInSpace = {{0, 0, 0, 1}, {0, 0, -0.5f}}; aci.time = frameTime;
        XrSpaceLocation before{XR_TYPE_SPACE_LOCATION}; xrLocateSpace(view, local, frameTime, &before);
        XrAsyncRequestIdFB id = 0; r = cAnchor(sess, &aci, &id); check(XR_SUCCEEDED(r), "xrCreateSpatialAnchorFB", "%s", res(r));
        XrEventDataBuffer ev; if (check(XR_SUCCEEDED(r) && waitEvent(XR_TYPE_EVENT_DATA_SPATIAL_ANCHOR_CREATE_COMPLETE_FB, id, &ev), "the anchor's completion event arrives")) {
            auto *ac = (XrEventDataSpatialAnchorCreateCompleteFB *)&ev; XrSpaceLocation al{XR_TYPE_SPACE_LOCATION}; r = xrLocateSpace(ac->space, local, frameTime, &al);
            float ex = before.pose.position.x, ey = before.pose.position.y, ez = before.pose.position.z;
            // half a metre along the head's forward direction
            XrQuaternionf q = before.pose.orientation; float fx = -2 * (q.x * q.z + q.w * q.y), fy = -2 * (q.y * q.z - q.w * q.x), fz = -(1 - 2 * (q.x * q.x + q.y * q.y));
            ex += 0.5f * fx; ey += 0.5f * fy; ez += 0.5f * fz;
            float d = std::sqrt((al.pose.position.x - ex) * (al.pose.position.x - ex) + (al.pose.position.y - ey) * (al.pose.position.y - ey) + (al.pose.position.z - ez) * (al.pose.position.z - ez));
            check(XR_SUCCEEDED(r) && (before.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) && d < 0.05f, "the anchor is located where it was made", "%.3f m off", d);
            XrSpaceSaveInfoFB svi{XR_TYPE_SPACE_SAVE_INFO_FB}; svi.space = ac->space; svi.location = XR_SPACE_STORAGE_LOCATION_LOCAL_FB; svi.persistenceMode = XR_SPACE_PERSISTENCE_MODE_INDEFINITE_FB; XrAsyncRequestIdFB sid = 0;
            r = save ? save(sess, &svi, &sid) : XR_ERROR_FUNCTION_UNSUPPORTED; check(XR_SUCCEEDED(r) && waitEvent(XR_TYPE_EVENT_DATA_SPACE_SAVE_COMPLETE_FB, sid, &ev), "the anchor is saved (for this run; not yet across runs)", "%s", res(r));
        }
    }

    // ---- hands with Meta's aim state
    auto cHand = fn<PFN_xrCreateHandTrackerEXT>("xrCreateHandTrackerEXT"); auto lHand = fn<PFN_xrLocateHandJointsEXT>("xrLocateHandJointsEXT");
    if (check(cHand && lHand, "the hand tracking calls exist")) {
        int active = 0, withAim = 0; float maxPinch = 0;
        for (int h = 0; h < 2; h++) {
            XrHandTrackerCreateInfoEXT hc{XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT}; hc.hand = h ? XR_HAND_RIGHT_EXT : XR_HAND_LEFT_EXT; hc.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT; XrHandTrackerEXT ht = XR_NULL_HANDLE;
            if (XR_FAILED(cHand(sess, &hc, &ht))) continue;
            for (int i = 0; i < seconds * 6; i++) {
                frame();
                XrHandJointLocationEXT j[XR_HAND_JOINT_COUNT_EXT]; XrHandTrackingAimStateFB aim{XR_TYPE_HAND_TRACKING_AIM_STATE_FB};
                XrHandJointLocationsEXT hl{XR_TYPE_HAND_JOINT_LOCATIONS_EXT, &aim}; hl.jointCount = XR_HAND_JOINT_COUNT_EXT; hl.jointLocations = j;
                XrHandJointsLocateInfoEXT hi{XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT}; hi.baseSpace = local; hi.time = frameTime;
                if (XR_SUCCEEDED(lHand(ht, &hi, &hl)) && hl.isActive) { active++; if (aim.status & XR_HAND_TRACKING_AIM_VALID_BIT_FB) withAim++; if (aim.pinchStrengthIndex > maxPinch) maxPinch = aim.pinchStrengthIndex; }
            }
        }
        line("info  hands: %d readings with a hand, %d with a valid aim state, strongest index pinch %.2f (needs a hand held up in front of the glasses)", active, withAim, maxPinch);
        check(active == withAim, "whenever a hand is tracked its aim state is filled in", "%d of %d", withAim, active);
    }

    // ---- what the layer did meanwhile
    if (void *h = dlopen("libuxr_meta_layer.so", RTLD_NOW)) {
        auto stats = (void (*)(int *, int *, int *, int *))dlsym(h, "uxrMetaLayerStats");
        if (stats) { int packets, dropped, aligned, ents; stats(&packets, &dropped, &aligned, &ents);
            check(packets > 0, "the layer is getting the room from the Ultra XR app", "%d readings", packets);
            check(dropped > 0, "passthrough layers were taken out of the frames before the runtime saw them", "%d", dropped);
            check(aligned == 1, "the room's frame is lined up with the application's space from the head pose"); }
    }
    xrDestroySession(sess); xrDestroyInstance(inst);
    line("%d checks, %d failed", passed + failed, failed);
    return env->NewStringUTF(report.c_str());
}
