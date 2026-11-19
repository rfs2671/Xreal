// Ultra XR: Meta's mixed-reality OpenXR extensions, answered by an API layer.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// A Quest game asks its runtime for XR_FB_passthrough, XR_FB_spatial_entity (and _query, _storage, _container),
// XR_FB_scene and XR_FB_hand_tracking_aim. The runtime on the phone (Monado) has none of them. This layer sits
// between the game and the runtime, inside the game's own process, and answers them:
//
//   passthrough   accepted, and its composition layers are taken out of each frame: on see-through glasses the real
//                 world is already there, and what is not drawn is what is seen through.
//   scene         the room, floor, ceiling, walls and table tops that the Ultra XR app has found, read from the app's
//                 own http://127.0.0.1:8765/room.json (the same data its WebXR pages get), as spatial entities with
//                 the components and semantic labels Meta's Scene API gives.
//   anchors       places kept in the room's frame; they follow the room when the game's own space is recentred.
//   hand aim      pinch strengths and an aim pose worked out from the runtime's hand joints.
//   room mesh     XR_META_spatial_entity_mesh: one GLOBAL_MESH entity, its triangles the floor, ceiling, walls and
//                 table tops above (flat faces; not a scan of the room's real shape).
//   hand mesh     XR_FB_hand_tracking_mesh: a plain hand made here (a slab for the palm, a tube for each finger),
//                 bound to the runtime's 26 joints.
//   scene capture XR_FB_scene_capture: there is no room set-up screen to open, the room is found as one looks around;
//                 the request is answered as finished.
//   sharing       XR_FB_spatial_entity_sharing and _user: there is no service to share through; a share request
//                 completes with "cloud storage disabled", which is what a Quest with sharing switched off answers.
//   boundary      XR_META_boundary_visibility: there is no boundary; asking to hide it succeeds.
//   depth         XR_META_environment_depth, for OpenGL ES sessions: a depth picture an eye, drawn here from the
//                 scene's planes (floor, ceiling, walls, table tops) as each eye sees them at the asked time. It
//                 hides game objects behind the walls and tables the room model has; it knows nothing of hands,
//                 people or anything else that is not one of those planes. Vulkan sessions are told the feature
//                 is unsupported.
//
// Everything else goes to the runtime untouched. One instance and one session at a time, as on a headset.
#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <openxr/openxr_loader_negotiation.h>
#include "meta_extra.h"
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <time.h>
#include <sys/stat.h>
#include <array>
#include <cstdarg>
#include <cstdio>
#include <utility>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <atomic>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "UltraXRMeta", __VA_ARGS__)

namespace {
const char *LAYER = "XR_APILAYER_ULTRAXR_meta";
const char *OURS[] = {"XR_FB_passthrough", "XR_FB_spatial_entity", "XR_FB_spatial_entity_query", "XR_FB_spatial_entity_storage",
                      "XR_FB_spatial_entity_container", "XR_FB_scene", "XR_FB_hand_tracking_aim", "XR_FB_scene_capture",
                      "XR_FB_spatial_entity_sharing", "XR_FB_spatial_entity_user", "XR_META_spatial_entity_mesh", "XR_FB_hand_tracking_mesh",
                      "XR_META_boundary_visibility", "XR_META_environment_depth"};

PFN_xrGetInstanceProcAddr nextGipa = nullptr;
XrInstance instance = XR_NULL_HANDLE;
XrSession session = XR_NULL_HANDLE;
XrSpace roomRef = XR_NULL_HANDLE, viewRef = XR_NULL_HANDLE;      // ours, on the real runtime: local-floor and the head
XrTime lastTime = 0;

// the runtime's own functions
PFN_xrDestroyInstance rDestroyInstance; PFN_xrGetSystemProperties rGetSystemProperties; PFN_xrCreateSession rCreateSession;
PFN_xrDestroySession rDestroySession; PFN_xrCreateReferenceSpace rCreateReferenceSpace; PFN_xrDestroySpace rDestroySpace;
PFN_xrLocateSpace rLocateSpace; PFN_xrPollEvent rPollEvent; PFN_xrWaitFrame rWaitFrame; PFN_xrEndFrame rEndFrame;
PFN_xrLocateHandJointsEXT rLocateHandJoints; PFN_xrCreateHandTrackerEXT rCreateHandTracker; PFN_xrLocateViews rLocateViews;
bool sessionGles = false;

std::mutex lock;

// ---------------------------------------------------------------- diagnostics: what a game asked for and did not get
// Everything here goes to logcat under the tag UltraXRQuest and to a file the game's process can write:
// /sdcard/Android/data/<package>/files/uxr_quest_diag.txt (or /data/data/<package>/ when that folder cannot be made).
// tools/quest/quest_diag.py collects both, with the crash dump Android itself writes.
PFN_xrCreateSwapchain rCreateSwapchain; PFN_xrSuggestInteractionProfileBindings rSuggestBindings; PFN_xrAttachSessionActionSets rAttachActionSets;
PFN_xrBeginSession rBeginSession; PFN_xrSyncActions rSyncActions; PFN_xrPathToString rPathToString; PFN_xrStringToPath rStringToPath;
PFN_xrGetCurrentInteractionProfile rCurrentProfile; PFN_xrEnumerateSwapchainFormats rSwapchainFormats; PFN_xrEnumerateEnvironmentBlendModes rBlendModes;
std::string packageName() {
    char name[256] = ""; FILE *f = fopen("/proc/self/cmdline", "r"); if (f) { fread(name, 1, sizeof name - 1, f); fclose(f); }
    for (char *c = name; *c; c++) if (*c == ':') { *c = 0; break; }      // a process name may be package:suffix
    return name;
}
int diagFd = -1; std::mutex diagLock; std::string diagPath; timespec diagStart{};
void diagOpen() {      // diagLock held
    if (diagFd >= 0 || diagStart.tv_sec) return;
    clock_gettime(CLOCK_MONOTONIC, &diagStart); std::string pkg = packageName(); if (pkg.empty()) return;
    std::string dir = "/sdcard/Android/data/" + pkg; mkdir(dir.c_str(), 0770); dir += "/files"; mkdir(dir.c_str(), 0770);
    for (std::string p : {dir + "/uxr_quest_diag.txt", "/data/data/" + pkg + "/uxr_quest_diag.txt"}) {
        diagFd = open(p.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0660); if (diagFd >= 0) { diagPath = p; break; }
    }
}
void diag(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void diag(const char *fmt, ...) {
    char b[1100]; va_list a; va_start(a, fmt); vsnprintf(b, sizeof b - 2, fmt, a); va_end(a);
    __android_log_print(ANDROID_LOG_WARN, "UltraXRQuest", "%s", b);
    std::lock_guard<std::mutex> g(diagLock); diagOpen(); if (diagFd < 0) return;
    timespec now; clock_gettime(CLOCK_MONOTONIC, &now); char t[32]; int n = snprintf(t, sizeof t, "%8.2f  ", (now.tv_sec - diagStart.tv_sec) + (now.tv_nsec - diagStart.tv_nsec) / 1e9);
    write(diagFd, t, n); size_t l = strlen(b); b[l] = '\n'; write(diagFd, b, l + 1);
}
#define ONCE(...) do { static std::atomic<bool> said_{false}; if (!said_.exchange(true)) diag(__VA_ARGS__); } while (0)
const char *resultName(XrResult r) {
    switch (r) {
    case XR_SUCCESS: return "XR_SUCCESS"; case XR_ERROR_VALIDATION_FAILURE: return "XR_ERROR_VALIDATION_FAILURE"; case XR_ERROR_RUNTIME_FAILURE: return "XR_ERROR_RUNTIME_FAILURE";
    case XR_ERROR_OUT_OF_MEMORY: return "XR_ERROR_OUT_OF_MEMORY"; case XR_ERROR_API_VERSION_UNSUPPORTED: return "XR_ERROR_API_VERSION_UNSUPPORTED"; case XR_ERROR_INITIALIZATION_FAILED: return "XR_ERROR_INITIALIZATION_FAILED";
    case XR_ERROR_FUNCTION_UNSUPPORTED: return "XR_ERROR_FUNCTION_UNSUPPORTED"; case XR_ERROR_FEATURE_UNSUPPORTED: return "XR_ERROR_FEATURE_UNSUPPORTED"; case XR_ERROR_EXTENSION_NOT_PRESENT: return "XR_ERROR_EXTENSION_NOT_PRESENT";
    case XR_ERROR_LIMIT_REACHED: return "XR_ERROR_LIMIT_REACHED"; case XR_ERROR_SIZE_INSUFFICIENT: return "XR_ERROR_SIZE_INSUFFICIENT"; case XR_ERROR_HANDLE_INVALID: return "XR_ERROR_HANDLE_INVALID";
    case XR_ERROR_INSTANCE_LOST: return "XR_ERROR_INSTANCE_LOST"; case XR_ERROR_SESSION_RUNNING: return "XR_ERROR_SESSION_RUNNING"; case XR_ERROR_SESSION_NOT_RUNNING: return "XR_ERROR_SESSION_NOT_RUNNING";
    case XR_ERROR_SESSION_LOST: return "XR_ERROR_SESSION_LOST"; case XR_ERROR_SYSTEM_INVALID: return "XR_ERROR_SYSTEM_INVALID"; case XR_ERROR_PATH_INVALID: return "XR_ERROR_PATH_INVALID";
    case XR_ERROR_PATH_UNSUPPORTED: return "XR_ERROR_PATH_UNSUPPORTED"; case XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED: return "XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED"; case XR_ERROR_LAYER_INVALID: return "XR_ERROR_LAYER_INVALID";
    case XR_ERROR_LAYER_LIMIT_EXCEEDED: return "XR_ERROR_LAYER_LIMIT_EXCEEDED"; case XR_ERROR_SWAPCHAIN_RECT_INVALID: return "XR_ERROR_SWAPCHAIN_RECT_INVALID"; case XR_ERROR_ACTION_TYPE_MISMATCH: return "XR_ERROR_ACTION_TYPE_MISMATCH";
    case XR_ERROR_SESSION_NOT_READY: return "XR_ERROR_SESSION_NOT_READY"; case XR_ERROR_REFERENCE_SPACE_UNSUPPORTED: return "XR_ERROR_REFERENCE_SPACE_UNSUPPORTED"; case XR_ERROR_FILE_ACCESS_ERROR: return "XR_ERROR_FILE_ACCESS_ERROR";
    case XR_ERROR_FORM_FACTOR_UNSUPPORTED: return "XR_ERROR_FORM_FACTOR_UNSUPPORTED"; case XR_ERROR_FORM_FACTOR_UNAVAILABLE: return "XR_ERROR_FORM_FACTOR_UNAVAILABLE"; case XR_ERROR_API_LAYER_NOT_PRESENT: return "XR_ERROR_API_LAYER_NOT_PRESENT";
    case XR_ERROR_CALL_ORDER_INVALID: return "XR_ERROR_CALL_ORDER_INVALID"; case XR_ERROR_GRAPHICS_DEVICE_INVALID: return "XR_ERROR_GRAPHICS_DEVICE_INVALID"; case XR_ERROR_POSE_INVALID: return "XR_ERROR_POSE_INVALID";
    case XR_ERROR_ENVIRONMENT_BLEND_MODE_UNSUPPORTED: return "XR_ERROR_ENVIRONMENT_BLEND_MODE_UNSUPPORTED"; case XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED: return "XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED";
    case XR_ERROR_ACTIONSET_NOT_ATTACHED: return "XR_ERROR_ACTIONSET_NOT_ATTACHED"; case XR_ERROR_ACTIONSETS_ALREADY_ATTACHED: return "XR_ERROR_ACTIONSETS_ALREADY_ATTACHED"; case XR_ERROR_NAME_INVALID: return "XR_ERROR_NAME_INVALID";
    case XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING: return "XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING"; case XR_ERROR_RUNTIME_UNAVAILABLE: return "XR_ERROR_RUNTIME_UNAVAILABLE";
    default: { static thread_local char b[24]; snprintf(b, sizeof b, "result %d", (int)r); return b; }
    }
}
// A function the runtime does not have. The game gets something it can call (answering "unsupported") in place of
// nothing, and the call is written down: a null function pointer called by a game is a crash with no name on it.
const int STUBS = 320;
struct Stub { char name[72]; std::atomic<int> calls; } stubs[STUBS]; int stubCount = 0; std::atomic<int> unsupportedCalls{0};
volatile const char *lastCall = "none"; std::atomic<long> frameCount{0}, endFrameFails{0};
void stubCalled(int n) {
    int c = stubs[n].calls.fetch_add(1); unsupportedCalls++; lastCall = stubs[n].name;
    if (c == 0) diag("UNSUPPORTED CALL  %s: neither the runtime nor the layer has this function; the game was answered XR_ERROR_FUNCTION_UNSUPPORTED", stubs[n].name);
}
template <int N> XrResult XRAPI_CALL stubFn() { stubCalled(N); return XR_ERROR_FUNCTION_UNSUPPORTED; }
template <size_t... I> std::array<PFN_xrVoidFunction, sizeof...(I)> makeStubs(std::index_sequence<I...>) { return {{(PFN_xrVoidFunction)&stubFn<(int)I>...}}; }
const std::array<PFN_xrVoidFunction, STUBS> stubTable = makeStubs(std::make_index_sequence<STUBS>());
PFN_xrVoidFunction stubFor(const char *name) {      // lock not needed: the loader asks from one thread at a time
    static std::mutex m; std::lock_guard<std::mutex> g(m);
    for (int i = 0; i < stubCount; i++) if (strcmp(stubs[i].name, name) == 0) return stubTable[i];
    if (stubCount >= STUBS || strlen(name) >= sizeof stubs[0].name) return nullptr;
    strcpy(stubs[stubCount].name, name); return stubTable[stubCount++];
}
void diagSummary(const char *when) {
    std::string called; int kinds = 0;
    for (int i = 0; i < stubCount; i++) { int c = stubs[i].calls.load(); if (c) { kinds++; char b[112]; snprintf(b, sizeof b, " %s x%d", stubs[i].name, c); called += b; } }
    diag("SUMMARY (%s)  %ld frames, %ld failed xrEndFrame, %d unsupported functions called%s%s", when, frameCount.load(), endFrameFails.load(), kinds, kinds ? ":" : "", called.c_str());
}
// A crash: one line saying where the game was in OpenXR terms, then on to whoever handled the signal before us
// (Android's own crash dump, which has the stack).
const int CRASH_SIGNALS[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE};
struct sigaction oldAction[32]; std::atomic<bool> crashSeen{false}, crashHooked{false};
void onCrash(int sig, siginfo_t *info, void *ctx) {
    if (!crashSeen.exchange(true)) {
        char b[400]; int n = snprintf(b, sizeof b, "CRASH  signal %d (%s) at address %p in thread %d, after %ld frames; the last OpenXR call the layer saw: %s; unsupported calls so far: %d\n",
            sig, sig == SIGSEGV ? "SIGSEGV" : sig == SIGABRT ? "SIGABRT" : sig == SIGBUS ? "SIGBUS" : sig == SIGILL ? "SIGILL" : "SIGFPE", info ? info->si_addr : nullptr, (int)gettid(), frameCount.load(), (const char *)lastCall, unsupportedCalls.load());
        if (diagFd >= 0 && n > 0) { write(diagFd, "          ", 10); write(diagFd, b, (size_t)n); fsync(diagFd); }
        __android_log_write(ANDROID_LOG_ERROR, "UltraXRQuest", b);
    }
    struct sigaction &o = oldAction[sig];
    if ((o.sa_flags & SA_SIGINFO) && o.sa_sigaction) { o.sa_sigaction(sig, info, ctx); return; }
    if (!(o.sa_flags & SA_SIGINFO) && o.sa_handler != SIG_DFL && o.sa_handler != SIG_IGN) { o.sa_handler(sig); return; }
    signal(sig, SIG_DFL); if (sig == SIGABRT || !info || info->si_code <= 0) raise(sig);      // a fault happens again on return
}
void hookCrashes() {
    if (crashHooked.exchange(true)) return;
    for (int s : CRASH_SIGNALS) { struct sigaction a{}; a.sa_sigaction = onCrash; a.sa_flags = SA_SIGINFO | SA_ONSTACK; sigemptyset(&a.sa_mask); sigaction(s, &a, &oldAction[s]); }
}
std::string pathName(XrPath p) {
    char b[XR_MAX_PATH_LENGTH] = "?"; uint32_t n = 0; if (p == XR_NULL_PATH) return "(none)";
    if (rPathToString && instance) rPathToString(instance, p, sizeof b, &n, b); return b;
}
const char *stateName(XrSessionState s) {
    const char *n[] = {"UNKNOWN", "IDLE", "READY", "SYNCHRONIZED", "VISIBLE", "FOCUSED", "STOPPING", "LOSS_PENDING", "EXITING"}; return (int)s >= 0 && (int)s <= 8 ? n[(int)s] : "?";
}

// ---------------------------------------------------------------- small maths
struct V3 { float x, y, z; };
V3 rot(const XrQuaternionf &q, V3 v) {
    V3 u{q.x, q.y, q.z}; float s = q.w;
    V3 c1{u.y * v.z - u.z * v.y, u.z * v.x - u.x * v.z, u.x * v.y - u.y * v.x};
    V3 c2{u.y * c1.z - u.z * c1.y, u.z * c1.x - u.x * c1.z, u.x * c1.y - u.y * c1.x};
    return {v.x + 2 * (s * c1.x + c2.x), v.y + 2 * (s * c1.y + c2.y), v.z + 2 * (s * c1.z + c2.z)};
}
XrQuaternionf mul(const XrQuaternionf &a, const XrQuaternionf &b) {
    return {a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y, a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
            a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w, a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z};
}
XrPosef compose(const XrPosef &a, const XrPosef &b) {      // b given in a's frame
    V3 p = rot(a.orientation, {b.position.x, b.position.y, b.position.z});
    return {mul(a.orientation, b.orientation), {a.position.x + p.x, a.position.y + p.y, a.position.z + p.z}};
}
XrPosef inverse(const XrPosef &a) {
    XrQuaternionf q{-a.orientation.x, -a.orientation.y, -a.orientation.z, a.orientation.w};
    V3 p = rot(q, {-a.position.x, -a.position.y, -a.position.z});
    return {q, {p.x, p.y, p.z}};
}
// the orientation whose x, y, z axes are the three given unit vectors
XrQuaternionf fromAxes(V3 x, V3 y, V3 z) {
    float m00 = x.x, m01 = y.x, m02 = z.x, m10 = x.y, m11 = y.y, m12 = z.y, m20 = x.z, m21 = y.z, m22 = z.z, tr = m00 + m11 + m22;
    XrQuaternionf q;
    if (tr > 0) { float s = std::sqrt(tr + 1) * 2; q = {(m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s}; }
    else if (m00 > m11 && m00 > m22) { float s = std::sqrt(1 + m00 - m11 - m22) * 2; q = {0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s}; }
    else if (m11 > m22) { float s = std::sqrt(1 + m11 - m00 - m22) * 2; q = {(m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s}; }
    else { float s = std::sqrt(1 + m22 - m00 - m11) * 2; q = {(m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s}; }
    float l = std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w); return {q.x / l, q.y / l, q.z / l, q.w / l};
}
float yawOf(const XrQuaternionf &q) { V3 f = rot(q, {0, 0, -1}); return std::atan2(-f.x, -f.z); }

// ---------------------------------------------------------------- the room's entities
enum Kind { ROOM, FLOOR, CEILING, WALL, TABLE, ANCHOR, MESH };
struct Entity {
    Kind kind; XrUuidEXT uuid; XrPosef pose;      // pose in the room's frame (room.json's)
    float w = 0, h = 0;                           // a plane's size along its x and y
    bool locatable = false, storable = false, saved = false;
    int key = 0;                                  // which wall or table it was in the room data
    bool live = true;                             // false: a wall or table the room model no longer has; the handle stays good, queries leave it out
};
std::vector<Entity *> entities;                   // never freed while the instance lives: games keep the handles
bool isEntity(XrSpace s) { for (auto *e : entities) if ((XrSpace)(uintptr_t)e == s) return true; return false; }
Entity *entity(XrSpace s) { return isEntity(s) ? (Entity *)(uintptr_t)s : nullptr; }
XrUuidEXT makeUuid(Kind k, int n) {
    XrUuidEXT u{}; const char tag[] = "UltraXR-room";
    memcpy(u.data, tag, 12); u.data[12] = (uint8_t)k; u.data[13] = (uint8_t)(n >> 8); u.data[14] = (uint8_t)n; u.data[15] = 1; return u;
}
int anchorCount = 0;

// Saved anchors, kept between runs in the game's own data folder. There is no saved map of the room yet, so there is
// nothing to find a place by on the next run: an anchor comes back where it was relative to where the session
// started (the room's frame begins at the head's place and heading at the start). Start in the same spot facing the
// same way and it is where it was; start elsewhere and it is off by as much.
std::string anchorFile() {
    char name[256] = ""; FILE *f = fopen("/proc/self/cmdline", "r"); if (f) { fread(name, 1, sizeof name - 1, f); fclose(f); }
    for (char *c = name; *c; c++) if (*c == ':') { *c = 0; break; }      // a process name may be package:suffix
    return name[0] ? std::string("/data/data/") + name + "/uxr_anchors.txt" : std::string();
}
int anchorsRestored = 0;

void storeAnchors() {      // lock held
    std::string p = anchorFile(); if (p.empty()) return;
    FILE *f = fopen((p + ".new").c_str(), "w"); if (!f) return;
    for (auto *e : entities) {
        if (e->kind != ANCHOR || !e->saved) continue;
        fprintf(f, "%d ", e->key); for (int i = 0; i < 16; i++) fprintf(f, "%02x", e->uuid.data[i]);
        fprintf(f, " %.6f %.6f %.6f %.6f %.6f %.6f %.6f\n", e->pose.orientation.x, e->pose.orientation.y, e->pose.orientation.z, e->pose.orientation.w, e->pose.position.x, e->pose.position.y, e->pose.position.z);
    }
    fclose(f); rename((p + ".new").c_str(), p.c_str());
}
void loadAnchors() {       // lock held; once an instance
    for (auto *e : entities) if (e->kind == ANCHOR) return;
    std::string p = anchorFile(); FILE *f = p.empty() ? nullptr : fopen(p.c_str(), "r"); if (!f) return;
    int key; char hex[40]; float q[4], t[3];
    while (fscanf(f, "%d %32s %f %f %f %f %f %f %f", &key, hex, &q[0], &q[1], &q[2], &q[3], &t[0], &t[1], &t[2]) == 9) {
        if (strlen(hex) != 32) continue;
        auto *e = new Entity{ANCHOR, {}, {{q[0], q[1], q[2], q[3]}, {t[0], t[1], t[2]}}};
        for (int i = 0; i < 16; i++) { unsigned v = 0; sscanf(hex + 2 * i, "%2x", &v); e->uuid.data[i] = (uint8_t)v; }
        e->key = key; e->saved = true; e->storable = true; entities.push_back(e); anchorsRestored++;
        if (key > anchorCount) anchorCount = key;
    }
    fclose(f);
    LOGI("%d saved anchors read back (placed relative to where this session started)", anchorsRestored);
}

// room.json's frame seen from our local-floor space: a turn about the vertical and a shift, from the head, which is in both
float alignYaw = 0; V3 alignShift{0, 0, 0}; bool aligned = false;
XrPosef roomInRef(const XrPosef &p) {
    XrQuaternionf t{0, std::sin(alignYaw / 2), 0, std::cos(alignYaw / 2)};
    V3 r = rot(t, {p.position.x, p.position.y, p.position.z});
    return {mul(t, p.orientation), {r.x + alignShift.x, r.y + alignShift.y, r.z + alignShift.z}};
}
XrPosef refInRoom(const XrPosef &p) {
    XrQuaternionf t{0, -std::sin(alignYaw / 2), 0, std::cos(alignYaw / 2)};
    V3 r = rot(t, {p.position.x - alignShift.x, p.position.y - alignShift.y, p.position.z - alignShift.z});
    return {mul(t, p.orientation), {r.x, r.y, r.z}};
}

// ---------------------------------------------------------------- the app's room data
std::string httpGet(const char *path) {
    int fd = socket(AF_INET, SOCK_STREAM, 0); if (fd < 0) return "";
    timeval tv{1, 0}; setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv); setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    sockaddr_in a{}; a.sin_family = AF_INET; a.sin_port = htons(8765); inet_pton(AF_INET, "127.0.0.1", &a.sin_addr);
    std::string out;
    if (connect(fd, (sockaddr *)&a, sizeof a) == 0) {
        std::string req = std::string("GET ") + path + " HTTP/1.0\r\nHost: localhost\r\n\r\n";
        send(fd, req.data(), req.size(), 0);
        char buf[4096]; ssize_t n; while ((n = recv(fd, buf, sizeof buf, 0)) > 0) out.append(buf, n);
    }
    close(fd);
    size_t b = out.find("\r\n\r\n"); return b == std::string::npos ? "" : out.substr(b + 4);
}
bool numbers(const std::string &s, size_t from, const char *name, float *out, int n) {
    std::string k = std::string("\"") + name + "\":"; size_t i = s.find(k, from); if (i == std::string::npos) return false;
    i += k.size(); if (s[i] == '[') i++;
    const char *p = s.c_str() + i;
    for (int j = 0; j < n; j++) { char *e; out[j] = strtof(p, &e); if (e == p) return false; p = e; if (*p == ',') p++; }
    return true;
}
std::atomic<bool> running{false}; std::thread poller; int roomPackets = 0; bool roomTracked = false, floorMeasured = false;
struct HeadSample { XrPosef p; bool ok = false; } jsonHead;

void upsert(Kind kind, int key, const XrPosef &pose, float w, float h) {
    for (auto *e : entities) if (e->kind == kind && e->key == key) { e->pose = pose; e->w = w; e->h = h; e->live = true; return; }
    auto *e = new Entity{kind, makeUuid(kind, key), pose, w, h}; e->key = key; e->storable = true; e->saved = true; entities.push_back(e);
}
void readRoom() {
    std::string s = httpGet("/room.json");
    if (s.empty() || s.find("\"session\":true") == std::string::npos) return;
    std::lock_guard<std::mutex> g(lock);
    roomPackets++;
    roomTracked = s.find("\"tracked\":true") != std::string::npos;
    floorMeasured = s.find("\"measured\":true") != std::string::npos;
    float hp[3], hq[4];
    size_t hi = s.find("\"head\":");
    if (hi != std::string::npos && numbers(s, hi, "p", hp, 3) && numbers(s, hi, "q", hq, 4)) { jsonHead.p = {{hq[0], hq[1], hq[2], hq[3]}, {hp[0], hp[1], hp[2]}}; jsonHead.ok = true; }
    float fy = 0; size_t fi = s.find("\"floor\":"); if (fi != std::string::npos) numbers(s, fi, "y", &fy, 1);
    // the floor and (assumed 2.5 m above it) the ceiling: 8 m squares around the room's origin; x, y in the plane, z out of it
    upsert(FLOOR, 0, {fromAxes({1, 0, 0}, {0, 0, -1}, {0, 1, 0}), {0, fy, 0}}, 8, 8);
    upsert(CEILING, 0, {fromAxes({1, 0, 0}, {0, 0, 1}, {0, -1, 0}), {0, fy + 2.5f, 0}}, 8, 8);
    upsert(ROOM, 0, {{0, 0, 0, 1}, {0, fy, 0}}, 0, 0);
    upsert(MESH, 0, {{0, 0, 0, 1}, {0, fy, 0}}, 0, 0);
    for (auto *e : entities) if (e->kind == WALL || e->kind == TABLE) e->live = false;
    int walls = 0, tables = 0; size_t i = 0;
    while ((i = s.find("{\"kind\":\"", i)) != std::string::npos) {
        bool wall = s.compare(i + 9, 4, "wall") == 0, table = s.compare(i + 9, 5, "table") == 0;
        float c[3], u[3], v[3], n[3], hu, hv;
        if ((wall || table) && numbers(s, i, "c", c, 3) && numbers(s, i, "u", u, 3) && numbers(s, i, "v", v, 3) && numbers(s, i, "n", n, 3) && numbers(s, i, "hu", &hu, 1) && numbers(s, i, "hv", &hv, 1))
            upsert(wall ? WALL : TABLE, wall ? walls++ : tables++, {fromAxes({u[0], u[1], u[2]}, {v[0], v[1], v[2]}, {n[0], n[1], n[2]}), {c[0], c[1], c[2]}}, hu * 2, hv * 2);
        i += 9;
    }
}
void pollLoop() { while (running) { readRoom(); for (int i = 0; i < 5 && running; i++) usleep(100000); } }

// called with the lock held, once a frame: line the room's frame up with ours from the two head poses
void align() {
    if (!jsonHead.ok || roomRef == XR_NULL_HANDLE || lastTime == 0) return;
    XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
    if (XR_FAILED(rLocateSpace(viewRef, roomRef, lastTime, &loc)) || !(loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) return;
    float yaw = yawOf(loc.pose.orientation) - yawOf(jsonHead.p.orientation);
    XrQuaternionf t{0, std::sin(yaw / 2), 0, std::cos(yaw / 2)};
    V3 r = rot(t, {jsonHead.p.position.x, jsonHead.p.position.y, jsonHead.p.position.z});
    V3 shift{loc.pose.position.x - r.x, loc.pose.position.y - r.y, loc.pose.position.z - r.z};
    if (!aligned) { alignYaw = yaw; alignShift = shift; aligned = true; return; }
    // the two poses are from slightly different moments: follow slowly, jump only when far off (a recentre)
    float dy = yaw - alignYaw; while (dy > M_PI) dy -= 2 * M_PI; while (dy < -M_PI) dy += 2 * M_PI;
    float d = std::sqrt((shift.x - alignShift.x) * (shift.x - alignShift.x) + (shift.y - alignShift.y) * (shift.y - alignShift.y) + (shift.z - alignShift.z) * (shift.z - alignShift.z));
    float k = (std::fabs(dy) > 0.2f || d > 0.4f) ? 1.0f : 0.02f;
    alignYaw += dy * k; alignShift = {alignShift.x + (shift.x - alignShift.x) * k, alignShift.y + (shift.y - alignShift.y) * k, alignShift.z + (shift.z - alignShift.z) * k};
}

// ---------------------------------------------------------------- events we owe the game
std::deque<std::vector<uint8_t>> events;
template <class T> void push(const T &e) { std::vector<uint8_t> b(sizeof(T)); memcpy(b.data(), &e, sizeof(T)); events.push_back(b); }
XrAsyncRequestIdFB nextRequest = 1000;
struct Query { XrAsyncRequestIdFB id; std::vector<Entity *> results; };
std::vector<Query> queries;

bool has(Entity *e, XrSpaceComponentTypeFB c) {
    switch (c) {
        case XR_SPACE_COMPONENT_TYPE_LOCATABLE_FB: return e->kind != ROOM;
        case XR_SPACE_COMPONENT_TYPE_STORABLE_FB: return true;
        case XR_SPACE_COMPONENT_TYPE_BOUNDED_2D_FB: return e->kind == FLOOR || e->kind == CEILING || e->kind == WALL || e->kind == TABLE;
        case XR_SPACE_COMPONENT_TYPE_SEMANTIC_LABELS_FB: return e->kind != ROOM && e->kind != ANCHOR;
        case XR_SPACE_COMPONENT_TYPE_ROOM_LAYOUT_FB: case XR_SPACE_COMPONENT_TYPE_SPACE_CONTAINER_FB: return e->kind == ROOM;
        case XR_SPACE_COMPONENT_TYPE_TRIANGLE_MESH_META: return e->kind == MESH;
        default: return false;
    }
}
bool enabled(Entity *e, XrSpaceComponentTypeFB c) {
    if (!has(e, c)) return false;
    if (c == XR_SPACE_COMPONENT_TYPE_LOCATABLE_FB) return e->locatable;
    if (c == XR_SPACE_COMPONENT_TYPE_STORABLE_FB) return e->storable;
    return true;      // what the room model gives is always there
}
const char *label(Kind k) { return k == FLOOR ? "FLOOR" : k == CEILING ? "CEILING" : k == WALL ? "WALL_FACE" : k == TABLE ? "TABLE" : k == MESH ? "GLOBAL_MESH" : ""; }

// ---------------------------------------------------------------- passthrough
struct Pass { bool running = false; }; struct PassLayer { bool running = true; };
std::vector<Pass *> passes; std::vector<PassLayer *> passLayers;
int passthroughLayersDropped = 0;

XrResult XRAPI_CALL uCreatePassthroughFB(XrSession, const XrPassthroughCreateInfoFB *info, XrPassthroughFB *out) {
    auto *p = new Pass; p->running = info && (info->flags & XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB); passes.push_back(p); *out = (XrPassthroughFB)(uintptr_t)p; return XR_SUCCESS;
}
XrResult XRAPI_CALL uDestroyPassthroughFB(XrPassthroughFB) { return XR_SUCCESS; }
XrResult XRAPI_CALL uPassthroughStartFB(XrPassthroughFB p) { ((Pass *)(uintptr_t)p)->running = true; return XR_SUCCESS; }
XrResult XRAPI_CALL uPassthroughPauseFB(XrPassthroughFB p) { ((Pass *)(uintptr_t)p)->running = false; return XR_SUCCESS; }
XrResult XRAPI_CALL uCreatePassthroughLayerFB(XrSession, const XrPassthroughLayerCreateInfoFB *, XrPassthroughLayerFB *out) {
    auto *l = new PassLayer; passLayers.push_back(l); *out = (XrPassthroughLayerFB)(uintptr_t)l; return XR_SUCCESS;
}
XrResult XRAPI_CALL uDestroyPassthroughLayerFB(XrPassthroughLayerFB) { return XR_SUCCESS; }
XrResult XRAPI_CALL uPassthroughLayerPauseFB(XrPassthroughLayerFB) { return XR_SUCCESS; }
XrResult XRAPI_CALL uPassthroughLayerResumeFB(XrPassthroughLayerFB) { return XR_SUCCESS; }
XrResult XRAPI_CALL uPassthroughLayerSetStyleFB(XrPassthroughLayerFB, const XrPassthroughStyleFB *) { return XR_SUCCESS; }
// surface-projected passthrough (a picture of the world on a mesh) has no meaning on see-through glasses
XrResult XRAPI_CALL uCreateGeometryInstanceFB(XrSession, const XrGeometryInstanceCreateInfoFB *, XrGeometryInstanceFB *) {
    ONCE("UNSUPPORTED FEATURE  xrCreateGeometryInstanceFB (passthrough shown on a game's own surface): answered XR_ERROR_FEATURE_UNSUPPORTED; the real world is seen everywhere the game draws nothing");
    return XR_ERROR_FEATURE_UNSUPPORTED;
}
XrResult XRAPI_CALL uDestroyGeometryInstanceFB(XrGeometryInstanceFB) { return XR_SUCCESS; }
XrResult XRAPI_CALL uGeometryInstanceSetTransformFB(XrGeometryInstanceFB, const XrGeometryInstanceTransformFB *) { return XR_ERROR_FEATURE_UNSUPPORTED; }

// ---------------------------------------------------------------- spatial entities, scene, anchors
XrResult XRAPI_CALL uCreateSpatialAnchorFB(XrSession, const XrSpatialAnchorCreateInfoFB *info, XrAsyncRequestIdFB *req) {
    if (!info || !req) return XR_ERROR_VALIDATION_FAILURE;
    std::lock_guard<std::mutex> g(lock);
    // where the given space is in ours now, then the anchor in the room's frame, where it stays
    XrPosef base{{0, 0, 0, 1}, {0, 0, 0}};
    if (Entity *b = entity(info->space)) base = roomInRef(b->pose);
    else {
        XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
        XrResult r = rLocateSpace(info->space, roomRef, info->time ? info->time : lastTime, &loc);
        if (XR_FAILED(r)) return r;
        if (!(loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT)) return XR_ERROR_SPACE_COMPONENT_NOT_ENABLED_FB;
        base = loc.pose;
    }
    auto *e = new Entity{ANCHOR, makeUuid(ANCHOR, ++anchorCount), refInRoom(compose(base, info->poseInSpace))};
    e->key = anchorCount; e->locatable = true; entities.push_back(e);
    *req = nextRequest++;
    XrEventDataSpatialAnchorCreateCompleteFB ev{XR_TYPE_EVENT_DATA_SPATIAL_ANCHOR_CREATE_COMPLETE_FB}; ev.requestId = *req; ev.result = XR_SUCCESS; ev.space = (XrSpace)(uintptr_t)e; ev.uuid = e->uuid; push(ev);
    return XR_SUCCESS;
}
XrResult XRAPI_CALL uGetSpaceUuidFB(XrSpace s, XrUuidEXT *u) { Entity *e = entity(s); if (!e) return XR_ERROR_HANDLE_INVALID; *u = e->uuid; return XR_SUCCESS; }
XrResult XRAPI_CALL uEnumerateSpaceSupportedComponentsFB(XrSpace s, uint32_t cap, uint32_t *count, XrSpaceComponentTypeFB *out) {
    Entity *e = entity(s); if (!e) return XR_ERROR_HANDLE_INVALID;
    const XrSpaceComponentTypeFB all[] = {XR_SPACE_COMPONENT_TYPE_LOCATABLE_FB, XR_SPACE_COMPONENT_TYPE_STORABLE_FB, XR_SPACE_COMPONENT_TYPE_BOUNDED_2D_FB,
                                          XR_SPACE_COMPONENT_TYPE_SEMANTIC_LABELS_FB, XR_SPACE_COMPONENT_TYPE_ROOM_LAYOUT_FB, XR_SPACE_COMPONENT_TYPE_SPACE_CONTAINER_FB,
                                          XR_SPACE_COMPONENT_TYPE_TRIANGLE_MESH_META};
    uint32_t n = 0; for (auto c : all) if (has(e, c)) { if (out && n < cap) out[n] = c; n++; }
    *count = n; return (out && cap < n) ? XR_ERROR_SIZE_INSUFFICIENT : XR_SUCCESS;
}
XrResult XRAPI_CALL uSetSpaceComponentStatusFB(XrSpace s, const XrSpaceComponentStatusSetInfoFB *info, XrAsyncRequestIdFB *req) {
    Entity *e = entity(s); if (!e) return XR_ERROR_HANDLE_INVALID;
    if (!has(e, info->componentType)) return XR_ERROR_SPACE_COMPONENT_NOT_SUPPORTED_FB;
    std::lock_guard<std::mutex> g(lock);
    if (enabled(e, info->componentType) == (bool)info->enabled) return XR_ERROR_SPACE_COMPONENT_STATUS_ALREADY_SET_FB;
    if (info->componentType == XR_SPACE_COMPONENT_TYPE_LOCATABLE_FB) e->locatable = info->enabled;
    if (info->componentType == XR_SPACE_COMPONENT_TYPE_STORABLE_FB) e->storable = info->enabled;
    *req = nextRequest++;
    XrEventDataSpaceSetStatusCompleteFB ev{XR_TYPE_EVENT_DATA_SPACE_SET_STATUS_COMPLETE_FB}; ev.requestId = *req; ev.result = XR_SUCCESS; ev.space = s; ev.uuid = e->uuid; ev.componentType = info->componentType; ev.enabled = info->enabled; push(ev);
    return XR_SUCCESS;
}
XrResult XRAPI_CALL uGetSpaceComponentStatusFB(XrSpace s, XrSpaceComponentTypeFB c, XrSpaceComponentStatusFB *st) {
    Entity *e = entity(s); if (!e) return XR_ERROR_HANDLE_INVALID;
    if (!has(e, c)) return XR_ERROR_SPACE_COMPONENT_NOT_SUPPORTED_FB;
    st->enabled = enabled(e, c); st->changePending = XR_FALSE; return XR_SUCCESS;
}
XrResult XRAPI_CALL uQuerySpacesFB(XrSession, const XrSpaceQueryInfoBaseHeaderFB *info, XrAsyncRequestIdFB *req) {
    if (!info || !req) return XR_ERROR_VALIDATION_FAILURE;
    std::lock_guard<std::mutex> g(lock);
    Query q; q.id = nextRequest++;
    const XrSpaceQueryInfoFB *qi = info->type == XR_TYPE_SPACE_QUERY_INFO_FB ? (const XrSpaceQueryInfoFB *)info : nullptr;
    const XrSpaceFilterInfoBaseHeaderFB *f = qi ? qi->filter : nullptr;
    for (auto *e : entities) {
        if ((e->kind == ANCHOR && !e->saved) || !e->live) continue;         // a query finds what is stored, and what is still there
        bool ok = true;
        if (f && f->type == XR_TYPE_SPACE_COMPONENT_FILTER_INFO_FB) ok = has(e, ((const XrSpaceComponentFilterInfoFB *)f)->componentType);
        else if (f && f->type == XR_TYPE_SPACE_UUID_FILTER_INFO_FB) {
            auto *uf = (const XrSpaceUuidFilterInfoFB *)f; ok = false;
            for (uint32_t i = 0; i < uf->uuidCount; i++) if (memcmp(&uf->uuids[i], &e->uuid, sizeof(XrUuidEXT)) == 0) ok = true;
        }
        if (ok && (!qi || qi->maxResultCount == 0 || q.results.size() < qi->maxResultCount)) q.results.push_back(e);
    }
    *req = q.id; queries.push_back(q);
    XrEventDataSpaceQueryResultsAvailableFB a{XR_TYPE_EVENT_DATA_SPACE_QUERY_RESULTS_AVAILABLE_FB}; a.requestId = q.id; push(a);
    XrEventDataSpaceQueryCompleteFB c{XR_TYPE_EVENT_DATA_SPACE_QUERY_COMPLETE_FB}; c.requestId = q.id; c.result = XR_SUCCESS; push(c);
    return XR_SUCCESS;
}
XrResult XRAPI_CALL uRetrieveSpaceQueryResultsFB(XrSession, XrAsyncRequestIdFB id, XrSpaceQueryResultsFB *out) {
    std::lock_guard<std::mutex> g(lock);
    for (auto &q : queries) if (q.id == id) {
        out->resultCountOutput = (uint32_t)q.results.size();
        if (out->resultCapacityInput == 0) return XR_SUCCESS;
        if (out->resultCapacityInput < q.results.size()) return XR_ERROR_SIZE_INSUFFICIENT;
        for (size_t i = 0; i < q.results.size(); i++) { out->results[i].space = (XrSpace)(uintptr_t)q.results[i]; out->results[i].uuid = q.results[i]->uuid; }
        return XR_SUCCESS;
    }
    return XR_ERROR_VALIDATION_FAILURE;
}
XrResult XRAPI_CALL uSaveSpaceFB(XrSession, const XrSpaceSaveInfoFB *info, XrAsyncRequestIdFB *req) {
    Entity *e = entity(info->space); if (!e) return XR_ERROR_HANDLE_INVALID;
    std::lock_guard<std::mutex> g(lock);
    e->saved = true; *req = nextRequest++; if (e->kind == ANCHOR) storeAnchors();
    XrEventDataSpaceSaveCompleteFB ev{XR_TYPE_EVENT_DATA_SPACE_SAVE_COMPLETE_FB}; ev.requestId = *req; ev.result = XR_SUCCESS; ev.space = info->space; ev.uuid = e->uuid; ev.location = info->location; push(ev);
    return XR_SUCCESS;
}
XrResult XRAPI_CALL uEraseSpaceFB(XrSession, const XrSpaceEraseInfoFB *info, XrAsyncRequestIdFB *req) {
    Entity *e = entity(info->space); if (!e) return XR_ERROR_HANDLE_INVALID;
    std::lock_guard<std::mutex> g(lock);
    e->saved = false; *req = nextRequest++; if (e->kind == ANCHOR) storeAnchors();
    XrEventDataSpaceEraseCompleteFB ev{XR_TYPE_EVENT_DATA_SPACE_ERASE_COMPLETE_FB}; ev.requestId = *req; ev.result = XR_SUCCESS; ev.space = info->space; ev.uuid = e->uuid; ev.location = info->location; push(ev);
    return XR_SUCCESS;
}
XrResult XRAPI_CALL uGetSpaceContainerFB(XrSession, XrSpace s, XrSpaceContainerFB *out) {
    Entity *e = entity(s); if (!e || e->kind != ROOM) return XR_ERROR_SPACE_COMPONENT_NOT_ENABLED_FB;
    std::lock_guard<std::mutex> g(lock);
    uint32_t n = 0; for (auto *x : entities) if (x->kind != ROOM && x->kind != ANCHOR && x->live) { if (out->uuids && n < out->uuidCapacityInput) out->uuids[n] = x->uuid; n++; }
    out->uuidCountOutput = n; return (out->uuidCapacityInput && out->uuidCapacityInput < n) ? XR_ERROR_SIZE_INSUFFICIENT : XR_SUCCESS;
}
XrResult XRAPI_CALL uGetSpaceBoundingBox2DFB(XrSession, XrSpace s, XrRect2Df *out) {
    Entity *e = entity(s); if (!e || !has(e, XR_SPACE_COMPONENT_TYPE_BOUNDED_2D_FB)) return XR_ERROR_SPACE_COMPONENT_NOT_ENABLED_FB;
    *out = {{-e->w / 2, -e->h / 2}, {e->w, e->h}}; return XR_SUCCESS;
}
XrResult XRAPI_CALL uGetSpaceBoundingBox3DFB(XrSession, XrSpace, XrRect3DfFB *) { return XR_ERROR_SPACE_COMPONENT_NOT_ENABLED_FB; }
XrResult XRAPI_CALL uGetSpaceBoundary2DFB(XrSession, XrSpace s, XrBoundary2DFB *out) {
    Entity *e = entity(s); if (!e || !has(e, XR_SPACE_COMPONENT_TYPE_BOUNDED_2D_FB)) return XR_ERROR_SPACE_COMPONENT_NOT_ENABLED_FB;
    out->vertexCountOutput = 4; if (out->vertexCapacityInput == 0) return XR_SUCCESS; if (out->vertexCapacityInput < 4) return XR_ERROR_SIZE_INSUFFICIENT;
    float x = e->w / 2, y = e->h / 2; out->vertices[0] = {-x, -y}; out->vertices[1] = {x, -y}; out->vertices[2] = {x, y}; out->vertices[3] = {-x, y}; return XR_SUCCESS;
}
XrResult XRAPI_CALL uGetSpaceSemanticLabelsFB(XrSession, XrSpace s, XrSemanticLabelsFB *out) {
    Entity *e = entity(s); if (!e || !has(e, XR_SPACE_COMPONENT_TYPE_SEMANTIC_LABELS_FB)) return XR_ERROR_SPACE_COMPONENT_NOT_ENABLED_FB;
    const char *l = label(e->kind); uint32_t n = (uint32_t)strlen(l); out->bufferCountOutput = n;
    if (out->bufferCapacityInput == 0) return XR_SUCCESS; if (out->bufferCapacityInput < n) return XR_ERROR_SIZE_INSUFFICIENT;
    memcpy(out->buffer, l, n); return XR_SUCCESS;
}
XrResult XRAPI_CALL uGetSpaceRoomLayoutFB(XrSession, XrSpace s, XrRoomLayoutFB *out) {
    Entity *e = entity(s); if (!e || e->kind != ROOM) return XR_ERROR_SPACE_COMPONENT_NOT_ENABLED_FB;
    std::lock_guard<std::mutex> g(lock);
    uint32_t n = 0;
    for (auto *x : entities) {
        if (x->kind == FLOOR) out->floorUuid = x->uuid; else if (x->kind == CEILING) out->ceilingUuid = x->uuid;
        else if (x->kind == WALL && x->live) { if (out->wallUuids && n < out->wallUuidCapacityInput) out->wallUuids[n] = x->uuid; n++; }
    }
    out->wallUuidCountOutput = n; return (out->wallUuidCapacityInput && out->wallUuidCapacityInput < n) ? XR_ERROR_SIZE_INSUFFICIENT : XR_SUCCESS;
}

// ---------------------------------------------------------------- the room as one mesh
// The planes above as triangles, in the mesh entity's own frame (the room's origin at floor height). Each face is two
// triangles, counter-clockwise seen from the side the plane faces (into the room).
std::vector<XrVector3f> meshV; std::vector<uint32_t> meshI;
void buildMesh(Entity *m) {      // lock held
    meshV.clear(); meshI.clear(); XrPosef inv = inverse(m->pose);
    for (auto *e : entities) {
        if ((e->kind != FLOOR && e->kind != CEILING && e->kind != WALL && e->kind != TABLE) || !e->live) continue;
        float x = e->w / 2, y = e->h / 2; const float k[4][2] = {{-x, -y}, {x, -y}, {x, y}, {-x, y}};
        uint32_t b = (uint32_t)meshV.size();
        for (auto &q : k) meshV.push_back(compose(inv, compose(e->pose, {{0, 0, 0, 1}, {q[0], q[1], 0}})).position);
        for (uint32_t i : {0u, 1u, 2u, 0u, 2u, 3u}) meshI.push_back(b + i);
    }
}
XrResult XRAPI_CALL uGetSpaceTriangleMeshMETA(XrSpace s, const XrSpaceTriangleMeshGetInfoMETA *, XrSpaceTriangleMeshMETA *out) {
    Entity *e = entity(s); if (!e || e->kind != MESH || !out) return XR_ERROR_SPACE_COMPONENT_NOT_ENABLED_FB;
    std::lock_guard<std::mutex> g(lock);
    bool sizing = out->vertexCapacityInput == 0 || out->indexCapacityInput == 0;
    if (sizing || meshV.empty()) buildMesh(e);      // built when the sizes are asked, so the second call matches the first
    out->vertexCountOutput = (uint32_t)meshV.size(); out->indexCountOutput = (uint32_t)meshI.size();
    if (sizing) return XR_SUCCESS;
    if (out->vertexCapacityInput < meshV.size() || out->indexCapacityInput < meshI.size()) return XR_ERROR_SIZE_INSUFFICIENT;
    if (out->vertices) memcpy(out->vertices, meshV.data(), meshV.size() * sizeof(XrVector3f));
    if (out->indices) memcpy(out->indices, meshI.data(), meshI.size() * sizeof(uint32_t));
    return XR_SUCCESS;
}

// ---------------------------------------------------------------- scene capture, sharing, boundary
int sceneCaptures = 0;
XrResult XRAPI_CALL uRequestSceneCaptureFB(XrSession, const XrSceneCaptureRequestInfoFB *, XrAsyncRequestIdFB *req) {
    if (!req) return XR_ERROR_VALIDATION_FAILURE;
    std::lock_guard<std::mutex> g(lock); *req = nextRequest++; sceneCaptures++;
    XrEventDataSceneCaptureCompleteFB ev{XR_TYPE_EVENT_DATA_SCENE_CAPTURE_COMPLETE_FB}; ev.requestId = *req; ev.result = XR_SUCCESS; push(ev);
    return XR_SUCCESS;
}
struct SpaceUser { XrSpaceUserIdFB id; };
XrResult XRAPI_CALL uCreateSpaceUserFB(XrSession, const XrSpaceUserCreateInfoFB *info, XrSpaceUserFB *out) {
    if (!info || !out) return XR_ERROR_VALIDATION_FAILURE;
    *out = (XrSpaceUserFB)(uintptr_t) new SpaceUser{info->userId}; return XR_SUCCESS;
}
XrResult XRAPI_CALL uGetSpaceUserIdFB(XrSpaceUserFB u, XrSpaceUserIdFB *id) { if (!u || !id) return XR_ERROR_HANDLE_INVALID; *id = ((SpaceUser *)(uintptr_t)u)->id; return XR_SUCCESS; }
XrResult XRAPI_CALL uDestroySpaceUserFB(XrSpaceUserFB u) { delete (SpaceUser *)(uintptr_t)u; return XR_SUCCESS; }
XrResult XRAPI_CALL uShareSpacesFB(XrSession, const XrSpaceShareInfoFB *, XrAsyncRequestIdFB *req) {
    if (!req) return XR_ERROR_VALIDATION_FAILURE;
    std::lock_guard<std::mutex> g(lock); *req = nextRequest++;
    XrEventDataSpaceShareCompleteFB ev{XR_TYPE_EVENT_DATA_SPACE_SHARE_COMPLETE_FB}; ev.requestId = *req; ev.result = XR_ERROR_SPACE_CLOUD_STORAGE_DISABLED_FB; push(ev);
    return XR_SUCCESS;
}
XrBoundaryVisibilityMETA boundaryNow = XR_BOUNDARY_VISIBILITY_NOT_SUPPRESSED_META;
XrResult XRAPI_CALL uRequestBoundaryVisibilityMETA(XrSession, XrBoundaryVisibilityMETA v) {
    if (v != XR_BOUNDARY_VISIBILITY_NOT_SUPPRESSED_META && v != XR_BOUNDARY_VISIBILITY_SUPPRESSED_META) return XR_ERROR_VALIDATION_FAILURE;
    std::lock_guard<std::mutex> g(lock);
    if (v != boundaryNow) { boundaryNow = v; XrEventDataBoundaryVisibilityChangedMETA ev{XR_TYPE_EVENT_DATA_BOUNDARY_VISIBILITY_CHANGED_META}; ev.boundaryVisibility = v; push(ev); }
    return XR_SUCCESS;
}

// ---------------------------------------------------------------- environment depth
// One square depth picture an eye, in a two-layer texture as Meta's runtime gives it: 16-bit, 0 at the near plane
// (0.1 m), 1 infinitely far, so a surface z metres ahead is stored as 1 - 0.1 / z. Each eye's picture is what that
// eye sees of the scene's planes at the asked time; where there is no plane it says "far".
const int DEPTH_N = 160; const float DEPTH_NEAR = 0.1f;
struct DepthProvider { bool running = false; GLuint tex = 0; };
std::vector<uint16_t> depthPixels(DEPTH_N * DEPTH_N);
float depthProbe[2] = {0, 0}; int depthImages = 0;      // for the test: metres at the picture's centre and near its lower edge, left eye
struct PlaneInEye { V3 c, u, v, n; float hu, hv; };

XrResult XRAPI_CALL uCreateEnvironmentDepthProviderMETA(XrSession, const XrEnvironmentDepthProviderCreateInfoMETA *, XrEnvironmentDepthProviderMETA *out) {
    if (!out) return XR_ERROR_VALIDATION_FAILURE;
    if (!sessionGles) {      // the texture is made with OpenGL ES, in the application's context
        ONCE("UNSUPPORTED FEATURE  xrCreateEnvironmentDepthProviderMETA in a Vulkan session: answered XR_ERROR_FEATURE_UNSUPPORTED (environment depth is made for OpenGL ES sessions only); the game gets no occlusion by the room");
        return XR_ERROR_FEATURE_UNSUPPORTED;
    }
    diag("ok  xrCreateEnvironmentDepthProviderMETA (depth from the room's planes)");
    *out = (XrEnvironmentDepthProviderMETA)(uintptr_t) new DepthProvider; return XR_SUCCESS;
}
void dropDepthTexture(DepthProvider *p) { if (p->tex && eglGetCurrentContext() != EGL_NO_CONTEXT) glDeleteTextures(1, &p->tex); p->tex = 0; }
XrResult XRAPI_CALL uDestroyEnvironmentDepthProviderMETA(XrEnvironmentDepthProviderMETA h) { auto *p = (DepthProvider *)(uintptr_t)h; if (!p) return XR_ERROR_HANDLE_INVALID; dropDepthTexture(p); delete p; return XR_SUCCESS; }
XrResult XRAPI_CALL uStartEnvironmentDepthProviderMETA(XrEnvironmentDepthProviderMETA h) { if (!h) return XR_ERROR_HANDLE_INVALID; ((DepthProvider *)(uintptr_t)h)->running = true; return XR_SUCCESS; }
XrResult XRAPI_CALL uStopEnvironmentDepthProviderMETA(XrEnvironmentDepthProviderMETA h) { if (!h) return XR_ERROR_HANDLE_INVALID; ((DepthProvider *)(uintptr_t)h)->running = false; return XR_SUCCESS; }
XrResult XRAPI_CALL uCreateEnvironmentDepthSwapchainMETA(XrEnvironmentDepthProviderMETA h, const XrEnvironmentDepthSwapchainCreateInfoMETA *, XrEnvironmentDepthSwapchainMETA *out) {
    auto *p = (DepthProvider *)(uintptr_t)h; if (!p || !out) return XR_ERROR_HANDLE_INVALID;
    if (eglGetCurrentContext() == EGL_NO_CONTEXT) return XR_ERROR_RUNTIME_FAILURE;      // must be called where the application draws
    if (!p->tex) {
        GLint was = 0; glGetIntegerv(GL_TEXTURE_BINDING_2D_ARRAY, &was);
        glGenTextures(1, &p->tex); glBindTexture(GL_TEXTURE_2D_ARRAY, p->tex);
        glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_DEPTH_COMPONENT16, DEPTH_N, DEPTH_N, 2);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D_ARRAY, (GLuint)was);
    }
    *out = (XrEnvironmentDepthSwapchainMETA)(uintptr_t)p; return XR_SUCCESS;
}
XrResult XRAPI_CALL uDestroyEnvironmentDepthSwapchainMETA(XrEnvironmentDepthSwapchainMETA h) { auto *p = (DepthProvider *)(uintptr_t)h; if (!p) return XR_ERROR_HANDLE_INVALID; dropDepthTexture(p); return XR_SUCCESS; }
XrResult XRAPI_CALL uEnumerateEnvironmentDepthSwapchainImagesMETA(XrEnvironmentDepthSwapchainMETA h, uint32_t cap, uint32_t *count, XrSwapchainImageBaseHeader *images) {
    auto *p = (DepthProvider *)(uintptr_t)h; if (!p || !count) return XR_ERROR_HANDLE_INVALID;
    *count = 1; if (cap == 0) return XR_SUCCESS;
    if (!images || images->type != XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR) return XR_ERROR_VALIDATION_FAILURE;
    ((XrSwapchainImageOpenGLESKHR *)images)->image = p->tex; return XR_SUCCESS;
}
XrResult XRAPI_CALL uGetEnvironmentDepthSwapchainStateMETA(XrEnvironmentDepthSwapchainMETA h, XrEnvironmentDepthSwapchainStateMETA *st) { if (!h || !st) return XR_ERROR_HANDLE_INVALID; st->width = DEPTH_N; st->height = DEPTH_N; return XR_SUCCESS; }
XrResult XRAPI_CALL uSetEnvironmentDepthHandRemovalMETA(XrEnvironmentDepthProviderMETA, const XrEnvironmentDepthHandRemovalSetInfoMETA *) { ONCE("UNSUPPORTED FEATURE  xrSetEnvironmentDepthHandRemovalMETA: answered XR_ERROR_FEATURE_UNSUPPORTED (the depth picture has no hands in it)"); return XR_ERROR_FEATURE_UNSUPPORTED; }      // there are no hands in it to remove
XrResult XRAPI_CALL uAcquireEnvironmentDepthImageMETA(XrEnvironmentDepthProviderMETA h, const XrEnvironmentDepthImageAcquireInfoMETA *info, XrEnvironmentDepthImageMETA *image) {
    auto *p = (DepthProvider *)(uintptr_t)h; if (!p || !info || !image) return XR_ERROR_VALIDATION_FAILURE;
    if (!p->running || !p->tex || !rLocateViews || eglGetCurrentContext() == EGL_NO_CONTEXT) return XR_ENVIRONMENT_DEPTH_NOT_AVAILABLE_META;
    XrViewLocateInfo vi{XR_TYPE_VIEW_LOCATE_INFO}; vi.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO; vi.displayTime = info->displayTime; vi.space = info->space;
    XrViewState vs{XR_TYPE_VIEW_STATE}; XrView views[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}}; uint32_t nv = 0;
    if (XR_FAILED(rLocateViews(session, &vi, &vs, 2, &nv, views)) || nv < 2 || !(vs.viewStateFlags & XR_VIEW_STATE_POSITION_VALID_BIT)) return XR_ENVIRONMENT_DEPTH_NOT_AVAILABLE_META;
    XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
    if (XR_FAILED(rLocateSpace(roomRef, info->space, info->displayTime, &loc)) || !(loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT)) return XR_ENVIRONMENT_DEPTH_NOT_AVAILABLE_META;
    std::vector<std::pair<XrPosef, std::pair<float, float>>> planes;
    {
        std::lock_guard<std::mutex> g(lock);
        if (!aligned) return XR_ENVIRONMENT_DEPTH_NOT_AVAILABLE_META;
        for (auto *e : entities) if (e->live && (e->kind == FLOOR || e->kind == CEILING || e->kind == WALL || e->kind == TABLE)) {
            // the floor and the ceiling do not end where the scene's 8 m squares do: for hiding things they go on
            bool wide = e->kind == FLOOR || e->kind == CEILING;
            planes.push_back({compose(loc.pose, roomInRef(e->pose)), {wide ? 30.f : e->w / 2, wide ? 30.f : e->h / 2}});
        }
    }
    if (planes.empty()) return XR_ENVIRONMENT_DEPTH_NOT_AVAILABLE_META;
    GLint wasTex = 0, wasPbo = 0, wasAlign = 4, wasRow = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D_ARRAY, &wasTex); glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &wasPbo); glGetIntegerv(GL_UNPACK_ALIGNMENT, &wasAlign); glGetIntegerv(GL_UNPACK_ROW_LENGTH, &wasRow);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0); glPixelStorei(GL_UNPACK_ALIGNMENT, 2); glPixelStorei(GL_UNPACK_ROW_LENGTH, 0); glBindTexture(GL_TEXTURE_2D_ARRAY, p->tex);
    for (int eye = 0; eye < 2; eye++) {
        XrPosef inv = inverse(views[eye].pose); std::vector<PlaneInEye> pe; pe.reserve(planes.size());
        for (auto &pl : planes) { XrPosef q = compose(inv, pl.first); pe.push_back({{q.position.x, q.position.y, q.position.z}, rot(q.orientation, {1, 0, 0}), rot(q.orientation, {0, 1, 0}), rot(q.orientation, {0, 0, 1}), pl.second.first, pl.second.second}); }
        float tl = std::tan(views[eye].fov.angleLeft), tr = std::tan(views[eye].fov.angleRight), td = std::tan(views[eye].fov.angleDown), tu = std::tan(views[eye].fov.angleUp);
        for (int y = 0; y < DEPTH_N; y++) {      // row 0 is the bottom of the picture
            float dy = td + (tu - td) * (y + 0.5f) / DEPTH_N;
            for (int x = 0; x < DEPTH_N; x++) {
                float dx = tl + (tr - tl) * (x + 0.5f) / DEPTH_N, best = 1e9f;      // the ray (dx, dy, -1): t is then the distance ahead
                for (auto &q : pe) {
                    float den = q.n.x * dx + q.n.y * dy - q.n.z; if (std::fabs(den) < 1e-6f) continue;
                    float t = (q.n.x * q.c.x + q.n.y * q.c.y + q.n.z * q.c.z) / den; if (t <= DEPTH_NEAR || t >= best) continue;
                    float hx = t * dx - q.c.x, hy = t * dy - q.c.y, hz = -t - q.c.z;
                    if (std::fabs(hx * q.u.x + hy * q.u.y + hz * q.u.z) > q.hu || std::fabs(hx * q.v.x + hy * q.v.y + hz * q.v.z) > q.hv) continue;
                    best = t;
                }
                depthPixels[y * DEPTH_N + x] = best > 1e8f ? 65535 : (uint16_t)(65535.0f * (1.0f - DEPTH_NEAR / best) + 0.5f);
                if (eye == 0 && x == DEPTH_N / 2 && (y == DEPTH_N / 2 || y == DEPTH_N / 16)) depthProbe[y == DEPTH_N / 2 ? 0 : 1] = best > 1e8f ? 0 : best;
            }
        }
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, eye, DEPTH_N, DEPTH_N, 1, GL_DEPTH_COMPONENT, GL_UNSIGNED_SHORT, depthPixels.data());
        image->views[eye].fov = views[eye].fov; image->views[eye].pose = views[eye].pose;
    }
    glBindTexture(GL_TEXTURE_2D_ARRAY, (GLuint)wasTex); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, (GLuint)wasPbo); glPixelStorei(GL_UNPACK_ALIGNMENT, wasAlign); glPixelStorei(GL_UNPACK_ROW_LENGTH, wasRow);
    image->swapchainIndex = 0; image->nearZ = DEPTH_NEAR; image->farZ = INFINITY; depthImages++;
    return XR_SUCCESS;
}

// ---------------------------------------------------------------- a hand to draw
// Quest gives games a skinned hand model. There is none here, so one is made: an open flat hand, fingers along -z, the
// back of the hand up, the wrist at the origin; a slab for the palm and a six-sided tube along each finger, every ring
// of a tube bound to the joint it sits on. Joint frames follow XR_EXT_hand_tracking (-z towards the fingertip, +y out
// of the back of the hand), so a game that poses the model with the tracked joints gets a hand where the hand is.
struct HandMesh { XrPosef bind[XR_HAND_JOINT_COUNT_EXT]; float radius[XR_HAND_JOINT_COUNT_EXT]; XrHandJointEXT parent[XR_HAND_JOINT_COUNT_EXT];
                  std::vector<XrVector3f> pos, nrm; std::vector<XrVector4sFB> joint; std::vector<int16_t> idx; };
HandMesh handMesh[2]; bool handMeshBuilt = false;      // 0 left, 1 right
std::vector<std::pair<XrHandTrackerEXT, int>> handTrackers;
void buildHand(HandMesh &m, bool left) {
    // x and z of each joint of a right hand, metres, in XrHandJointEXT's order: palm, wrist, thumb 4, then 5 a finger
    static const float P[XR_HAND_JOINT_COUNT_EXT][2] = {{0.004f, -0.055f}, {0, 0},
        {-0.025f, -0.020f}, {-0.050f, -0.050f}, {-0.068f, -0.080f}, {-0.080f, -0.105f},
        {-0.018f, -0.025f}, {-0.025f, -0.095f}, {-0.027f, -0.135f}, {-0.028f, -0.160f}, {-0.029f, -0.182f},
        {-0.002f, -0.025f}, {-0.003f, -0.097f}, {-0.003f, -0.142f}, {-0.003f, -0.170f}, {-0.003f, -0.194f},
        {0.012f, -0.025f}, {0.017f, -0.090f}, {0.019f, -0.130f}, {0.020f, -0.157f}, {0.021f, -0.180f},
        {0.025f, -0.022f}, {0.035f, -0.080f}, {0.039f, -0.110f}, {0.041f, -0.129f}, {0.043f, -0.150f}};
    static const float R[XR_HAND_JOINT_COUNT_EXT] = {0.020f, 0.020f, 0.013f, 0.011f, 0.010f, 0.008f, 0.011f, 0.010f, 0.009f, 0.008f, 0.007f,
        0.011f, 0.010f, 0.009f, 0.008f, 0.007f, 0.010f, 0.0095f, 0.0085f, 0.0075f, 0.0065f, 0.009f, 0.0085f, 0.0075f, 0.0065f, 0.006f};
    const int first[5] = {XR_HAND_JOINT_THUMB_METACARPAL_EXT, XR_HAND_JOINT_INDEX_METACARPAL_EXT, XR_HAND_JOINT_MIDDLE_METACARPAL_EXT, XR_HAND_JOINT_RING_METACARPAL_EXT, XR_HAND_JOINT_LITTLE_METACARPAL_EXT};
    const int count[5] = {4, 5, 5, 5, 5}; const int SIDES = 6;
    float sx = left ? -1.f : 1.f;
    auto at = [&](int j) { return V3{P[j][0] * sx, 0, P[j][1]}; };
    for (int j = 0; j < XR_HAND_JOINT_COUNT_EXT; j++) { V3 a = at(j); m.bind[j] = {{0, 0, 0, 1}, {a.x, a.y, a.z}}; m.radius[j] = R[j]; m.parent[j] = XR_HAND_JOINT_WRIST_EXT; }
    auto put = [&](V3 p, V3 n, int j) { m.pos.push_back({p.x, p.y, p.z}); m.nrm.push_back({n.x, n.y, n.z}); m.joint.push_back({(int16_t)j, 0, 0, 0}); };
    auto tri = [&](int a, int b, int c) { m.idx.push_back((int16_t)a); m.idx.push_back((int16_t)b); m.idx.push_back((int16_t)c); };
    // the palm: a box from just in front of the wrist to the knuckles, its back end on the wrist joint, its front on the palm joint
    {
        float x0 = left ? -0.042f : -0.032f, x1 = left ? 0.032f : 0.042f, y0 = -0.011f, y1 = 0.011f, z0 = -0.095f, z1 = -0.005f;
        int b = (int)m.pos.size();
        for (int i = 0; i < 8; i++) {
            V3 p{(i & 1) ? x1 : x0, (i & 2) ? y1 : y0, (i & 4) ? z1 : z0}, n{(i & 1) ? 0.577f : -0.577f, (i & 2) ? 0.577f : -0.577f, (i & 4) ? 0.577f : -0.577f};
            put(p, n, (i & 4) ? XR_HAND_JOINT_WRIST_EXT : XR_HAND_JOINT_PALM_EXT);
        }
        const int q[6][4] = {{1, 3, 7, 5}, {0, 4, 6, 2}, {2, 6, 7, 3}, {0, 1, 5, 4}, {4, 5, 7, 6}, {0, 2, 3, 1}};
        for (auto &f : q) { tri(b + f[0], b + f[1], b + f[2]); tri(b + f[0], b + f[2], b + f[3]); }
    }
    for (int f = 0; f < 5; f++) {
        int prev = -1; V3 dir{0, 0, -1};
        for (int k = 0; k < count[f]; k++) {
            int j = first[f] + k; V3 a = at(j);
            if (k > 0) m.parent[j] = (XrHandJointEXT)(j - 1);
            if (k < count[f] - 1) { V3 b = at(j + 1); float l = std::sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z)); dir = {(b.x - a.x) / l, 0, (b.z - a.z) / l}; }
            V3 z{-dir.x, 0, -dir.z}, y{0, 1, 0}, x{z.z, 0, -z.x};      // x = y cross z
            m.bind[j].orientation = fromAxes(x, y, z);
            int ring = (int)m.pos.size();
            for (int i = 0; i < SIDES; i++) {
                float an = 2 * (float)M_PI * i / SIDES, cs = std::cos(an), sn = std::sin(an); V3 n{cs * x.x + sn * y.x, cs * x.y + sn * y.y, cs * x.z + sn * y.z};
                put({a.x + n.x * R[j], a.y + n.y * R[j], a.z + n.z * R[j]}, n, j);
            }
            if (prev >= 0) for (int i = 0; i < SIDES; i++) { int a0 = prev + i, b0 = prev + (i + 1) % SIDES, c0 = ring + i, d0 = ring + (i + 1) % SIDES; tri(a0, c0, b0); tri(b0, c0, d0); }
            if (k == count[f] - 1) {      // the fingertip closes the tube
                int t = (int)m.pos.size(); put({a.x + dir.x * R[j] * 0.7f, 0, a.z + dir.z * R[j] * 0.7f}, dir, j);
                for (int i = 0; i < SIDES; i++) tri(ring + i, t, ring + (i + 1) % SIDES);
            }
            prev = ring;
        }
    }
}
XrResult XRAPI_CALL uCreateHandTrackerEXT(XrSession s, const XrHandTrackerCreateInfoEXT *info, XrHandTrackerEXT *out) {
    if (!rCreateHandTracker) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = rCreateHandTracker(s, info, out);
    if (XR_SUCCEEDED(r) && info && out) { std::lock_guard<std::mutex> g(lock); handTrackers.push_back({*out, info->hand == XR_HAND_RIGHT_EXT ? 1 : 0}); }
    return r;
}
XrResult XRAPI_CALL uGetHandMeshFB(XrHandTrackerEXT t, XrHandTrackingMeshFB *out) {
    if (!out) return XR_ERROR_VALIDATION_FAILURE;
    std::lock_guard<std::mutex> g(lock);
    if (!handMeshBuilt) { buildHand(handMesh[0], true); buildHand(handMesh[1], false); handMeshBuilt = true; }
    int h = -1; for (auto &p : handTrackers) if (p.first == t) h = p.second;
    if (h < 0) return XR_ERROR_HANDLE_INVALID;
    HandMesh &m = handMesh[h]; uint32_t nj = XR_HAND_JOINT_COUNT_EXT, nv = (uint32_t)m.pos.size(), ni = (uint32_t)m.idx.size();
    out->jointCountOutput = nj; out->vertexCountOutput = nv; out->indexCountOutput = ni;
    if ((out->jointCapacityInput && out->jointCapacityInput < nj) || (out->vertexCapacityInput && out->vertexCapacityInput < nv) || (out->indexCapacityInput && out->indexCapacityInput < ni)) return XR_ERROR_SIZE_INSUFFICIENT;
    if (out->jointCapacityInput) {
        if (out->jointBindPoses) memcpy(out->jointBindPoses, m.bind, sizeof m.bind);
        if (out->jointRadii) memcpy(out->jointRadii, m.radius, sizeof m.radius);
        if (out->jointParents) memcpy(out->jointParents, m.parent, sizeof m.parent);
    }
    if (out->vertexCapacityInput) {
        if (out->vertexPositions) memcpy(out->vertexPositions, m.pos.data(), nv * sizeof(XrVector3f));
        if (out->vertexNormals) memcpy(out->vertexNormals, m.nrm.data(), nv * sizeof(XrVector3f));
        if (out->vertexUVs) memset(out->vertexUVs, 0, nv * sizeof(XrVector2f));
        if (out->vertexBlendIndices) memcpy(out->vertexBlendIndices, m.joint.data(), nv * sizeof(XrVector4sFB));
        if (out->vertexBlendWeights) for (uint32_t i = 0; i < nv; i++) out->vertexBlendWeights[i] = {1, 0, 0, 0};
    }
    if (out->indexCapacityInput && out->indices) memcpy(out->indices, m.idx.data(), ni * sizeof(int16_t));
    return XR_SUCCESS;
}

// ---------------------------------------------------------------- calls passed on, written down for the diagnostics
XrResult XRAPI_CALL uCreateSwapchain(XrSession s, const XrSwapchainCreateInfo *ci, XrSwapchain *out) {
    lastCall = "xrCreateSwapchain"; XrResult r = rCreateSwapchain(s, ci, out);
    if (ci) diag("%s  xrCreateSwapchain format 0x%llx, %ux%u, %u samples, %u array layers, %u faces, %u mips, usage 0x%llx: %s", XR_FAILED(r) ? "FAILED" : "ok", (unsigned long long)ci->format, ci->width, ci->height, ci->sampleCount, ci->arraySize, ci->faceCount, ci->mipCount, (unsigned long long)ci->usageFlags, resultName(r));
    if (XR_FAILED(r) && rSwapchainFormats) { int64_t f[64]; uint32_t n = 0; if (XR_SUCCEEDED(rSwapchainFormats(s, 64, &n, f))) { std::string l; char b[24]; for (uint32_t i = 0; i < n; i++) { snprintf(b, sizeof b, " 0x%llx", (unsigned long long)f[i]); l += b; } diag("        the runtime's swapchain formats:%s", l.c_str()); } }
    return r;
}
XrResult XRAPI_CALL uCreateReferenceSpace(XrSession s, const XrReferenceSpaceCreateInfo *ci, XrSpace *out) {
    lastCall = "xrCreateReferenceSpace"; XrResult r = rCreateReferenceSpace(s, ci, out);
    if (ci) { const char *n = ci->referenceSpaceType == XR_REFERENCE_SPACE_TYPE_VIEW ? "VIEW" : ci->referenceSpaceType == XR_REFERENCE_SPACE_TYPE_LOCAL ? "LOCAL" : ci->referenceSpaceType == XR_REFERENCE_SPACE_TYPE_STAGE ? "STAGE" : ci->referenceSpaceType == XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR_EXT ? "LOCAL_FLOOR" : "other";
        if (XR_FAILED(r)) diag("FAILED  xrCreateReferenceSpace type %d (%s): %s", (int)ci->referenceSpaceType, n, resultName(r)); else diag("ok  xrCreateReferenceSpace %s", n); }
    return r;
}
XrResult XRAPI_CALL uSuggestBindings(XrInstance i, const XrInteractionProfileSuggestedBinding *b) {
    lastCall = "xrSuggestInteractionProfileBindings"; XrResult r = rSuggestBindings(i, b);
    if (b) diag("%s  xrSuggestInteractionProfileBindings %s, %u bindings: %s", XR_FAILED(r) ? "FAILED" : "ok", pathName(b->interactionProfile).c_str(), b->countSuggestedBindings, resultName(r));
    if (b && XR_FAILED(r) && b->suggestedBindings) for (uint32_t k = 0; k < b->countSuggestedBindings && k < 80; k++) diag("        binding %s", pathName(b->suggestedBindings[k].binding).c_str());
    return r;
}
XrResult XRAPI_CALL uAttachActionSets(XrSession s, const XrSessionActionSetsAttachInfo *i) {
    lastCall = "xrAttachSessionActionSets"; XrResult r = rAttachActionSets(s, i);
    diag("%s  xrAttachSessionActionSets %u sets: %s", XR_FAILED(r) ? "FAILED" : "ok", i ? i->countActionSets : 0, resultName(r)); return r;
}
XrResult XRAPI_CALL uBeginSession(XrSession s, const XrSessionBeginInfo *i) {
    lastCall = "xrBeginSession"; XrResult r = rBeginSession(s, i);
    diag("%s  xrBeginSession view configuration %d: %s", XR_FAILED(r) ? "FAILED" : "ok", i ? (int)i->primaryViewConfigurationType : 0, resultName(r)); return r;
}
XrResult XRAPI_CALL uSyncActions(XrSession s, const XrActionsSyncInfo *i) {
    XrResult r = rSyncActions(s, i); if (XR_FAILED(r)) { lastCall = "xrSyncActions"; ONCE("FAILED  xrSyncActions: %s (said once)", resultName(r)); } return r;
}
void noteProfiles(XrSession s) {
    if (!rCurrentProfile || !rStringToPath || !instance) return;
    for (const char *hand : {"/user/hand/left", "/user/hand/right"}) {
        XrPath p = XR_NULL_PATH; rStringToPath(instance, hand, &p); XrInteractionProfileState st{XR_TYPE_INTERACTION_PROFILE_STATE};
        XrResult r = rCurrentProfile(s, p, &st); diag("INPUT  %s is now %s", hand, XR_SUCCEEDED(r) ? pathName(st.interactionProfile).c_str() : resultName(r));
    }
}

// ---------------------------------------------------------------- calls we pass on, with a hand in them
XrResult XRAPI_CALL uLocateSpace(XrSpace space, XrSpace base, XrTime time, XrSpaceLocation *loc) {
    Entity *a = entity(space), *b = entity(base);
    if (!a && !b) return rLocateSpace(space, base, time, loc);
    std::lock_guard<std::mutex> g(lock);
    const XrSpaceLocationFlags all = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT | XR_SPACE_LOCATION_POSITION_TRACKED_BIT;
    if ((a && !a->locatable && a->kind != ROOM) || (b && !b->locatable && b->kind != ROOM)) return XR_ERROR_SPACE_COMPONENT_NOT_ENABLED_FB;
    XrPosef pa, pb;      // both in our local-floor space
    if (a) pa = roomInRef(a->pose);
    else { XrSpaceLocation l{XR_TYPE_SPACE_LOCATION}; XrResult r = rLocateSpace(space, roomRef, time, &l); if (XR_FAILED(r)) return r; if (!(l.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT)) { loc->locationFlags = 0; return XR_SUCCESS; } pa = l.pose; }
    if (b) pb = roomInRef(b->pose);
    else { XrSpaceLocation l{XR_TYPE_SPACE_LOCATION}; XrResult r = rLocateSpace(base, roomRef, time, &l); if (XR_FAILED(r)) return r; if (!(l.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT)) { loc->locationFlags = 0; return XR_SUCCESS; } pb = l.pose; }
    loc->pose = compose(inverse(pb), pa); loc->locationFlags = aligned ? all : (XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT);
    return XR_SUCCESS;
}
XrResult XRAPI_CALL uDestroySpace(XrSpace s) { if (isEntity(s)) return XR_SUCCESS; return rDestroySpace(s); }
XrResult XRAPI_CALL uWaitFrame(XrSession s, const XrFrameWaitInfo *i, XrFrameState *st) {
    lastCall = "xrWaitFrame"; XrResult r = rWaitFrame(s, i, st);
    if (XR_SUCCEEDED(r)) { std::lock_guard<std::mutex> g(lock); lastTime = st->predictedDisplayTime; align(); }
    else ONCE("FAILED  xrWaitFrame: %s (said once)", resultName(r));
    long n = ++frameCount; if (n == 1) diag("FRAMES  the game's first frame"); else if (n % 1800 == 0) diagSummary("running");
    return r;
}
XrResult XRAPI_CALL uEndFrame(XrSession s, const XrFrameEndInfo *info) {
    // passthrough layers are not drawn: the glasses are see-through. The rest go on in their order.
    std::vector<const XrCompositionLayerBaseHeader *> keep; keep.reserve(info->layerCount);
    for (uint32_t i = 0; i < info->layerCount; i++) { if (info->layers[i] && info->layers[i]->type == XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB) passthroughLayersDropped++; else keep.push_back(info->layers[i]); }
    XrFrameEndInfo e = *info; e.layerCount = (uint32_t)keep.size(); e.layers = keep.empty() ? nullptr : keep.data();
    lastCall = "xrEndFrame"; XrResult r = rEndFrame(s, &e);
    static std::atomic<int> said{0}; static std::atomic<uint32_t> lastShape{0xffffffffu};
    uint32_t shape = (uint32_t)info->environmentBlendMode * 131u + info->layerCount; for (uint32_t i = 0; i < info->layerCount; i++) shape = shape * 31u + (info->layers[i] ? (uint32_t)info->layers[i]->type : 0u);
    if ((XR_FAILED(r) || lastShape.exchange(shape) != shape) && said++ < 12) {      // the first frame, and whenever what is sent changes
        std::string l; char b[48];
        for (uint32_t i = 0; i < info->layerCount; i++) { int t = info->layers[i] ? (int)info->layers[i]->type : 0;
            snprintf(b, sizeof b, " %s", t == XR_TYPE_COMPOSITION_LAYER_PROJECTION ? "projection" : t == XR_TYPE_COMPOSITION_LAYER_QUAD ? "quad" : t == XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB ? "passthrough(taken out)" : t == XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR ? "cylinder" : t == XR_TYPE_COMPOSITION_LAYER_EQUIRECT2_KHR ? "equirect2" : t == XR_TYPE_COMPOSITION_LAYER_CUBE_KHR ? "cube" : "");
            if (!b[1]) snprintf(b, sizeof b, " type-%d", t); l += b; }
        diag("%s  xrEndFrame blend mode %d, %u layers:%s: %s", XR_FAILED(r) ? "FAILED" : "ok", (int)info->environmentBlendMode, info->layerCount, l.c_str(), resultName(r));
    }
    if (XR_FAILED(r)) endFrameFails++;
    return r;
}
XrResult XRAPI_CALL uPollEvent(XrInstance i, XrEventDataBuffer *buf) {
    { std::lock_guard<std::mutex> g(lock); if (!events.empty()) { memcpy(buf, events.front().data(), events.front().size()); events.pop_front(); return XR_SUCCESS; } }
    XrResult r = rPollEvent(i, buf);
    if (r == XR_SUCCESS) {
        if (buf->type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) diag("SESSION  state %s", stateName(((XrEventDataSessionStateChanged *)buf)->state));
        else if (buf->type == XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED) noteProfiles(((XrEventDataInteractionProfileChanged *)buf)->session);
        else if (buf->type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) diag("LOST  the runtime says the instance is about to be lost");
        else if (buf->type == XR_TYPE_EVENT_DATA_EVENTS_LOST) diag("EVENTS  the runtime dropped %u events", ((XrEventDataEventsLost *)buf)->lostEventCount);
    }
    return r;
}
XrResult XRAPI_CALL uGetSystemProperties(XrInstance i, XrSystemId id, XrSystemProperties *p) {
    XrResult r = rGetSystemProperties(i, id, p);
    if (XR_FAILED(r) && p->next) {
        // The runtime refuses the whole call over one chained structure it does not like (one for an extension the
        // application did not enable, say). Quest games chain many. Ask again without them and fill in what is ours.
        void *chain = p->next; p->next = nullptr; XrResult plain = rGetSystemProperties(i, id, p); p->next = chain;
        static bool said = false; if (!said) { said = true; LOGI("xrGetSystemProperties: the runtime answered %d with the application's chained structures, %d without them", (int)r, (int)plain); }
        if (XR_SUCCEEDED(plain)) {
            r = plain;
            for (XrBaseOutStructure *n = (XrBaseOutStructure *)p->next; n; n = n->next)
                if (n->type == XR_TYPE_SYSTEM_HAND_TRACKING_PROPERTIES_EXT) ((XrSystemHandTrackingPropertiesEXT *)n)->supportsHandTracking = rLocateHandJoints ? XR_TRUE : XR_FALSE;
        }
    }
    for (XrBaseOutStructure *n = (XrBaseOutStructure *)p->next; XR_SUCCEEDED(r) && n; n = n->next) {
        if (n->type == XR_TYPE_SYSTEM_PASSTHROUGH_PROPERTIES_FB) ((XrSystemPassthroughPropertiesFB *)n)->supportsPassthrough = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_SPATIAL_ENTITY_PROPERTIES_FB) ((XrSystemSpatialEntityPropertiesFB *)n)->supportsSpatialEntity = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_BOUNDARY_VISIBILITY_PROPERTIES_META) ((XrSystemBoundaryVisibilityPropertiesMETA *)n)->supportsBoundaryVisibility = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_ENVIRONMENT_DEPTH_PROPERTIES_META) { ((XrSystemEnvironmentDepthPropertiesMETA *)n)->supportsEnvironmentDepth = XR_TRUE; ((XrSystemEnvironmentDepthPropertiesMETA *)n)->supportsHandRemoval = XR_FALSE; }
    }
    return r;
}
XrResult XRAPI_CALL uCreateSession(XrInstance i, const XrSessionCreateInfo *info, XrSession *out) {
    lastCall = "xrCreateSession"; XrResult r = rCreateSession(i, info, out);
    const char *api = "no graphics binding";
    for (const XrBaseInStructure *n = (const XrBaseInStructure *)info->next; n; n = n->next) { if (n->type == XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR) api = "OpenGL ES"; if (n->type == XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR) api = "Vulkan"; }
    diag("%s  xrCreateSession with %s: %s", XR_FAILED(r) ? "FAILED" : "ok", api, resultName(r));
    if (XR_FAILED(r)) return r;
    if (rBlendModes) { XrEnvironmentBlendMode m[8]; uint32_t n = 0; if (XR_SUCCEEDED(rBlendModes(i, info->systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 8, &n, m))) { std::string l; char b[16]; for (uint32_t k = 0; k < n; k++) { snprintf(b, sizeof b, " %d", (int)m[k]); l += b; } diag("        blend modes the runtime takes:%s (1 opaque, 2 additive, 3 alpha blend)", l.c_str()); } }
    std::lock_guard<std::mutex> g(lock);
    session = *out; aligned = false; lastTime = 0; loadAnchors();
    sessionGles = false; for (const XrBaseInStructure *n = (const XrBaseInStructure *)info->next; n; n = n->next) if (n->type == XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR) sessionGles = true;
    XrReferenceSpaceCreateInfo ci{XR_TYPE_REFERENCE_SPACE_CREATE_INFO}; ci.poseInReferenceSpace = {{0, 0, 0, 1}, {0, 0, 0}};
    ci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR_EXT;
    if (XR_FAILED(rCreateReferenceSpace(session, &ci, &roomRef))) { ci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL; rCreateReferenceSpace(session, &ci, &roomRef); }
    ci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW; rCreateReferenceSpace(session, &ci, &viewRef);
    if (!running.exchange(true)) poller = std::thread(pollLoop);
    LOGI("session made: passthrough, scene and anchors are answered by the layer");
    return r;
}
XrResult XRAPI_CALL uDestroySession(XrSession s) {
    diagSummary("session destroyed");
    if (running.exchange(false) && poller.joinable()) poller.join();
    { std::lock_guard<std::mutex> g(lock); if (roomRef) rDestroySpace(roomRef); if (viewRef) rDestroySpace(viewRef); roomRef = viewRef = XR_NULL_HANDLE; session = XR_NULL_HANDLE; }
    return rDestroySession(s);
}
XrResult XRAPI_CALL uLocateHandJointsEXT(XrHandTrackerEXT t, const XrHandJointsLocateInfoEXT *info, XrHandJointLocationsEXT *out) {
    if (!rLocateHandJoints) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = rLocateHandJoints(t, info, out); if (XR_FAILED(r)) return r;
    for (XrBaseOutStructure *n = (XrBaseOutStructure *)out->next; n; n = n->next) {
        // the hand model above is made life-size and the joints are not rescaled: the scale is one
        if (n->type == XR_TYPE_HAND_TRACKING_SCALE_FB) { auto *sc = (XrHandTrackingScaleFB *)n; sc->sensorOutput = 1; sc->currentOutput = 1; continue; }
        if (n->type != XR_TYPE_HAND_TRACKING_AIM_STATE_FB) continue;
        auto *aim = (XrHandTrackingAimStateFB *)n; aim->status = 0; aim->pinchStrengthIndex = aim->pinchStrengthMiddle = aim->pinchStrengthRing = aim->pinchStrengthLittle = 0;
        if (!out->isActive || out->jointCount < XR_HAND_JOINT_COUNT_EXT) continue;
        auto P = [&](int j) { return out->jointLocations[j].pose.position; };
        auto strength = [&](int tip) { XrVector3f a = P(XR_HAND_JOINT_THUMB_TIP_EXT), b = P(tip); float d = std::sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)); float s = 1 - (d - 0.015f) / 0.045f; return s < 0 ? 0.f : s > 1 ? 1.f : s; };
        aim->pinchStrengthIndex = strength(XR_HAND_JOINT_INDEX_TIP_EXT); aim->pinchStrengthMiddle = strength(XR_HAND_JOINT_MIDDLE_TIP_EXT);
        aim->pinchStrengthRing = strength(XR_HAND_JOINT_RING_TIP_EXT); aim->pinchStrengthLittle = strength(XR_HAND_JOINT_LITTLE_TIP_EXT);
        // the aim: from the index knuckle, the way the palm faces forward
        aim->aimPose = out->jointLocations[XR_HAND_JOINT_INDEX_PROXIMAL_EXT].pose;
        aim->status = XR_HAND_TRACKING_AIM_COMPUTED_BIT_FB | XR_HAND_TRACKING_AIM_VALID_BIT_FB | (aim->pinchStrengthIndex > 0.8f ? XR_HAND_TRACKING_AIM_INDEX_PINCHING_BIT_FB : 0);
    }
    return r;
}
XrResult XRAPI_CALL uDestroyInstance(XrInstance i) {
    if (running.exchange(false) && poller.joinable()) poller.join();
    PFN_xrDestroyInstance d = rDestroyInstance; instance = XR_NULL_HANDLE; return d(i);
}

// ---------------------------------------------------------------- the layer's plumbing
struct Entry { const char *name; PFN_xrVoidFunction fn; };
#define E(n, f) {n, (PFN_xrVoidFunction)f}
const Entry table[] = {
    E("xrDestroyInstance", uDestroyInstance), E("xrGetSystemProperties", uGetSystemProperties), E("xrCreateSession", uCreateSession), E("xrDestroySession", uDestroySession),
    E("xrCreateSwapchain", uCreateSwapchain), E("xrCreateReferenceSpace", uCreateReferenceSpace), E("xrSuggestInteractionProfileBindings", uSuggestBindings),
    E("xrAttachSessionActionSets", uAttachActionSets), E("xrBeginSession", uBeginSession), E("xrSyncActions", uSyncActions),
    E("xrLocateSpace", uLocateSpace), E("xrDestroySpace", uDestroySpace), E("xrWaitFrame", uWaitFrame), E("xrEndFrame", uEndFrame), E("xrPollEvent", uPollEvent),
    E("xrLocateHandJointsEXT", uLocateHandJointsEXT), E("xrCreateHandTrackerEXT", uCreateHandTrackerEXT), E("xrGetHandMeshFB", uGetHandMeshFB),
    E("xrGetSpaceTriangleMeshMETA", uGetSpaceTriangleMeshMETA), E("xrRequestSceneCaptureFB", uRequestSceneCaptureFB), E("xrShareSpacesFB", uShareSpacesFB),
    E("xrCreateSpaceUserFB", uCreateSpaceUserFB), E("xrGetSpaceUserIdFB", uGetSpaceUserIdFB), E("xrDestroySpaceUserFB", uDestroySpaceUserFB),
    E("xrRequestBoundaryVisibilityMETA", uRequestBoundaryVisibilityMETA),
    E("xrCreateEnvironmentDepthProviderMETA", uCreateEnvironmentDepthProviderMETA), E("xrDestroyEnvironmentDepthProviderMETA", uDestroyEnvironmentDepthProviderMETA),
    E("xrStartEnvironmentDepthProviderMETA", uStartEnvironmentDepthProviderMETA), E("xrStopEnvironmentDepthProviderMETA", uStopEnvironmentDepthProviderMETA),
    E("xrCreateEnvironmentDepthSwapchainMETA", uCreateEnvironmentDepthSwapchainMETA), E("xrDestroyEnvironmentDepthSwapchainMETA", uDestroyEnvironmentDepthSwapchainMETA),
    E("xrEnumerateEnvironmentDepthSwapchainImagesMETA", uEnumerateEnvironmentDepthSwapchainImagesMETA), E("xrGetEnvironmentDepthSwapchainStateMETA", uGetEnvironmentDepthSwapchainStateMETA),
    E("xrAcquireEnvironmentDepthImageMETA", uAcquireEnvironmentDepthImageMETA), E("xrSetEnvironmentDepthHandRemovalMETA", uSetEnvironmentDepthHandRemovalMETA),
    E("xrCreatePassthroughFB", uCreatePassthroughFB), E("xrDestroyPassthroughFB", uDestroyPassthroughFB), E("xrPassthroughStartFB", uPassthroughStartFB), E("xrPassthroughPauseFB", uPassthroughPauseFB),
    E("xrCreatePassthroughLayerFB", uCreatePassthroughLayerFB), E("xrDestroyPassthroughLayerFB", uDestroyPassthroughLayerFB), E("xrPassthroughLayerPauseFB", uPassthroughLayerPauseFB),
    E("xrPassthroughLayerResumeFB", uPassthroughLayerResumeFB), E("xrPassthroughLayerSetStyleFB", uPassthroughLayerSetStyleFB),
    E("xrCreateGeometryInstanceFB", uCreateGeometryInstanceFB), E("xrDestroyGeometryInstanceFB", uDestroyGeometryInstanceFB), E("xrGeometryInstanceSetTransformFB", uGeometryInstanceSetTransformFB),
    E("xrCreateSpatialAnchorFB", uCreateSpatialAnchorFB), E("xrGetSpaceUuidFB", uGetSpaceUuidFB), E("xrEnumerateSpaceSupportedComponentsFB", uEnumerateSpaceSupportedComponentsFB),
    E("xrSetSpaceComponentStatusFB", uSetSpaceComponentStatusFB), E("xrGetSpaceComponentStatusFB", uGetSpaceComponentStatusFB),
    E("xrQuerySpacesFB", uQuerySpacesFB), E("xrRetrieveSpaceQueryResultsFB", uRetrieveSpaceQueryResultsFB), E("xrSaveSpaceFB", uSaveSpaceFB), E("xrEraseSpaceFB", uEraseSpaceFB),
    E("xrGetSpaceContainerFB", uGetSpaceContainerFB), E("xrGetSpaceBoundingBox2DFB", uGetSpaceBoundingBox2DFB), E("xrGetSpaceBoundingBox3DFB", uGetSpaceBoundingBox3DFB),
    E("xrGetSpaceBoundary2DFB", uGetSpaceBoundary2DFB), E("xrGetSpaceSemanticLabelsFB", uGetSpaceSemanticLabelsFB), E("xrGetSpaceRoomLayoutFB", uGetSpaceRoomLayoutFB),
};
XrResult XRAPI_CALL uGetInstanceProcAddr(XrInstance i, const char *name, PFN_xrVoidFunction *fn) {
    for (auto &e : table) if (strcmp(e.name, name) == 0) {
        // a call the runtime must also have: only ours if the runtime has it (hand joints may be missing)
        if ((strcmp(name, "xrLocateHandJointsEXT") == 0 || strcmp(name, "xrCreateHandTrackerEXT") == 0 || strcmp(name, "xrGetHandMeshFB") == 0) && (!rLocateHandJoints || !rCreateHandTracker)) break;
        *fn = e.fn; return XR_SUCCESS;
    }
    // asked before the instance exists (the loader looks the layer over first): nothing below us yet
    if (!nextGipa) { *fn = nullptr; return XR_ERROR_FUNCTION_UNSUPPORTED; }
    XrResult r = nextGipa(i, name, fn);
    // not there: a stand-in that answers "unsupported" and writes the call down (see stubFor)
    if ((XR_FAILED(r) || !*fn) && i != XR_NULL_HANDLE && name && name[0] == 'x' && name[1] == 'r') { PFN_xrVoidFunction s = stubFor(name); if (s) { *fn = s; return XR_SUCCESS; } }
    return r;
}
XrResult XRAPI_CALL uCreateApiLayerInstance(const XrInstanceCreateInfo *info, const XrApiLayerCreateInfo *layerInfo, XrInstance *out) {
    if (!layerInfo || !layerInfo->nextInfo) return XR_ERROR_INITIALIZATION_FAILED;
    // our extensions are ours: the runtime is not asked for them
    hookCrashes();
    std::vector<const char *> ext; std::string mineList;
    for (uint32_t i = 0; i < info->enabledExtensionCount; i++) { bool mine = false; for (auto o : OURS) if (strcmp(o, info->enabledExtensionNames[i]) == 0) mine = true; if (!mine) ext.push_back(info->enabledExtensionNames[i]); else { mineList += " "; mineList += info->enabledExtensionNames[i] + 3; } }
    diag("GAME  %s (package %s), engine %s %u, asks for OpenXR %u.%u.%u", info->applicationInfo.applicationName, packageName().c_str(), info->applicationInfo.engineName, info->applicationInfo.engineVersion,
         (unsigned)XR_VERSION_MAJOR(info->applicationInfo.apiVersion), (unsigned)XR_VERSION_MINOR(info->applicationInfo.apiVersion), (unsigned)XR_VERSION_PATCH(info->applicationInfo.apiVersion));
    if (!diagPath.empty()) diag("LOG  this file: %s", diagPath.c_str());
    // The layer keeps the room in a floor-level space of its own: that needs XR_EXT_local_floor enabled, whether or
    // not the application asked for it. (A runtime without it refuses the instance: then it is asked again without.)
    bool addedFloor = false; { bool has = false; for (auto e : ext) if (strcmp(e, "XR_EXT_local_floor") == 0) has = true; if (!has) { ext.push_back("XR_EXT_local_floor"); addedFloor = true; } }
    XrInstanceCreateInfo ci = *info; ci.enabledExtensionCount = (uint32_t)ext.size(); ci.enabledExtensionNames = ext.data();
    XrApiLayerCreateInfo li = *layerInfo; li.nextInfo = layerInfo->nextInfo->next;
    nextGipa = layerInfo->nextInfo->nextGetInstanceProcAddr;
    XrResult r = layerInfo->nextInfo->nextCreateApiLayerInstance(&ci, &li, out);
    if (r == XR_ERROR_EXTENSION_NOT_PRESENT && addedFloor) { ext.pop_back(); addedFloor = false; ci.enabledExtensionCount = (uint32_t)ext.size(); ci.enabledExtensionNames = ext.data(); r = layerInfo->nextInfo->nextCreateApiLayerInstance(&ci, &li, out); }
    std::vector<const char *> refused;
    if (r == XR_ERROR_EXTENSION_NOT_PRESENT) {
        // The game enabled an extension nobody has. A Quest game does that when what stands in front of it promised
        // more than there is. Find which, say so, and go on without them: what the game then calls is written down too.
        PFN_xrEnumerateInstanceExtensionProperties en = nullptr; nextGipa(XR_NULL_HANDLE, "xrEnumerateInstanceExtensionProperties", (PFN_xrVoidFunction *)&en);
        uint32_t n = 0; std::vector<XrExtensionProperties> have;
        if (en && XR_SUCCEEDED(en(nullptr, 0, &n, nullptr)) && n) { have.assign(n, {XR_TYPE_EXTENSION_PROPERTIES}); if (XR_FAILED(en(nullptr, n, &n, have.data()))) have.clear(); }
        if (!have.empty()) {
            std::vector<const char *> kept;
            for (auto e : ext) { bool ok = false; for (auto &h : have) if (strcmp(h.extensionName, e) == 0) ok = true; (ok ? kept : refused).push_back(e); }
            for (auto e : refused) diag("UNSUPPORTED EXTENSION  %s: enabled by the game, in neither the runtime nor the layer; left out so the game can go on", e);
            if (!refused.empty()) { ext = kept; ci.enabledExtensionCount = (uint32_t)ext.size(); ci.enabledExtensionNames = ext.data(); r = layerInfo->nextInfo->nextCreateApiLayerInstance(&ci, &li, out); }
        } else diag("FAILED  the runtime refused an extension and its list could not be read to say which");
    }
    { std::string l; for (auto e : ext) { l += " "; l += e + 3; }
      diag("EXTENSIONS  %u enabled by the game: answered by the layer:%s | passed to the runtime:%s%s | unsupported: %u", info->enabledExtensionCount, mineList.empty() ? " none" : mineList.c_str(), l.empty() ? " none" : l.c_str(), addedFloor ? " (local_floor added by the layer)" : "", (unsigned)refused.size()); }
    if (XR_FAILED(r)) { diag("FAILED  xrCreateInstance: %s (the game cannot start)", resultName(r)); return r; }
    instance = *out;
#define R(n, v) nextGipa(instance, n, (PFN_xrVoidFunction *)&v)
    R("xrDestroyInstance", rDestroyInstance); R("xrGetSystemProperties", rGetSystemProperties); R("xrCreateSession", rCreateSession); R("xrDestroySession", rDestroySession);
    R("xrCreateReferenceSpace", rCreateReferenceSpace); R("xrDestroySpace", rDestroySpace); R("xrLocateSpace", rLocateSpace); R("xrPollEvent", rPollEvent);
    R("xrWaitFrame", rWaitFrame); R("xrEndFrame", rEndFrame); R("xrLocateViews", rLocateViews);
    R("xrCreateSwapchain", rCreateSwapchain); R("xrSuggestInteractionProfileBindings", rSuggestBindings); R("xrAttachSessionActionSets", rAttachActionSets); R("xrBeginSession", rBeginSession);
    R("xrSyncActions", rSyncActions); R("xrPathToString", rPathToString); R("xrStringToPath", rStringToPath); R("xrGetCurrentInteractionProfile", rCurrentProfile);
    R("xrEnumerateSwapchainFormats", rSwapchainFormats); R("xrEnumerateEnvironmentBlendModes", rBlendModes);
    rLocateHandJoints = nullptr; R("xrLocateHandJointsEXT", rLocateHandJoints); rCreateHandTracker = nullptr; R("xrCreateHandTrackerEXT", rCreateHandTracker);
    LOGI("layer active over the runtime (%u of the application's %u extensions passed down)", (unsigned)ext.size(), info->enabledExtensionCount);
    return r;
}
}      // namespace

extern "C" __attribute__((visibility("default"))) XrResult XRAPI_CALL xrNegotiateLoaderApiLayerInterface(const XrNegotiateLoaderInfo *loaderInfo, const char *layerName, XrNegotiateApiLayerRequest *req) {
    if (!loaderInfo || !req || (layerName && strcmp(layerName, LAYER) != 0)) return XR_ERROR_INITIALIZATION_FAILED;
    req->layerInterfaceVersion = XR_CURRENT_LOADER_API_LAYER_VERSION; req->layerApiVersion = XR_CURRENT_API_VERSION;
    req->getInstanceProcAddr = uGetInstanceProcAddr; req->createApiLayerInstance = uCreateApiLayerInstance;
    return XR_SUCCESS;
}

// for the test app in the same process: what the layer has done so far
extern "C" __attribute__((visibility("default"))) int uxrMetaLayerDepthProbe(float *centre, float *low) { *centre = depthProbe[0]; *low = depthProbe[1]; return depthImages; }
extern "C" __attribute__((visibility("default"))) void uxrMetaLayerStats(int *packets, int *dropped, int *alignedOut, int *entityCount) {
    std::lock_guard<std::mutex> g(lock); *packets = roomPackets; *dropped = passthroughLayersDropped; *alignedOut = aligned ? 1 : 0; *entityCount = (int)entities.size();
}
