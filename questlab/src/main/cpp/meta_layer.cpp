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
//
// Everything else goes to the runtime untouched. One instance and one session at a time, as on a headset.
#include <jni.h>
#include <android/log.h>
#define XR_USE_PLATFORM_ANDROID
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <openxr/openxr_loader_negotiation.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
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
                      "XR_FB_spatial_entity_container", "XR_FB_scene", "XR_FB_hand_tracking_aim"};

PFN_xrGetInstanceProcAddr nextGipa = nullptr;
XrInstance instance = XR_NULL_HANDLE;
XrSession session = XR_NULL_HANDLE;
XrSpace roomRef = XR_NULL_HANDLE, viewRef = XR_NULL_HANDLE;      // ours, on the real runtime: local-floor and the head
XrTime lastTime = 0;

// the runtime's own functions
PFN_xrDestroyInstance rDestroyInstance; PFN_xrGetSystemProperties rGetSystemProperties; PFN_xrCreateSession rCreateSession;
PFN_xrDestroySession rDestroySession; PFN_xrCreateReferenceSpace rCreateReferenceSpace; PFN_xrDestroySpace rDestroySpace;
PFN_xrLocateSpace rLocateSpace; PFN_xrPollEvent rPollEvent; PFN_xrWaitFrame rWaitFrame; PFN_xrEndFrame rEndFrame;
PFN_xrLocateHandJointsEXT rLocateHandJoints;

std::mutex lock;

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
enum Kind { ROOM, FLOOR, CEILING, WALL, TABLE, ANCHOR };
struct Entity {
    Kind kind; XrUuidEXT uuid; XrPosef pose;      // pose in the room's frame (room.json's)
    float w = 0, h = 0;                           // a plane's size along its x and y
    bool locatable = false, storable = false, saved = false;
    int key = 0;                                  // which wall or table it was in the room data
};
std::vector<Entity *> entities;                   // never freed while the instance lives: games keep the handles
bool isEntity(XrSpace s) { for (auto *e : entities) if ((XrSpace)(uintptr_t)e == s) return true; return false; }
Entity *entity(XrSpace s) { return isEntity(s) ? (Entity *)(uintptr_t)s : nullptr; }
XrUuidEXT makeUuid(Kind k, int n) {
    XrUuidEXT u{}; const char tag[] = "UltraXR-room";
    memcpy(u.data, tag, 12); u.data[12] = (uint8_t)k; u.data[13] = (uint8_t)(n >> 8); u.data[14] = (uint8_t)n; u.data[15] = 1; return u;
}
int anchorCount = 0;

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
    for (auto *e : entities) if (e->kind == kind && e->key == key) { e->pose = pose; e->w = w; e->h = h; return; }
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
        default: return false;
    }
}
bool enabled(Entity *e, XrSpaceComponentTypeFB c) {
    if (!has(e, c)) return false;
    if (c == XR_SPACE_COMPONENT_TYPE_LOCATABLE_FB) return e->locatable;
    if (c == XR_SPACE_COMPONENT_TYPE_STORABLE_FB) return e->storable;
    return true;      // what the room model gives is always there
}
const char *label(Kind k) { return k == FLOOR ? "FLOOR" : k == CEILING ? "CEILING" : k == WALL ? "WALL_FACE" : k == TABLE ? "TABLE" : ""; }

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
XrResult XRAPI_CALL uCreateGeometryInstanceFB(XrSession, const XrGeometryInstanceCreateInfoFB *, XrGeometryInstanceFB *) { return XR_ERROR_FEATURE_UNSUPPORTED; }
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
    e->locatable = true; entities.push_back(e);
    *req = nextRequest++;
    XrEventDataSpatialAnchorCreateCompleteFB ev{XR_TYPE_EVENT_DATA_SPATIAL_ANCHOR_CREATE_COMPLETE_FB}; ev.requestId = *req; ev.result = XR_SUCCESS; ev.space = (XrSpace)(uintptr_t)e; ev.uuid = e->uuid; push(ev);
    return XR_SUCCESS;
}
XrResult XRAPI_CALL uGetSpaceUuidFB(XrSpace s, XrUuidEXT *u) { Entity *e = entity(s); if (!e) return XR_ERROR_HANDLE_INVALID; *u = e->uuid; return XR_SUCCESS; }
XrResult XRAPI_CALL uEnumerateSpaceSupportedComponentsFB(XrSpace s, uint32_t cap, uint32_t *count, XrSpaceComponentTypeFB *out) {
    Entity *e = entity(s); if (!e) return XR_ERROR_HANDLE_INVALID;
    const XrSpaceComponentTypeFB all[] = {XR_SPACE_COMPONENT_TYPE_LOCATABLE_FB, XR_SPACE_COMPONENT_TYPE_STORABLE_FB, XR_SPACE_COMPONENT_TYPE_BOUNDED_2D_FB,
                                          XR_SPACE_COMPONENT_TYPE_SEMANTIC_LABELS_FB, XR_SPACE_COMPONENT_TYPE_ROOM_LAYOUT_FB, XR_SPACE_COMPONENT_TYPE_SPACE_CONTAINER_FB};
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
        if (e->kind == ANCHOR && !e->saved) continue;                       // a query finds what is stored
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
    e->saved = true; *req = nextRequest++;      // kept for as long as the game runs; not yet across runs
    XrEventDataSpaceSaveCompleteFB ev{XR_TYPE_EVENT_DATA_SPACE_SAVE_COMPLETE_FB}; ev.requestId = *req; ev.result = XR_SUCCESS; ev.space = info->space; ev.uuid = e->uuid; ev.location = info->location; push(ev);
    return XR_SUCCESS;
}
XrResult XRAPI_CALL uEraseSpaceFB(XrSession, const XrSpaceEraseInfoFB *info, XrAsyncRequestIdFB *req) {
    Entity *e = entity(info->space); if (!e) return XR_ERROR_HANDLE_INVALID;
    std::lock_guard<std::mutex> g(lock);
    e->saved = false; *req = nextRequest++;
    XrEventDataSpaceEraseCompleteFB ev{XR_TYPE_EVENT_DATA_SPACE_ERASE_COMPLETE_FB}; ev.requestId = *req; ev.result = XR_SUCCESS; ev.space = info->space; ev.uuid = e->uuid; ev.location = info->location; push(ev);
    return XR_SUCCESS;
}
XrResult XRAPI_CALL uGetSpaceContainerFB(XrSession, XrSpace s, XrSpaceContainerFB *out) {
    Entity *e = entity(s); if (!e || e->kind != ROOM) return XR_ERROR_SPACE_COMPONENT_NOT_ENABLED_FB;
    std::lock_guard<std::mutex> g(lock);
    uint32_t n = 0; for (auto *x : entities) if (x->kind != ROOM && x->kind != ANCHOR) { if (out->uuids && n < out->uuidCapacityInput) out->uuids[n] = x->uuid; n++; }
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
        else if (x->kind == WALL) { if (out->wallUuids && n < out->wallUuidCapacityInput) out->wallUuids[n] = x->uuid; n++; }
    }
    out->wallUuidCountOutput = n; return (out->wallUuidCapacityInput && out->wallUuidCapacityInput < n) ? XR_ERROR_SIZE_INSUFFICIENT : XR_SUCCESS;
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
    XrResult r = rWaitFrame(s, i, st);
    if (XR_SUCCEEDED(r)) { std::lock_guard<std::mutex> g(lock); lastTime = st->predictedDisplayTime; align(); }
    return r;
}
XrResult XRAPI_CALL uEndFrame(XrSession s, const XrFrameEndInfo *info) {
    // passthrough layers are not drawn: the glasses are see-through. The rest go on in their order.
    std::vector<const XrCompositionLayerBaseHeader *> keep; keep.reserve(info->layerCount);
    for (uint32_t i = 0; i < info->layerCount; i++) { if (info->layers[i] && info->layers[i]->type == XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB) passthroughLayersDropped++; else keep.push_back(info->layers[i]); }
    XrFrameEndInfo e = *info; e.layerCount = (uint32_t)keep.size(); e.layers = keep.empty() ? nullptr : keep.data();
    return rEndFrame(s, &e);
}
XrResult XRAPI_CALL uPollEvent(XrInstance i, XrEventDataBuffer *buf) {
    { std::lock_guard<std::mutex> g(lock); if (!events.empty()) { memcpy(buf, events.front().data(), events.front().size()); events.pop_front(); return XR_SUCCESS; } }
    return rPollEvent(i, buf);
}
XrResult XRAPI_CALL uGetSystemProperties(XrInstance i, XrSystemId id, XrSystemProperties *p) {
    XrResult r = rGetSystemProperties(i, id, p);
    for (XrBaseOutStructure *n = (XrBaseOutStructure *)p->next; XR_SUCCEEDED(r) && n; n = n->next) {
        if (n->type == XR_TYPE_SYSTEM_PASSTHROUGH_PROPERTIES_FB) ((XrSystemPassthroughPropertiesFB *)n)->supportsPassthrough = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_SPATIAL_ENTITY_PROPERTIES_FB) ((XrSystemSpatialEntityPropertiesFB *)n)->supportsSpatialEntity = XR_TRUE;
    }
    return r;
}
XrResult XRAPI_CALL uCreateSession(XrInstance i, const XrSessionCreateInfo *info, XrSession *out) {
    XrResult r = rCreateSession(i, info, out); if (XR_FAILED(r)) return r;
    std::lock_guard<std::mutex> g(lock);
    session = *out; aligned = false; lastTime = 0;
    XrReferenceSpaceCreateInfo ci{XR_TYPE_REFERENCE_SPACE_CREATE_INFO}; ci.poseInReferenceSpace = {{0, 0, 0, 1}, {0, 0, 0}};
    ci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR_EXT;
    if (XR_FAILED(rCreateReferenceSpace(session, &ci, &roomRef))) { ci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL; rCreateReferenceSpace(session, &ci, &roomRef); }
    ci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW; rCreateReferenceSpace(session, &ci, &viewRef);
    if (!running.exchange(true)) poller = std::thread(pollLoop);
    LOGI("session made: passthrough, scene and anchors are answered by the layer");
    return r;
}
XrResult XRAPI_CALL uDestroySession(XrSession s) {
    if (running.exchange(false) && poller.joinable()) poller.join();
    { std::lock_guard<std::mutex> g(lock); if (roomRef) rDestroySpace(roomRef); if (viewRef) rDestroySpace(viewRef); roomRef = viewRef = XR_NULL_HANDLE; session = XR_NULL_HANDLE; }
    return rDestroySession(s);
}
XrResult XRAPI_CALL uLocateHandJointsEXT(XrHandTrackerEXT t, const XrHandJointsLocateInfoEXT *info, XrHandJointLocationsEXT *out) {
    if (!rLocateHandJoints) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrResult r = rLocateHandJoints(t, info, out); if (XR_FAILED(r)) return r;
    for (XrBaseOutStructure *n = (XrBaseOutStructure *)out->next; n; n = n->next) {
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
    E("xrLocateSpace", uLocateSpace), E("xrDestroySpace", uDestroySpace), E("xrWaitFrame", uWaitFrame), E("xrEndFrame", uEndFrame), E("xrPollEvent", uPollEvent),
    E("xrLocateHandJointsEXT", uLocateHandJointsEXT),
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
        if (strcmp(name, "xrLocateHandJointsEXT") == 0 && !rLocateHandJoints) break;
        *fn = e.fn; return XR_SUCCESS;
    }
    // asked before the instance exists (the loader looks the layer over first): nothing below us yet
    if (!nextGipa) { *fn = nullptr; return XR_ERROR_FUNCTION_UNSUPPORTED; }
    return nextGipa(i, name, fn);
}
XrResult XRAPI_CALL uCreateApiLayerInstance(const XrInstanceCreateInfo *info, const XrApiLayerCreateInfo *layerInfo, XrInstance *out) {
    if (!layerInfo || !layerInfo->nextInfo) return XR_ERROR_INITIALIZATION_FAILED;
    // our extensions are ours: the runtime is not asked for them
    std::vector<const char *> ext;
    for (uint32_t i = 0; i < info->enabledExtensionCount; i++) { bool mine = false; for (auto o : OURS) if (strcmp(o, info->enabledExtensionNames[i]) == 0) mine = true; if (!mine) ext.push_back(info->enabledExtensionNames[i]); }
    XrInstanceCreateInfo ci = *info; ci.enabledExtensionCount = (uint32_t)ext.size(); ci.enabledExtensionNames = ext.data();
    XrApiLayerCreateInfo li = *layerInfo; li.nextInfo = layerInfo->nextInfo->next;
    nextGipa = layerInfo->nextInfo->nextGetInstanceProcAddr;
    XrResult r = layerInfo->nextInfo->nextCreateApiLayerInstance(&ci, &li, out);
    if (XR_FAILED(r)) return r;
    instance = *out;
#define R(n, v) nextGipa(instance, n, (PFN_xrVoidFunction *)&v)
    R("xrDestroyInstance", rDestroyInstance); R("xrGetSystemProperties", rGetSystemProperties); R("xrCreateSession", rCreateSession); R("xrDestroySession", rDestroySession);
    R("xrCreateReferenceSpace", rCreateReferenceSpace); R("xrDestroySpace", rDestroySpace); R("xrLocateSpace", rLocateSpace); R("xrPollEvent", rPollEvent);
    R("xrWaitFrame", rWaitFrame); R("xrEndFrame", rEndFrame);
    rLocateHandJoints = nullptr; R("xrLocateHandJointsEXT", rLocateHandJoints);
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
extern "C" __attribute__((visibility("default"))) void uxrMetaLayerStats(int *packets, int *dropped, int *alignedOut, int *entityCount) {
    std::lock_guard<std::mutex> g(lock); *packets = roomPackets; *dropped = passthroughLayersDropped; *alignedOut = aligned ? 1 : 0; *entityCount = (int)entities.size();
}
