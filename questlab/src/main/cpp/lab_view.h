// Ultra XR: the parts of the Quest API test that draw and that read controllers. Included by lab.cpp.
// SPDX-License-Identifier: GPL-3.0-or-later
//
//   input      an action set bound the way Quest games bind it (the Touch controller profile), read every frame
//   room view  OpenGL ES: the scene's walls, floor and tables drawn as grids where the Scene API says they are, so a
//              wearer sees whether they lie on the real ones; two small cubes a wall, one in front (white, must be
//              seen) and one behind (red, must be hidden by the environment depth picture); hands and aim rays
//   Vulkan     a session on Vulkan with a projection layer cleared to a tint an eye (left red, right blue)

// ---------------------------------------------------------------- input, as a Quest game binds it
static XrActionSet actionSet = XR_NULL_HANDLE; static XrAction actTrigger, actAim, actGrip, actPrimary;
static XrPath handPath[2]; static XrSpace aimSpace[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE}, gripSpace[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
static float triggerNow[2], triggerMax[2]; static int triggerPresses[2], primaryPresses[2], aimLocated[2], syncs, syncFails; static bool triggerDown[2], primaryDown[2];
static XrPosef aimPose[2]; static bool aimOk[2];

static void setupInput() {
    XrActionSetCreateInfo sc{XR_TYPE_ACTION_SET_CREATE_INFO}; strcpy(sc.actionSetName, "lab"); strcpy(sc.localizedActionSetName, "Lab");
    XrResult r = xrCreateActionSet(inst, &sc, &actionSet); if (!check(XR_SUCCEEDED(r), "an action set", "%s", res(r))) return;
    xrStringToPath(inst, "/user/hand/left", &handPath[0]); xrStringToPath(inst, "/user/hand/right", &handPath[1]);
    auto action = [&](const char *name, XrActionType type, XrAction *out) {
        XrActionCreateInfo ac{XR_TYPE_ACTION_CREATE_INFO}; strcpy(ac.actionName, name); strcpy(ac.localizedActionName, name); ac.actionType = type; ac.countSubactionPaths = 2; ac.subactionPaths = handPath;
        return xrCreateAction(actionSet, &ac, out);
    };
    action("trigger", XR_ACTION_TYPE_FLOAT_INPUT, &actTrigger); action("aim", XR_ACTION_TYPE_POSE_INPUT, &actAim); action("grip", XR_ACTION_TYPE_POSE_INPUT, &actGrip); action("primary", XR_ACTION_TYPE_BOOLEAN_INPUT, &actPrimary);
    auto path = [&](const char *s) { XrPath p = XR_NULL_PATH; xrStringToPath(inst, s, &p); return p; };
    // the profile every Quest game suggests
    std::vector<XrActionSuggestedBinding> touch = {
        {actTrigger, path("/user/hand/left/input/trigger/value")}, {actTrigger, path("/user/hand/right/input/trigger/value")},
        {actAim, path("/user/hand/left/input/aim/pose")}, {actAim, path("/user/hand/right/input/aim/pose")},
        {actGrip, path("/user/hand/left/input/grip/pose")}, {actGrip, path("/user/hand/right/input/grip/pose")},
        {actPrimary, path("/user/hand/left/input/x/click")}, {actPrimary, path("/user/hand/right/input/a/click")}};
    XrInteractionProfileSuggestedBinding sb{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING}; sb.interactionProfile = path("/interaction_profiles/oculus/touch_controller"); sb.countSuggestedBindings = (uint32_t)touch.size(); sb.suggestedBindings = touch.data();
    r = xrSuggestInteractionProfileBindings(inst, &sb); check(XR_SUCCEEDED(r), "bindings for the Touch controller profile are accepted", "%s", res(r));
    std::vector<XrActionSuggestedBinding> simple = {
        {actTrigger, path("/user/hand/left/input/select/click")}, {actTrigger, path("/user/hand/right/input/select/click")},
        {actAim, path("/user/hand/left/input/aim/pose")}, {actAim, path("/user/hand/right/input/aim/pose")},
        {actGrip, path("/user/hand/left/input/grip/pose")}, {actGrip, path("/user/hand/right/input/grip/pose")}};
    sb.interactionProfile = path("/interaction_profiles/khr/simple_controller"); sb.countSuggestedBindings = (uint32_t)simple.size(); sb.suggestedBindings = simple.data();
    r = xrSuggestInteractionProfileBindings(inst, &sb); check(XR_SUCCEEDED(r), "bindings for the simple controller profile are accepted", "%s", res(r));
    XrSessionActionSetsAttachInfo at{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO}; at.countActionSets = 1; at.actionSets = &actionSet;
    r = xrAttachSessionActionSets(sess, &at); check(XR_SUCCEEDED(r), "the action set attaches to the session", "%s", res(r));
    for (int h = 0; h < 2; h++) {
        XrActionSpaceCreateInfo as{XR_TYPE_ACTION_SPACE_CREATE_INFO}; as.poseInActionSpace = {{0, 0, 0, 1}, {0, 0, 0}}; as.subactionPath = handPath[h];
        as.action = actAim; xrCreateActionSpace(sess, &as, &aimSpace[h]); as.action = actGrip; xrCreateActionSpace(sess, &as, &gripSpace[h]);
    }
}
static void readInput() {      // every frame, as a game does
    if (actionSet == XR_NULL_HANDLE || state != XR_SESSION_STATE_FOCUSED) return;
    XrActiveActionSet active{actionSet, XR_NULL_PATH}; XrActionsSyncInfo si{XR_TYPE_ACTIONS_SYNC_INFO}; si.countActiveActionSets = 1; si.activeActionSets = &active;
    XrResult r = xrSyncActions(sess, &si); syncs++; if (XR_FAILED(r)) { syncFails++; return; }
    for (int h = 0; h < 2; h++) {
        XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO}; gi.subactionPath = handPath[h];
        XrActionStateFloat f{XR_TYPE_ACTION_STATE_FLOAT}; gi.action = actTrigger;
        if (XR_SUCCEEDED(xrGetActionStateFloat(sess, &gi, &f)) && f.isActive) { triggerNow[h] = f.currentState; if (f.currentState > triggerMax[h]) triggerMax[h] = f.currentState; bool d = f.currentState > 0.7f; if (d && !triggerDown[h]) triggerPresses[h]++; if (d || f.currentState < 0.3f) triggerDown[h] = d; } else triggerNow[h] = 0;
        XrActionStateBoolean b{XR_TYPE_ACTION_STATE_BOOLEAN}; gi.action = actPrimary;
        if (XR_SUCCEEDED(xrGetActionStateBoolean(sess, &gi, &b)) && b.isActive) { if (b.currentState && !primaryDown[h]) primaryPresses[h]++; primaryDown[h] = b.currentState; }
        XrSpaceLocation l{XR_TYPE_SPACE_LOCATION}; aimOk[h] = aimSpace[h] != XR_NULL_HANDLE && frameTime && XR_SUCCEEDED(xrLocateSpace(aimSpace[h], local, frameTime, &l)) && (l.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT);
        if (aimOk[h]) { aimPose[h] = l.pose; aimLocated[h]++; }
    }
}
static void reportInput() {
    if (actionSet == XR_NULL_HANDLE) return;
    for (int h = 0; h < 2; h++) {
        XrInteractionProfileState ps{XR_TYPE_INTERACTION_PROFILE_STATE}; char name[XR_MAX_PATH_LENGTH] = "(none)"; uint32_t n = 0;
        if (XR_SUCCEEDED(xrGetCurrentInteractionProfile(sess, handPath[h], &ps)) && ps.interactionProfile != XR_NULL_PATH) xrPathToString(inst, ps.interactionProfile, sizeof name, &n, name);
        line("info  %s controller: profile %s; aim pose located in %d of %d readings; trigger pressed %d times (strongest %.2f); %s button pressed %d times",
             h ? "right" : "left", name, aimLocated[h], syncs, triggerPresses[h], triggerMax[h], h ? "A" : "X", primaryPresses[h]);
    }
    check(syncs > 0 && syncFails == 0, "xrSyncActions succeeds every frame once the session has focus", "%d of %d failed", syncFails, syncs);
}

// ---------------------------------------------------------------- small maths for drawing
struct M4 { float m[16]; };      // column-major, as OpenGL takes it
static M4 mmul(const M4 &a, const M4 &b) { M4 r; for (int c = 0; c < 4; c++) for (int w = 0; w < 4; w++) { float s = 0; for (int k = 0; k < 4; k++) s += a.m[k * 4 + w] * b.m[c * 4 + k]; r.m[c * 4 + w] = s; } return r; }
static void qrot(const XrQuaternionf &q, const float v[3], float o[3]) {
    float ux = q.x, uy = q.y, uz = q.z, s = q.w; float c1[3] = {uy * v[2] - uz * v[1], uz * v[0] - ux * v[2], ux * v[1] - uy * v[0]};
    float c2[3] = {uy * c1[2] - uz * c1[1], uz * c1[0] - ux * c1[2], ux * c1[1] - uy * c1[0]};
    for (int i = 0; i < 3; i++) o[i] = v[i] + 2 * (s * c1[i] + c2[i]);
}
static XrVector3f at(const XrPosef &p, float x, float y, float z) { float v[3] = {x, y, z}, o[3]; qrot(p.orientation, v, o); return {p.position.x + o[0], p.position.y + o[1], p.position.z + o[2]}; }
static M4 viewMatrix(const XrPosef &p) {
    float X[3], Y[3], Z[3], ex[3] = {1, 0, 0}, ey[3] = {0, 1, 0}, ez[3] = {0, 0, 1}; qrot(p.orientation, ex, X); qrot(p.orientation, ey, Y); qrot(p.orientation, ez, Z);
    const float t[3] = {p.position.x, p.position.y, p.position.z}; M4 v{};
    v.m[0] = X[0]; v.m[4] = X[1]; v.m[8] = X[2]; v.m[12] = -(X[0] * t[0] + X[1] * t[1] + X[2] * t[2]);
    v.m[1] = Y[0]; v.m[5] = Y[1]; v.m[9] = Y[2]; v.m[13] = -(Y[0] * t[0] + Y[1] * t[1] + Y[2] * t[2]);
    v.m[2] = Z[0]; v.m[6] = Z[1]; v.m[10] = Z[2]; v.m[14] = -(Z[0] * t[0] + Z[1] * t[1] + Z[2] * t[2]); v.m[15] = 1; return v;
}
static M4 projMatrix(const XrFovf &f, float n) {      // far plane at infinity
    float l = std::tan(f.angleLeft), r = std::tan(f.angleRight), d = std::tan(f.angleDown), u = std::tan(f.angleUp); M4 p{};
    p.m[0] = 2 / (r - l); p.m[5] = 2 / (u - d); p.m[8] = (r + l) / (r - l); p.m[9] = (u + d) / (u - d); p.m[10] = -1; p.m[11] = -1; p.m[14] = -2 * n; return p;
}

// ---------------------------------------------------------------- the scene as the test read it
struct ScenePlane { XrSpace space; XrRect2Df rect; int kind; XrPosef pose; bool ok; };      // kind: 0 floor, 1 ceiling, 2 wall, 3 table
static std::vector<ScenePlane> scene;
static XrSpace thisRunAnchor = XR_NULL_HANDLE; static XrHandTrackerEXT viewTrackers[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};

// ---------------------------------------------------------------- the room view (OpenGL ES)
struct EyeChain { XrSwapchain sc = XR_NULL_HANDLE; std::vector<XrSwapchainImageOpenGLESKHR> img; std::vector<GLuint> fbo; int w = 0, h = 0; };
static EyeChain eyes[2]; static GLuint prog = 0, vbo = 0, vao = 0; static GLint uVp, uV, uCol, uOcc, uEye, uSize;
static XrCompositionLayerProjection projLayer{XR_TYPE_COMPOSITION_LAYER_PROJECTION}; static XrCompositionLayerProjectionView projViews[2];
static PFN_xrAcquireEnvironmentDepthImageMETA viewDepthAcquire = nullptr; static XrEnvironmentDepthProviderMETA viewDepth = XR_NULL_HANDLE; static GLuint viewDepthTex = 0;
static int drawn = 0, behindSeen = 0, behindHidden = 0, frontSeen = 0, frontHidden = 0, wallsInView = 0, depthFrames = 0; static std::string ppmPath; static bool ppmSaved = false;
struct Batch { GLenum mode; int first, count; float c[4]; int occlude; int expect; };      // expect: 1 must be hidden by the depth picture, 2 must be seen, 0 not judged
static std::vector<float> vtx; static std::vector<Batch> batches;
static void lineTo(XrVector3f a, XrVector3f b) { vtx.insert(vtx.end(), {a.x, a.y, a.z, b.x, b.y, b.z}); }
static void batch(GLenum mode, int first, float r, float g, float b, float a, int occ, int expect = 0) { Batch k{mode, first, (int)vtx.size() / 3 - first, {r, g, b, a}, occ, expect}; if (k.count > 0) batches.push_back(k); }
static void cube(XrVector3f c, float s) {
    const float P[8][3] = {{-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1}, {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}};
    const int I[36] = {0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1, 3, 2, 6, 3, 6, 7, 0, 3, 7, 0, 7, 4, 1, 5, 6, 1, 6, 2};
    for (int i : I) vtx.insert(vtx.end(), {c.x + P[i][0] * s, c.y + P[i][1] * s, c.z + P[i][2] * s});
}
static GLuint shader(GLenum type, const char *src) { GLuint s = glCreateShader(type); glShaderSource(s, 1, &src, nullptr); glCompileShader(s); GLint ok = 0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok); if (!ok) { char log[400] = ""; glGetShaderInfoLog(s, sizeof log, nullptr, log); line("note  shader: %s", log); } return s; }

static bool setupRoomView(XrSystemId sys) {
    uint32_t n = 0; XrViewConfigurationView vc[2] = {{XR_TYPE_VIEW_CONFIGURATION_VIEW}, {XR_TYPE_VIEW_CONFIGURATION_VIEW}};
    XrResult r = xrEnumerateViewConfigurationViews(inst, sys, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 2, &n, vc);
    if (!check(XR_SUCCEEDED(r) && n == 2, "two views to draw (stereo)", "%s, %ux%u each", res(r), vc[0].recommendedImageRectWidth, vc[0].recommendedImageRectHeight)) return false;
    int64_t fmts[64]; uint32_t nf = 0; xrEnumerateSwapchainFormats(sess, 64, &nf, fmts); int64_t fmt = nf ? fmts[0] : 0;
    for (uint32_t i = 0; i < nf; i++) if (fmts[i] == GL_SRGB8_ALPHA8) fmt = fmts[i];
    bool ok = true;
    for (int e = 0; e < 2; e++) {
        XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO}; ci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT; ci.format = fmt;
        ci.width = vc[e].recommendedImageRectWidth; ci.height = vc[e].recommendedImageRectHeight; ci.sampleCount = 1; ci.faceCount = 1; ci.arraySize = 1; ci.mipCount = 1;
        r = xrCreateSwapchain(sess, &ci, &eyes[e].sc); if (XR_FAILED(r)) { ok = false; break; }
        uint32_t ni = 0; xrEnumerateSwapchainImages(eyes[e].sc, 0, &ni, nullptr); eyes[e].img.assign(ni, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        xrEnumerateSwapchainImages(eyes[e].sc, ni, &ni, (XrSwapchainImageBaseHeader *)eyes[e].img.data()); eyes[e].w = (int)ci.width; eyes[e].h = (int)ci.height;
        for (auto &im : eyes[e].img) { GLuint f = 0; glGenFramebuffers(1, &f); glBindFramebuffer(GL_FRAMEBUFFER, f); glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, im.image, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) ok = false; eyes[e].fbo.push_back(f); }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (!check(ok, "an OpenGL ES colour swapchain an eye, drawable", "%s, format 0x%llx, %dx%d, %u images", res(r), (unsigned long long)fmt, eyes[0].w, eyes[0].h, (unsigned)eyes[0].img.size())) return false;
    const char *vs = "#version 300 es\nlayout(location=0) in vec3 p; uniform mat4 vp; uniform mat4 v; out float ez;\nvoid main(){ vec4 e = v*vec4(p,1.0); ez = -e.z; gl_Position = vp*vec4(p,1.0); gl_PointSize = 6.0; }\n";
    // the environment depth picture holds 1 - near/z for each eye, the same eye and time as this frame: a fragment
    // further away than what the picture has there is behind the room, and is not drawn
    const char *fs = "#version 300 es\nprecision highp float; precision highp sampler2DArray;\nuniform vec4 col; uniform int occ; uniform int eye; uniform vec2 size; uniform sampler2DArray dep; in float ez; out vec4 o;\n"
                     "void main(){ if (occ == 1) { float d = texture(dep, vec3(gl_FragCoord.xy / size, float(eye))).r; float z = 0.1 / max(1.0 - d, 1e-6); if (d < 0.99999 && ez > z + 0.05) discard; } o = col; }\n";
    prog = glCreateProgram(); glAttachShader(prog, shader(GL_VERTEX_SHADER, vs)); glAttachShader(prog, shader(GL_FRAGMENT_SHADER, fs)); glLinkProgram(prog);
    GLint linked = 0; glGetProgramiv(prog, GL_LINK_STATUS, &linked); if (!check(linked, "the drawing program links")) return false;
    uVp = glGetUniformLocation(prog, "vp"); uV = glGetUniformLocation(prog, "v"); uCol = glGetUniformLocation(prog, "col"); uOcc = glGetUniformLocation(prog, "occ"); uEye = glGetUniformLocation(prog, "eye"); uSize = glGetUniformLocation(prog, "size");
    glGenVertexArrays(1, &vao); glGenBuffers(1, &vbo);
    return true;
}
// ---- room set-up: the wearer points at each corner where a wall meets the floor and pinches, going round the room once;
// a pinch on the first corner again closes the room. Each corner is where the pointing ray meets the floor.
static bool setupMode = false, setupClosed = false; static int setupSaved = -1; static std::vector<XrVector3f> setupCorners;
static bool setupDown[2] = {false, false}; static double setupLastAdd = 0; static XrTime setupClosedAt = 0;
static double viewNow() { timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return ts.tv_sec + ts.tv_nsec / 1e9; }
static void buildSetup(XrTime t, const XrPosef &head) {
    float floorY = -1.6f; for (auto &p : scene) if (p.ok && p.kind == 0) floorY = p.pose.position.y;
    const float top = 2.3f;
    // A pinch of either hand (or a tap on the phone's touchpad, which is the right hand's trigger) takes the place.
    bool pressed = false; for (int h = 0; h < 2; h++) { bool down = triggerNow[h] > 0.5f; if (down && !setupDown[h]) pressed = true; setupDown[h] = down; }
    // The place is where the wearer looks: the middle of the view, followed down to the floor. The head is tracked
    // well everywhere; a hand pointing at the floor is below what the cameras see of it.
    for (int once = 0; once < 1 && !setupClosed; once++) {
        XrVector3f o = head.position, e = at(head, 0, 0, -1), d{e.x - o.x, e.y - o.y, e.z - o.z};
        if (d.y > -0.05f) continue;                                    // looking level or up: no place on the floor
        float k = (floorY - o.y) / d.y; if (k < 0.3f || k > 9) continue;
        XrVector3f hit{o.x + d.x * k, floorY, o.z + d.z * k};
        int first = (int)vtx.size() / 3;                               // the place looked at: a ring on the floor with a cross in it, and a post
        for (int i = 0; i < 24; i++) { float a0 = i * 0.2618f, a1 = (i + 1) * 0.2618f; lineTo({hit.x + 0.15f * std::cos(a0), floorY, hit.z + 0.15f * std::sin(a0)}, {hit.x + 0.15f * std::cos(a1), floorY, hit.z + 0.15f * std::sin(a1)}); }
        lineTo({hit.x - 0.15f, floorY, hit.z}, {hit.x + 0.15f, floorY, hit.z}); lineTo({hit.x, floorY, hit.z - 0.15f}, {hit.x, floorY, hit.z + 0.15f}); lineTo(hit, {hit.x, floorY + 1.0f, hit.z});
        if (!setupCorners.empty()) lineTo(setupCorners.back(), hit);   // and the wall it would make from the last corner
        batch(GL_LINES, first, 1, 1, 1, 1, 0);
        if (pressed && viewNow() - setupLastAdd > 0.7) {
            setupLastAdd = viewNow();
            XrVector3f f0 = setupCorners.empty() ? hit : setupCorners[0];
            if (setupCorners.size() >= 3 && std::hypot(hit.x - f0.x, hit.z - f0.z) < 0.4f) { setupClosed = true; setupClosedAt = t; __android_log_print(ANDROID_LOG_INFO, "QuestLab", "CUE room closed with %d corners", (int)setupCorners.size()); }
            else if (setupCorners.empty() || std::hypot(hit.x - setupCorners.back().x, hit.z - setupCorners.back().z) > 0.4f) { setupCorners.push_back(hit); __android_log_print(ANDROID_LOG_INFO, "QuestLab", "CUE corner %d marked at %.2f %.2f", (int)setupCorners.size(), hit.x, hit.z); }
        }
    }
    int first = (int)vtx.size() / 3; size_t n = setupCorners.size();
    for (size_t i = 0; i < n; i++) {
        XrVector3f a = setupCorners[i]; lineTo(a, {a.x, floorY + top, a.z});
        if (i + 1 < n || setupClosed) { XrVector3f b = setupCorners[(i + 1) % n]; lineTo(a, b); lineTo({a.x, floorY + top, a.z}, {b.x, floorY + top, b.z}); lineTo({a.x, floorY + top / 2, a.z}, {b.x, floorY + top / 2, b.z}); }
    }
    if (setupClosed) batch(GL_LINES, first, 0.2f, 1.0f, 0.3f, 1, 0); else batch(GL_LINES, first, 1.0f, 0.85f, 0.1f, 1, 0);
    if (n) { first = (int)vtx.size() / 3; XrVector3f a = setupCorners[0]; lineTo({a.x - 0.2f, floorY, a.z}, {a.x + 0.2f, floorY, a.z}); lineTo({a.x, floorY, a.z - 0.2f}, {a.x, floorY, a.z + 0.2f}); batch(GL_LINES, first, 0.2f, 1.0f, 0.3f, 1, 0); }   // the first corner: where to close
}
static void buildGeometry(XrTime t, const XrPosef &head) {
    vtx.clear(); batches.clear(); wallsInView = 0;
    static int age = 0; bool relocate = (age++ % 20) == 0;
    for (auto &p : scene) {
        if (relocate) { XrSpaceLocation l{XR_TYPE_SPACE_LOCATION}; p.ok = XR_SUCCEEDED(xrLocateSpace(p.space, local, t, &l)) && (l.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT); if (p.ok) p.pose = l.pose; }
        if (!p.ok || p.kind == 1) continue;
        int first = (int)vtx.size() / 3; float x0 = p.rect.offset.x, y0 = p.rect.offset.y, x1 = x0 + p.rect.extent.width, y1 = y0 + p.rect.extent.height, step = p.kind == 0 ? 0.5f : 0.25f;
        for (float x = x0; x < x1 + 1e-3f; x += step) lineTo(at(p.pose, x, y0, 0), at(p.pose, x, y1, 0));
        for (float y = y0; y < y1 + 1e-3f; y += step) lineTo(at(p.pose, x0, y, 0), at(p.pose, x1, y, 0));
        lineTo(at(p.pose, x1, y0, 0), at(p.pose, x1, y1, 0)); lineTo(at(p.pose, x0, y1, 0), at(p.pose, x1, y1, 0));
        if (p.kind == 0) batch(GL_LINES, first, 0.15f, 0.35f, 1.0f, 1, 0); else if (p.kind == 2) batch(GL_LINES, first, 0.1f, 1.0f, 0.3f, 1, 0); else batch(GL_LINES, first, 1.0f, 0.8f, 0.1f, 1, 0);
    }
    // a wall's two cubes: both go through the depth test; the one in front must survive it, the one behind must not
    for (auto &p : scene) {
        if (!p.ok || p.kind != 2) continue;
        XrVector3f c = p.pose.position, nz = at({p.pose.orientation, {0, 0, 0}}, 0, 0, 1);
        float side = (head.position.x - c.x) * nz.x + (head.position.y - c.y) * nz.y + (head.position.z - c.z) * nz.z >= 0 ? 1.f : -1.f;
        float off = p.rect.extent.width > 1.2f ? 0.4f : p.rect.extent.width / 4;
        int first = (int)vtx.size() / 3; cube(at(p.pose, -off, 0, 0.30f * side), 0.06f); batch(GL_TRIANGLES, first, 1, 1, 1, 1, 1, 2);
        // the cube behind is judged only while the line of sight to it goes through this wall's own rectangle
        XrVector3f B = at(p.pose, off, 0, -0.50f * side), H = head.position; float den = (B.x - H.x) * nz.x + (B.y - H.y) * nz.y + (B.z - H.z) * nz.z; int expect = 0;
        if (std::fabs(den) > 1e-4f) { float s = ((c.x - H.x) * nz.x + (c.y - H.y) * nz.y + (c.z - H.z) * nz.z) / den;
            if (s > 0 && s < 1) { XrVector3f P{H.x + s * (B.x - H.x) - c.x, H.y + s * (B.y - H.y) - c.y, H.z + s * (B.z - H.z) - c.z}, X = at({p.pose.orientation, {0, 0, 0}}, 1, 0, 0), Y = at({p.pose.orientation, {0, 0, 0}}, 0, 1, 0);
                float x = P.x * X.x + P.y * X.y + P.z * X.z, y = P.x * Y.x + P.y * Y.y + P.z * Y.z;
                if (std::fabs(x) < p.rect.extent.width / 2 - 0.08f && std::fabs(y) < p.rect.extent.height / 2 - 0.08f) expect = 1; } }
        first = (int)vtx.size() / 3; cube(B, 0.06f); batch(GL_TRIANGLES, first, 1, 0, 0, 1, 1, expect);
    }
    // the anchor made in this run: a cross
    if (thisRunAnchor != XR_NULL_HANDLE) { XrSpaceLocation l{XR_TYPE_SPACE_LOCATION};
        if (XR_SUCCEEDED(xrLocateSpace(thisRunAnchor, local, t, &l)) && (l.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT)) { int first = (int)vtx.size() / 3; XrPosef a = l.pose;
            lineTo(at(a, -0.05f, 0, 0), at(a, 0.05f, 0, 0)); lineTo(at(a, 0, -0.05f, 0), at(a, 0, 0.05f, 0)); lineTo(at(a, 0, 0, -0.05f), at(a, 0, 0, 0.05f)); batch(GL_LINES, first, 1, 0.5f, 0, 1, 0); } }
    // hands: a dot a joint; and the aim ray of each controller, brighter while its trigger is pulled
    auto lHand = fn<PFN_xrLocateHandJointsEXT>("xrLocateHandJointsEXT");
    for (int h = 0; h < 2 && lHand; h++) {
        if (viewTrackers[h] == XR_NULL_HANDLE) continue;
        XrHandJointLocationEXT j[XR_HAND_JOINT_COUNT_EXT]; XrHandJointLocationsEXT hl{XR_TYPE_HAND_JOINT_LOCATIONS_EXT}; hl.jointCount = XR_HAND_JOINT_COUNT_EXT; hl.jointLocations = j;
        XrHandJointsLocateInfoEXT hi{XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT}; hi.baseSpace = local; hi.time = t;
        if (XR_FAILED(lHand(viewTrackers[h], &hi, &hl)) || !hl.isActive) continue;
        int first = (int)vtx.size() / 3; for (auto &k : j) vtx.insert(vtx.end(), {k.pose.position.x, k.pose.position.y, k.pose.position.z});
        batch(GL_POINTS, first, h ? 1.0f : 0.1f, 0.9f, h ? 0.2f : 1.0f, 1, 0);      // left cyan, right yellow
    }
    if (setupMode) buildSetup(t, head);
    for (int h = 0; h < 2; h++) if (aimOk[h]) { int first = (int)vtx.size() / 3; lineTo(aimPose[h].position, at(aimPose[h], 0, 0, setupMode ? -6.0f : -1.5f)); float b = triggerNow[h] > 0.5f ? 1.0f : 0.45f; batch(GL_LINES, first, b, 0.1f * b, b, 1, 0); }
}
static const XrCompositionLayerBaseHeader *drawRoomView(XrTime t) {
    XrViewLocateInfo vi{XR_TYPE_VIEW_LOCATE_INFO}; vi.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO; vi.displayTime = t; vi.space = local;
    XrViewState vs{XR_TYPE_VIEW_STATE}; XrView v[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}}; uint32_t n = 0;
    if (XR_FAILED(xrLocateViews(sess, &vi, &vs, 2, &n, v)) || n != 2 || !(vs.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT)) return nullptr;
    bool haveDepth = false;
    if (viewDepth != XR_NULL_HANDLE && viewDepthAcquire) { XrEnvironmentDepthImageAcquireInfoMETA da{XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_ACQUIRE_INFO_META}; da.space = local; da.displayTime = t;
        XrEnvironmentDepthImageMETA di{XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_META}; di.views[0].type = di.views[1].type = XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_VIEW_META; haveDepth = viewDepthAcquire(viewDepth, &da, &di) == XR_SUCCESS; if (haveDepth) depthFrames++; }
    buildGeometry(t, v[0].pose);
    glUseProgram(prog); glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo); glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)(vtx.size() * sizeof(float)), vtx.data(), GL_STREAM_DRAW);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, nullptr);
    glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glDisable(GL_BLEND); glLineWidth(3);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D_ARRAY, viewDepthTex);
    for (int e = 0; e < 2; e++) {
        uint32_t idx = 0; XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO}; if (XR_FAILED(xrAcquireSwapchainImage(eyes[e].sc, &ai, &idx))) return nullptr;
        XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO}; wi.timeout = XR_INFINITE_DURATION; xrWaitSwapchainImage(eyes[e].sc, &wi);
        glBindFramebuffer(GL_FRAMEBUFFER, eyes[e].fbo[idx]); glViewport(0, 0, eyes[e].w, eyes[e].h); glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
        M4 view = viewMatrix(v[e].pose), vp = mmul(projMatrix(v[e].fov, 0.05f), view);
        glUniformMatrix4fv(uVp, 1, GL_FALSE, vp.m); glUniformMatrix4fv(uV, 1, GL_FALSE, view.m); glUniform1i(uEye, e); glUniform2f(uSize, (float)eyes[e].w, (float)eyes[e].h);
        for (auto &b : batches) { glUniform4fv(uCol, 1, b.c); glUniform1i(uOcc, b.occlude && haveDepth ? 1 : 0); glDrawArrays(b.mode, b.first, b.count); }
        if (e == 0) {
            // what came of each wall's two cubes: the picture's own pixel at the middle of each, when it is in view
            for (auto &b : batches) {
                if (b.mode != GL_TRIANGLES || !haveDepth || b.expect == 0) continue;
                float c[3] = {0, 0, 0}; for (int k = 0; k < 36; k++) for (int a = 0; a < 3; a++) c[a] += vtx[(size_t)(b.first + k) * 3 + a] / 36;
                float cx = vp.m[0] * c[0] + vp.m[4] * c[1] + vp.m[8] * c[2] + vp.m[12], cy = vp.m[1] * c[0] + vp.m[5] * c[1] + vp.m[9] * c[2] + vp.m[13], cw = vp.m[3] * c[0] + vp.m[7] * c[1] + vp.m[11] * c[2] + vp.m[15];
                if (cw < 0.3f) continue; int px = (int)((cx / cw * 0.5f + 0.5f) * eyes[e].w), py = (int)((cy / cw * 0.5f + 0.5f) * eyes[e].h);
                if (px < 8 || py < 8 || px >= eyes[e].w - 8 || py >= eyes[e].h - 8) continue;
                unsigned char rgba[4] = {0, 0, 0, 0}; glReadPixels(px, py, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
                bool red = b.expect == 1, lit = rgba[0] > 128 && (red ? rgba[1] < 90 : rgba[1] > 128);
                if (red) { (lit ? behindSeen : behindHidden)++; wallsInView++; } else (lit ? frontSeen : frontHidden)++;
            }
            if (!ppmSaved && drawn == 240 && !ppmPath.empty()) {      // one picture of the left eye, four seconds in, for the record
                std::vector<unsigned char> px((size_t)eyes[e].w * eyes[e].h * 4); glPixelStorei(GL_PACK_ALIGNMENT, 1); glReadPixels(0, 0, eyes[e].w, eyes[e].h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
                if (FILE *f = fopen(ppmPath.c_str(), "wb")) { fprintf(f, "P6\n%d %d\n255\n", eyes[e].w, eyes[e].h); for (int y = eyes[e].h - 1; y >= 0; y--) for (int x = 0; x < eyes[e].w; x++) fwrite(&px[((size_t)y * eyes[e].w + x) * 4], 1, 3, f); fclose(f); ppmSaved = true; }
            }
        }
        XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO}; xrReleaseSwapchainImage(eyes[e].sc, &ri);
        projViews[e] = {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}; projViews[e].pose = v[e].pose; projViews[e].fov = v[e].fov; projViews[e].subImage.swapchain = eyes[e].sc; projViews[e].subImage.imageRect = {{0, 0}, {eyes[e].w, eyes[e].h}};
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0); drawn++;
    projLayer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT; projLayer.space = local; projLayer.viewCount = 2; projLayer.views = projViews;
    return (const XrCompositionLayerBaseHeader *)&projLayer;
}

// ---------------------------------------------------------------- a session on Vulkan
static VkInstance vkInst = VK_NULL_HANDLE; static VkPhysicalDevice vkPhys = VK_NULL_HANDLE; static VkDevice vkDev = VK_NULL_HANDLE; static uint32_t vkFamily = 0; static VkQueue vkQueue = VK_NULL_HANDLE;
static VkCommandPool vkPool = VK_NULL_HANDLE; static VkCommandBuffer vkCmd = VK_NULL_HANDLE; static VkFence vkFence = VK_NULL_HANDLE;
struct VkEye { XrSwapchain sc = XR_NULL_HANDLE; std::vector<XrSwapchainImageVulkan2KHR> img; int w = 0, h = 0; };
static VkEye vkEyes[2]; static int vkDrawn = 0, vkFailed = 0;

static XrResult vulkanSession(XrSystemId sys) {
    auto req = fn<PFN_xrGetVulkanGraphicsRequirements2KHR>("xrGetVulkanGraphicsRequirements2KHR"); auto mkInst = fn<PFN_xrCreateVulkanInstanceKHR>("xrCreateVulkanInstanceKHR");
    auto getDev = fn<PFN_xrGetVulkanGraphicsDevice2KHR>("xrGetVulkanGraphicsDevice2KHR"); auto mkDev = fn<PFN_xrCreateVulkanDeviceKHR>("xrCreateVulkanDeviceKHR");
    if (!check(req && mkInst && getDev && mkDev, "the XR_KHR_vulkan_enable2 calls exist")) return XR_ERROR_FUNCTION_UNSUPPORTED;
    XrGraphicsRequirementsVulkan2KHR gr{XR_TYPE_GRAPHICS_REQUIREMENTS_VULKAN2_KHR}; req(inst, sys, &gr);
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName = "QuestApiLab"; app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo = &app;
    XrVulkanInstanceCreateInfoKHR xi{XR_TYPE_VULKAN_INSTANCE_CREATE_INFO_KHR}; xi.systemId = sys; xi.pfnGetInstanceProcAddr = &vkGetInstanceProcAddr; xi.vulkanCreateInfo = &ici;
    VkResult vr = VK_SUCCESS; XrResult r = mkInst(inst, &xi, &vkInst, &vr); if (!check(XR_SUCCEEDED(r) && vr == VK_SUCCESS, "xrCreateVulkanInstanceKHR", "%s, Vulkan result %d", res(r), (int)vr)) return XR_FAILED(r) ? r : XR_ERROR_RUNTIME_FAILURE;
    XrVulkanGraphicsDeviceGetInfoKHR gi{XR_TYPE_VULKAN_GRAPHICS_DEVICE_GET_INFO_KHR}; gi.systemId = sys; gi.vulkanInstance = vkInst;
    r = getDev(inst, &gi, &vkPhys); if (!check(XR_SUCCEEDED(r), "xrGetVulkanGraphicsDevice2KHR", "%s", res(r))) return r;
    uint32_t nq = 0; vkGetPhysicalDeviceQueueFamilyProperties(vkPhys, &nq, nullptr); std::vector<VkQueueFamilyProperties> qf(nq); vkGetPhysicalDeviceQueueFamilyProperties(vkPhys, &nq, qf.data());
    for (uint32_t i = 0; i < nq; i++) if (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { vkFamily = i; break; }
    float prio = 1; VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qi.queueFamilyIndex = vkFamily; qi.queueCount = 1; qi.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qi;
    XrVulkanDeviceCreateInfoKHR xd{XR_TYPE_VULKAN_DEVICE_CREATE_INFO_KHR}; xd.systemId = sys; xd.pfnGetInstanceProcAddr = &vkGetInstanceProcAddr; xd.vulkanPhysicalDevice = vkPhys; xd.vulkanCreateInfo = &dci;
    r = mkDev(inst, &xd, &vkDev, &vr); if (!check(XR_SUCCEEDED(r) && vr == VK_SUCCESS, "xrCreateVulkanDeviceKHR", "%s, Vulkan result %d", res(r), (int)vr)) return XR_FAILED(r) ? r : XR_ERROR_RUNTIME_FAILURE;
    vkGetDeviceQueue(vkDev, vkFamily, 0, &vkQueue);
    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; pci.queueFamilyIndex = vkFamily; vkCreateCommandPool(vkDev, &pci, nullptr, &vkPool);
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cai.commandPool = vkPool; cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount = 1; vkAllocateCommandBuffers(vkDev, &cai, &vkCmd);
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; vkCreateFence(vkDev, &fci, nullptr, &vkFence);
    XrGraphicsBindingVulkan2KHR gb{XR_TYPE_GRAPHICS_BINDING_VULKAN2_KHR}; gb.instance = vkInst; gb.physicalDevice = vkPhys; gb.device = vkDev; gb.queueFamilyIndex = vkFamily; gb.queueIndex = 0;
    XrSessionCreateInfo si{XR_TYPE_SESSION_CREATE_INFO}; si.next = &gb; si.systemId = sys; return xrCreateSession(inst, &si, &sess);
}
static bool setupVulkanView(XrSystemId sys) {
    uint32_t n = 0; XrViewConfigurationView vc[2] = {{XR_TYPE_VIEW_CONFIGURATION_VIEW}, {XR_TYPE_VIEW_CONFIGURATION_VIEW}}; xrEnumerateViewConfigurationViews(inst, sys, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 2, &n, vc);
    int64_t fmts[64]; uint32_t nf = 0; xrEnumerateSwapchainFormats(sess, 64, &nf, fmts); int64_t fmt = nf ? fmts[0] : 0;
    for (uint32_t i = 0; i < nf; i++) if (fmts[i] == VK_FORMAT_R8G8B8A8_SRGB) fmt = fmts[i];
    XrResult r = XR_SUCCESS; bool ok = n == 2;
    for (int e = 0; e < 2 && ok; e++) {
        XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO}; ci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT; ci.format = fmt;
        ci.width = vc[e].recommendedImageRectWidth; ci.height = vc[e].recommendedImageRectHeight; ci.sampleCount = 1; ci.faceCount = 1; ci.arraySize = 1; ci.mipCount = 1;
        r = xrCreateSwapchain(sess, &ci, &vkEyes[e].sc); if (XR_FAILED(r)) { ok = false; break; }
        uint32_t ni = 0; xrEnumerateSwapchainImages(vkEyes[e].sc, 0, &ni, nullptr); vkEyes[e].img.assign(ni, {XR_TYPE_SWAPCHAIN_IMAGE_VULKAN2_KHR});
        xrEnumerateSwapchainImages(vkEyes[e].sc, ni, &ni, (XrSwapchainImageBaseHeader *)vkEyes[e].img.data()); vkEyes[e].w = (int)ci.width; vkEyes[e].h = (int)ci.height; ok = ni > 0 && vkEyes[e].img[0].image != VK_NULL_HANDLE;
    }
    return check(ok, "a Vulkan colour swapchain an eye", "%s, format %lld, %dx%d, %u images", res(r), (long long)fmt, vkEyes[0].w, vkEyes[0].h, (unsigned)vkEyes[0].img.size());
}
// Each eye's picture is cleared to a dim tint, left red and right blue: a wearer (or a capture) tells from it that
// each eye gets its own picture and which is which.
static const XrCompositionLayerBaseHeader *drawVulkan(XrTime t) {
    XrViewLocateInfo vi{XR_TYPE_VIEW_LOCATE_INFO}; vi.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO; vi.displayTime = t; vi.space = local;
    XrViewState vs{XR_TYPE_VIEW_STATE}; XrView v[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}}; uint32_t n = 0;
    if (XR_FAILED(xrLocateViews(sess, &vi, &vs, 2, &n, v)) || n != 2 || !(vs.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT)) return nullptr;
    for (int e = 0; e < 2; e++) {
        uint32_t idx = 0; XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO}; if (XR_FAILED(xrAcquireSwapchainImage(vkEyes[e].sc, &ai, &idx))) { vkFailed++; return nullptr; }
        XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO}; wi.timeout = XR_INFINITE_DURATION; xrWaitSwapchainImage(vkEyes[e].sc, &wi);
        VkImage image = vkEyes[e].img[idx].image; VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT; vkResetCommandBuffer(vkCmd, 0); vkBeginCommandBuffer(vkCmd, &bi);
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}; b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; b.image = image; b.subresourceRange = range;
        b.oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT; b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(vkCmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
        VkClearColorValue col{}; col.float32[0] = e == 0 ? 0.20f : 0.0f; col.float32[2] = e == 0 ? 0.0f : 0.20f; col.float32[3] = 1.0f;
        vkCmdClearColorImage(vkCmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &col, 1, &range);
        b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL; b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        vkCmdPipelineBarrier(vkCmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
        vkEndCommandBuffer(vkCmd);
        VkSubmitInfo sub{VK_STRUCTURE_TYPE_SUBMIT_INFO}; sub.commandBufferCount = 1; sub.pCommandBuffers = &vkCmd; vkResetFences(vkDev, 1, &vkFence);
        if (vkQueueSubmit(vkQueue, 1, &sub, vkFence) != VK_SUCCESS || vkWaitForFences(vkDev, 1, &vkFence, VK_TRUE, 2000000000ull) != VK_SUCCESS) vkFailed++;
        XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO}; if (XR_FAILED(xrReleaseSwapchainImage(vkEyes[e].sc, &ri))) vkFailed++;
        projViews[e] = {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}; projViews[e].pose = v[e].pose; projViews[e].fov = v[e].fov; projViews[e].subImage.swapchain = vkEyes[e].sc; projViews[e].subImage.imageRect = {{0, 0}, {vkEyes[e].w, vkEyes[e].h}};
    }
    vkDrawn++; projLayer.layerFlags = 0; projLayer.space = local; projLayer.viewCount = 2; projLayer.views = projViews;
    return (const XrCompositionLayerBaseHeader *)&projLayer;
}
