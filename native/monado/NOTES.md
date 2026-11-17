# Monado OpenXR runtime for Ultra XR (`dev.ultraxr`), Android arm64

Built 15 November 2026 from Monado commit `ee03722885e777462708841223b60affe6873ac2` (4 November 2026) with Pocket XR's
patch and two small patches on top. Paths written `monado/...` are in the patched Monado tree, `pocket-xr/...` in
`/home/user/ref/pocket-xr_pocket-xr`, `loader/...` in KhronosGroup/OpenXR-SDK-Source 1.1.65, `broker/...` in
gitlab.freedesktop.org/monado/utilities/openxr-android-broker (commit 7ff16ad8).

Nothing here has run on a phone or an emulator. Section 12 lists what was checked and how.

## 1. What is in this folder

| Path | What |
| --- | --- |
| `jniLibs/arm64-v8a/libopenxr_monado.so` | The runtime that OpenXR applications load (2.0 MB, stripped). BSL-1.0 only. |
| `jniLibs/arm64-v8a/libmonado-service.so` | Monado's server, compositor and the pocketxr driver, loaded in the app's own process (2.2 MB, stripped). |
| `aar/monado-ipc.aar`, `aar/monado-auxiliary.aar` | Monado's Java side: `MonadoImpl`, `Client`, `IMonado`, `PocketFeed`; `MonadoView`, `SystemUiController`, ... |
| `patches/0001-monado-pocketxr.patch` | Pocket XR's patch, unchanged. |
| `patches/0002-ultraxr-build-on-linux.patch` | Build fixes (NDK version property, `ninja.exe`). |
| `patches/0003-ultraxr-hands-aim-recenter.patch` | Hand joints, separate aim pose, one tracking space, origin offset and recentre. GPL-3.0-or-later. |
| `patches/hello_xr-build-here.diff` | The three changes hello_xr needed to build with this SDK. |
| `build.sh` | Rebuilds all of it from a clean clone. Run once end to end into a fresh folder, see section 12. |
| `kotlin/dev/ultraxr/openxr/*.kt` | Drop-in classes: `OpenXrRuntimeService`, `OpenXrGlassesLayer`, `OpenXrBrokerProvider`, `UltraXrFeed`. |
| `kotlin/AndroidManifest-snippet.xml`, `build.gradle-snippet.kts`, `proguard-monado.pro` | What the app's manifest, Gradle file and R8 rules need. |
| `kotlin/reference/PocketFeed.java` | The JNI binding as patched, for reading. It is already inside `monado-ipc.aar`: do not add it to the app. |
| `hello_xr.apk` (= `hello_xr-vulkan-debug.apk`), `hello_xr-opengles-debug.apk` | Khronos hello_xr 1.1.65, debug signed, packages `org.khronos.openxr.hello_xr.vulkan` and `.opengles`. |
| `smoketest/`, `ultraxr-runtime-smoketest-debug.apk` | A minimal host app (package `dev.ultraxr`) made of the drop-in classes and a made-up wearer. It exists to compile the Kotlin and to try the runtime with hello_xr before the real app is ready. Uninstall it before installing the real app: same package, different key. |
| `hosttest/` | A test of the driver on this machine, through Monado's real space overseer and hand tracker. |
| `LICENSES/` | Licence texts and `README.md` saying what is under which. |

## 2. Build

`build.sh` does: clone, `git apply` the three patches, then

```bash
./gradlew --no-daemon --max-workers=2 -Pandroid.injected.build.abi=arm64-v8a \
  -PxrtAndroidPackage=dev.ultraxr -PxrtNdkVersion=27.2.12479018 \
  :src:xrt:targets:openxr_android:externalNativeBuildOutOfProcessRelease \
  :src:xrt:ipc:assembleRelease :src:xrt:auxiliary:assembleRelease
```

and strips the two libraries with `llvm-strip --strip-unneeded`.

What differed from Pocket XR's recipe (`pocket-xr/docs/OPENXR.md` lines 28 to 36), and why:

- **Task.** Pocket XR runs `:src:xrt:targets:openxr_android:assembleOutOfProcessRelease`. Here that fails: Monado's own
  app needs `com.github.markusfisch:BarcodeScannerView:1.6.4` from jitpack.io (`monado/src/xrt/auxiliary/android_cardboard/build.gradle:53`),
  and jitpack.io answers 403 from this machine. I ran it and it fails only at that dependency. The native libraries
  come from the app's CMake build, so `build.sh` runs that task alone (`externalNativeBuildOutOfProcessRelease`): same
  CMake arguments, same libraries, and their sizes match Pocket XR's shipped ones to within 5 KB.
- **`ninja.exe`.** Pocket XR's patch names `ninja.exe` in the Eigen configure step (it was made on Windows). Patch 0002
  adds the extension only on Windows.
- **NDK.** Pocket XR pins 27.0.12077973; 27.2.12479018 is installed. Patch 0002 adds `-PxrtNdkVersion`.
- **Eigen** 5.0.0 comes from gitlab.com at build time, which is reachable. **glslangValidator** came from
  `apt install glslang-tools` (15.1.0). **Build-tools 34.0.0** were fetched by Gradle from dl.google.com.
- Gradle 8.12 (Monado's wrapper), Android Gradle plugin 8.6.0, JDK 21, CMake 3.22.1.
- Parallelism: Gradle `--max-workers=2`, and the whole build runs under `taskset` on two CPUs, because the Android
  Gradle plugin starts Ninja without `-j` and Ninja sizes itself from the CPUs it may use.

**minSdk:** Monado's is 26 (`monado/build.gradle`, `sharedMinSdk = 26`; the ipc module says "needed for ASharedMemory").
The libraries are built with `ANDROID_PLATFORM=26`, target `aarch64-none-linux-android26`. An app with minSdk 29 is fine.

**libc++:** linked statically (`ANDROID_STL=c++_static`, Monado's default). No `libc++_shared.so` is needed. The one in
Pocket XR's `jniLibs` is for its ALVR client library.

### Checked with llvm-readelf

| | LOAD alignment | DT_NEEDED |
| --- | --- | --- |
| `libopenxr_monado.so` | 0x4000 on all three LOAD segments | libvulkan.so, libEGL.so, libandroid.so, liblog.so, libm.so, libdl.so, libc.so |
| `libmonado-service.so` | 0x4000 on all three LOAD segments | libEGL.so, libandroid.so, libvulkan.so, liblog.so, libm.so, libdl.so, libc.so |

Both are AArch64. The link line carries `-Wl,-z,max-page-size=16384` (from `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` in
Pocket XR's patch). `libopenxr_monado.so` exports one function, `xrNegotiateLoaderRuntimeInterface`.
`libmonado-service.so` exports the four `MonadoImpl` natives and eleven `PocketFeed` natives.

### Where the package name is baked in

`-PxrtAndroidPackage` becomes the CMake variable `XRT_ANDROID_PACKAGE`, then a `#define` in the generated
`xrt_config_android.h` (`"dev.ultraxr"`, confirmed in the build tree and as a string in both libraries). It is used in:

| Where | For what | In which library |
| --- | --- | --- |
| `monado/src/xrt/ipc/android/ipc_client_android.cpp:85` | `Client.blockingConnect(activity, "dev.ultraxr")`: `createPackageContext` and `Intent(CONNECT).setPackage(...)`, the bind to the service | `libopenxr_monado.so` |
| `ipc_client_android.cpp:59`, `auxiliary/android/android_custom_surface.cpp:72, 207, 264`, `android_lifecycle_callbacks.cpp:175` | Error messages only | both |
| `monado/src/xrt/drivers/android/android_sensors.c:209` | `ASensorManager_getInstanceForPackage` in Monado's own phone driver. Compiled in, never used: Pocket XR's patch swaps that driver's prober for the pocketxr one (`targets/common/target_lists.c`) | `libmonado-service.so` |

So the one place that matters is the bind: **the app's applicationId must be exactly `dev.ultraxr`**. A debug variant
with an `applicationIdSuffix` cannot be bound by this library; rebuild with another `-PxrtAndroidPackage`, or use no
suffix.

Not baked in but just as binding, found while reading:

- Monado's Java classes are loaded into the application with a `DexClassLoader` on `<directory of the loaded
  libopenxr_monado.so, cut at "/lib/">/base.apk` (`monado/src/xrt/auxiliary/android/android_load_class.cpp:33-52, 156-165`).
  So the classes `org.freedesktop.monado.ipc.Client`, `IMonado`, `org.freedesktop.monado.auxiliary.*` must be in the
  **base APK** (not a feature split), under their **real names** (R8 rules in `kotlin/proguard-monado.pro`).
- The action `org.freedesktop.monado.ipc.CONNECT` is a `BuildConfig` constant inside `monado-ipc.aar`
  (`monado/src/xrt/ipc/android/build.gradle`), as is the library name `monado-service` that `MonadoImpl` and `PocketFeed`
  load with `System.loadLibrary`.

## 3. What Pocket XR's patch gives (read, then confirmed by the host test where noted)

`pocket-xr/app/src/main/cpp/monado/monado-pocketxr.patch`:

- `drivers/pocketxr/pocketxr_hmd.c`: an auto prober that makes one head mounted display and two controllers, fed
  through `org.freedesktop.monado.ipc.PocketFeed` (JNI entries in `targets/service-lib/service_target.cpp`).
- The fake compositor pacer can be locked to a real vblank (`auxiliary/util/u_pacing_compositor_fake.c`,
  `u_pc_fake_external_vblank`).
- 16 KB pages, NDK 27, `-DXRT_ANDROID_PACKAGE` from a Gradle property.

### Head: `nativePose6`

```java
public static native void nativePose6(long timestampNs,
    float px, float py, float pz, float x, float y, float z, float w,
    float angX, float angY, float angZ, float velX, float velY, float velZ, boolean positionTracked);
```

- It is real 6DoF: position and orientation go into an `m_relation_history` with the flags orientation valid and
  tracked, angular velocity valid, position valid, and, when `positionTracked`, position tracked and linear velocity
  valid (`monado/src/xrt/drivers/pocketxr/pocketxr_hmd.c:320`). With `positionTracked` false the position is kept as
  valid but not tracked, and the velocity is dropped.
- **Clock:** `CLOCK_MONOTONIC` nanoseconds, which is `System.nanoTime()` on Android and Monado's `os_monotonic_get_ns()`.
  Not `elapsedRealtimeNanos` (that is CLOCK_BOOTTIME and runs on during sleep). Stamp with the time the pose is true.
- **Axes:** OpenXR's: right handed, metres, +x right, +y up, -z forward. Orientation is head to world (x, y, z, w).
  Angular velocity in rad/s about the world's axes, velocity in m/s in the world
  (`pocketxr_interface.h`; Monado turns the angular velocity into the body frame itself, `auxiliary/math/m_predict.c:33`).
- **Prediction:** Monado asks the device for the head at each frame's predicted display time
  (`pocketxr_hmd_get_tracked_pose`, line 139). `m_relation_history_get` interpolates between samples for a time inside
  the history and extrapolates from the newest sample with its velocities for a time after it
  (`auxiliary/math/m_relation_history.cpp:108-119`), with no limit on how far. So feed often (Pocket XR feeds every 3 ms,
  `pocket-xr/.../openxr/OpenXrHeadFeed.kt:22`), feed poses at their true time and leave the look-ahead to Monado, and
  give honest velocities: they are what the look-ahead uses. Pocket XR found raw gyro rate made the picture shake and
  fades the rate out below 15 degrees a second (`OpenXrHeadFeed.kt:28-36, 91-99`).
- Changed by patch 0003: the device now reports `supported.position_tracking = true` (Pocket XR left it false even
  with `nativePose6`), and the head shares its tracking origin with the hands (section 4).

`nativePose` (rotation only) is still there.

### Controllers: `nativeController`

```java
public static native void nativeController(int hand, long timestampNs,
    float px, float py, float pz, float x, float y, float z, float w,
    float angX, float angY, float angZ, float velX, float velY, float velZ,
    int buttons, float trigger, float squeeze, float stickX, float stickY);
```

- Two devices named `XRT_DEVICE_TOUCH_CONTROLLER`, left and right hand (`pocketxr_hmd.c:534`): the interaction profile
  **`/interaction_profiles/oculus/touch_controller`**. Monado's rebinding tables
  (`monado/src/xrt/auxiliary/bindings/rebindings/`) also let an application that only suggests
  `khr/simple_controller` or `khr/generic_controller` bind to it. An application that suggests only, say, Index or Vive
  bindings gets nothing bound. hello_xr suggests simple, touch, vive, index and WMR, so it binds touch.
- Inputs (`pocketxr_hmd.c`, `enum pocketxr_input` and `pocketxr_controller_update_inputs`, line 435):

  | Input | From |
  | --- | --- |
  | X click (left), A click (right) | `buttons` bit 0x1 |
  | Y click (left), B click (right) | 0x2 |
  | menu click (left), system click (right) | 0x4 |
  | thumbstick click | 0x8 |
  | X/A touch, Y/B touch, trigger touch, thumbstick touch, thumbrest touch | 0x10, 0x20, 0x40, 0x80, 0x100 |
  | trigger value, squeeze value | `trigger`, `squeeze`, 0 to 1 |
  | thumbstick | `stickX`, `stickY`, -1 to 1 |
  | grip pose, aim pose | the pose fed |
  | haptic output | accepted and ignored (no rumble) |

  A Touch controller has no trigger click; applications that bind `select/click` (simple controller) get it from the
  trigger value through Monado's rebinding. The right hand has **no menu button** in this profile (bit 0x4 is `system`
  there, which applications cannot normally bind): put "menu" gestures on the left hand.
- Poses are predicted in full up to 30 ms past the newest sample, then ever less up to 70 ms; after 150 ms without a
  state the pose stops being tracked; after 2 s the inputs go inactive (lines 380-390, 465).
- **In Pocket XR the aim pose is the grip pose** (one history answers both names). Patch 0003 adds a separate aim.
- **In Pocket XR the controllers are not in the head's space.** Each device is allocated with its own tracking origin
  (`U_DEVICE_ALLOC_TRACKING_NONE`), and Monado's builder then moves origins of that type: the head's 1.6 m up, the left
  hand's to (-0.2, 1.3, -0.5), the right hand's to (0.2, 1.3, -0.5)
  (`monado/src/xrt/auxiliary/util/u_builder_helpers.c:28-64`). A controller fed at the head's own position therefore
  appears 0.3 m below, 0.5 m in front and 0.2 m to the side of the head. That fits Pocket XR's note that the sabers
  "only sat in his hands pulled 0.4 m back" (`OpenXrRuntimeService.kt:67-68`). Patch 0003 removes it (section 4).

Gesture-driven play from the app: compute the grip pose from the palm, the aim pose from the hand's pointing ray, and
`trigger` from the thumb to index distance (`UltraXrFeed.pinchToTrigger` has the hysteresis), squeeze from a fist,
menu from a left hand gesture. All of that is the app's; the runtime only carries it.

### Display, eyes, pacing

- `nativeConfigure(widthPx, heightPx, refreshHz, tanHalfX, tanHalfY)`, before the server starts: the whole panel and one
  eye's field of view as half-angle tangents. The driver makes two views, each `width/2` by `height`, the left eye in
  the left half, no distortion (`pocketxr_hmd.c:235-260`). For 3840 x 1080 that is 1920 x 1080 an eye. The size
  applications are told to render at is 140 % of that by default (`XRT_COMPOSITOR_SCALE_PERCENTAGE`,
  `monado/src/xrt/compositor/main/comp_settings.c:34`), 2688 x 1512 an eye.
- `nativeConfigureEyes(ipdM, eyeShiftTan)`, before the server starts: the eye separation the views are placed at
  (accepted between 40 and 90 mm, else the application default of 63 mm stays), and a shift of each eye's frustum
  outward by a tangent, `(IPD / 2) / distance of the glasses' virtual screen`, so that something at infinity is seen
  straight ahead although the glasses' optics converge at that distance (`pocketxr_hmd.c:249-256`).
  `OpenXrGlassesLayer.Defaults` has starting values for the Air 2 Ultra: tangents 0.4251 by 0.2391 (from the
  published 52 degree diagonal of a 16:9 picture) and 4 m (Pocket XR's value for XREAL glasses). Both want measuring.
- `nativeVblank(lastVblankNs, periodNs)`: Android gives Monado's compositor no display timing (it runs
  `COMP_TARGET_FORCE_FAKE_DISPLAY_TIMING`, `compositor/main/comp_window_android.c:198`), so its pacer is a free running
  clock at the nominal period. While a vblank fed here is under 0.5 s old and its period is between 2 and 50 ms, the
  pacer predicts from it instead (`u_pacing_compositor_fake.c`). A period of 0 switches it off. Pocket XR measures the
  glasses' real present times with EGL frame timestamps from a one pixel frame it keeps drawing under the game, and a
  `VblankLock` that turns them into phase and period (`pocket-xr/app/src/main/kotlin/com/hayeco/flipspaces/spatial/SpaceRenderer.kt:1007-1035`,
  `pocket-xr/core/src/main/kotlin/com/hayeco/flipspaces/core/spatial/VblankLock.kt`). It measured 16.6564 ms against the
  nominal 16.667 and a gain of a few frames a second (`pocket-xr/docs/OPENXR-PLAN.md:168-179`). Without any feed the
  runtime still works; the phase drifts about a frame every 30 to 40 s.

## 4. What patch 0003 adds

All in `libmonado-service.so`; nothing in the library applications load.

```java
// org.freedesktop.monado.ipc.PocketFeed
public static native void nativeHand(int hand, long timestampNs, float[] joints, boolean active);
public static native void nativeConfigureHands(float maxPredictMs, float timeoutMs);
public static native void nativeControllerAim(int hand, long timestampNs,
    float px, float py, float pz, float x, float y, float z, float w,
    float angX, float angY, float angZ, float velX, float velY, float velZ);
public static native void nativeSetOriginOffset(float px, float py, float pz, float x, float y, float z, float w);
public static native boolean nativeRecenter();
```

JNI symbols: `Java_org_freedesktop_monado_ipc_PocketFeed_nativeHand` (`(IJ[FZ)V`), `..._nativeConfigureHands` (`(FF)V`),
`..._nativeControllerAim` (`(IJFFFFFFFFFFFFF)V`), `..._nativeSetOriginOffset` (`(FFFFFFF)V`), `..._nativeRecenter` (`()Z`),
in `monado/src/xrt/targets/service-lib/service_target.cpp:272-327`. `kotlin/dev/ultraxr/openxr/UltraXrFeed.kt` wraps
all of `PocketFeed` with named parameters and the joint indices.

### Hand joints: XR_EXT_hand_tracking

- `nativeHand(hand, timestampNs, joints, active)`: `hand` 0 left, 1 right. `joints` is 208 floats: 26 joints in
  `XrHandJointEXT` order (palm, wrist, thumb metacarpal, proximal, distal, tip, then index, middle, ring and little
  metacarpal, proximal, intermediate, distal, tip), each px, py, pz, qx, qy, qz, qw, radius. Same space and clock as
  the head. Orientations as the extension defines them: -z along the bone toward the tip, +y out of the back of the
  hand. Quaternions are normalised on the way in; a zero one becomes identity. `active` false (or a null or short
  array) makes the hand inactive at once.
- How it reaches applications, at this commit: the legacy system builder looks for a device with
  `supported.hand_tracking` and an input named `XRT_INPUT_HT_UNOBSTRUCTED_LEFT` / `_RIGHT`
  (`monado/src/xrt/auxiliary/util/u_system_helpers.c:70-99`, called from `targets/common/target_builder_legacy.c:189-205`)
  and puts it in the static roles `hand_tracking.unobstructed.left/right`. The OpenXR state tracker reports
  `XrSystemHandTrackingPropertiesEXT.supportsHandTracking` from those roles (`state_trackers/oxr/oxr_system.c:684-700`)
  and `xrCreateHandTrackerEXT` makes an `xrt_hand_tracker` from them (`oxr_hand_tracking.c:107-178`,
  `base/b_hand_tracker.cpp`), which calls the device's `get_hand_tracking` and chains each joint through the set's
  `hand_pose` and the device's tracking origin into the space the application asked for (`b_hand_tracker.cpp:236-312`).
  The extension itself is compiled in by default (`XRT_FEATURE_OPENXR_HAND_TRACKING_EXT`, `monado/CMakeLists.txt:413`),
  and so is `XR_EXT_hand_tracking_data_source`, where these hands report the source "unobstructed".
- So the patch adds one device, "Ultra XR hands", with those two inputs (`drivers/pocketxr/pocketxr_hands.c`), made by
  the same auto prober as the fourth device (`pocketxr_hmd.c:733`). The controllers stay separate devices in the
  left and right roles, so an application gets both: joints from `XR_EXT_hand_tracking`, and Touch inputs and
  grip/aim poses from the action system.
- The device keeps the last two samples of each hand. Asked for a time, it returns the newest sample with each
  joint's position carried on at the speed between the two samples for at most 30 ms (`nativeConfigureHands`; 0 holds
  the newest sample), or interpolated back toward the older sample for a time between them. Linear velocity is
  reported per joint when two samples under 150 ms apart exist. A hand not fed for 200 ms goes inactive by itself.
  This is simple on purpose: smoothing and any better prediction belong in the app, which knows its tracker.
- Not added: `XR_EXT_hand_interaction` (pinch, poke and aim poses as an interaction profile) is not served, because
  the left and right roles hold Touch controllers. An application that binds only that profile gets no input.

### Separate aim pose

`nativeControllerAim` feeds the aim pose on its own. While one was fed within 150 ms of the controller's newest
state it answers `XRT_INPUT_TOUCH_AIM_POSE`; otherwise the aim pose is the grip pose as before (`pocketxr_hmd.c:465-500`).

### One tracking space, and where it sits

- The head, both controllers and the hands now share one tracking origin of type "other", so the builder moves none
  of them (`pocketxr_hmd.c:67-76`). What the app feeds in one space arrives in one space.
- The driver then applies an **origin offset** to every pose it answers: fed space to Monado's root space, which is
  the STAGE space. Default (0, 1.6, 0): the nominal standing height the builder used to add for the head, so a
  tracker that starts at zero puts the floor 1.6 m below its start. `nativeSetOriginOffset` replaces it, for example
  with the real floor height once the app knows it, or with zero if the app feeds heights above the floor.
- **Reference spaces as Monado makes them here** (`monado/src/xrt/base/b_space_overseer.c:1040-1095, 1403-1465`,
  `targets/helpers/target_builder_helpers.c:150-168`): STAGE is the root. LOCAL is made **per application, when its
  session is created, at the head's position and yaw at that moment**. LOCAL_FLOOR is the same at floor height. VIEW is
  the head. So every application already starts centred on where the wearer is and looks.

### Recentring

- `nativeRecenter()` sets the origin offset so the head's newest fed pose is over the STAGE origin facing -z: yaw only,
  the height offset kept (`pocketxr_hmd.c:680-724`). It returns false when no head pose was fed yet. Head, controllers
  and hands move together, on the next pose asked for.
- What it does to a running application: one that uses STAGE is recentred. One that uses LOCAL keeps the origin it got
  at session start, which is a fixed place in STAGE, so recentring moves the wearer to the STAGE origin, not to that
  LOCAL origin, unless they are the same place. They are the same place if the app calls `nativeRecenter()` just
  before the application's session starts (for example when it connects, after the first head pose). After that
  every later `nativeRecenter()` brings the wearer back to both.
- No `XrEventDataReferenceSpaceChangePending` is sent: the world jumps for one frame, as with any recentre.
- Monado's own route, `xrt_space_overseer_recenter_local_spaces`, moves the shared LOCAL space, which applications do
  not use here because LOCAL is per application; it would do nothing visible. Hence the driver offset. The app can
  equally well recentre by re-zeroing its own tracking space before feeding; the effect is the same.

## 5. Hosting the runtime in the app

The drop-in classes do this; `kotlin/AndroidManifest-snippet.xml` is the manifest part.

### The sequence

1. The application calls `xrInitializeLoaderKHR` and `xrCreateInstance`. Its loader asks the broker provider for the
   active runtime (section 6) and `dlopen`s `<our native library dir>/libopenxr_monado.so` in the application's process.
2. That library loads `org.freedesktop.monado.ipc.Client` from our `base.apk` and calls `blockingConnect`
   (`monado/src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/Client.java:132`): it binds our service with
   `Intent("org.freedesktop.monado.ipc.CONNECT").setPackage("dev.ultraxr")` and the flags `BIND_AUTO_CREATE |
   BIND_IMPORTANT | BIND_ABOVE_CLIENT | BIND_INCLUDE_CAPABILITIES` (lines 245-265). It first waits for the application's
   activity to be resumed (`ipc_client_android.cpp:84`).
3. Our `OpenXrRuntimeService.onBind` configures the display and makes a `MonadoImpl`, whose constructor starts
   Monado's server on a thread in **our process** (`MonadoImpl.java:44-47`, `service_target.cpp`,
   `ipc_server_main_common`). The binder goes back to the application.
4. The application's `Client` asks `canDrawOverOtherApps()` over Binder (line 168). Our subclass answers
   `OpenXrGlassesLayer.canShow()`. **Yes** means "the runtime has its own surface": the application makes none. **No**
   means the application attaches a `MonadoView` to its own activity and sends that surface with `passAppSurface`
   (lines 169-176): the picture then shows on the phone, both eyes side by side. That is the fallback when the glasses
   are not there.
5. `Client` makes a socket pair and hands one end over with `connect(ParcelFileDescriptor)`; all OpenXR traffic then
   runs over that socket and shared memory, not Binder.
6. Meanwhile `OpenXrGlassesLayer.open` adds a sub-window to the app's window on the glasses' display and passes its
   surface to `MonadoImpl.passAppSurface`.

### Service declaration

- The service must be `android:exported="true"` with two intent filters: `org.freedesktop.monado.ipc.CONNECT` (what is
  bound) and `org.khronos.openxr.OpenXRRuntimeService` (never bound: brokers find runtimes by it, and applications'
  `<queries>` for that action are what makes our package visible to them on Android 11 and later, which the bind
  and `createPackageContext` need).
- Two `<meta-data>` on the service, read by brokers (`broker/utils/.../RuntimeData.kt`):
  `org.khronos.openxr.OpenXRRuntime.SoFilename` = `libopenxr_monado.so` and
  `org.khronos.openxr.OpenXRRuntime.MajorVersion` = `1` (an integer). Optional
  `org.khronos.openxr.OpenXRRuntime.Functions.<xrName>` entries rename entry points; Monado needs none.
- `monado-ipc.aar` brings its own `org.freedesktop.monado.ipc.MonadoService` (a Hilt entry point with a foreground
  notification) and the `FOREGROUND_SERVICE` permission. Remove the service with `tools:node="remove"`, as Pocket XR
  does (`pocket-xr/app/src/main/AndroidManifest.xml:259-279`). With it gone, Hilt is not needed on the classpath; the
  smoke test app builds and passes R8 without it.
- Lifecycle: the service is only bound, never started, so it lives exactly as long as an application is bound. When
  the last one unbinds, `onUnbind` closes the glasses layer and `onDestroy` shuts Monado down on a side thread (Pocket
  XR hit an ANR doing it on the main thread after a lost surface, `OpenXrRuntimeService.kt:49-55`). Each application
  session therefore gets a fresh server, devices and compositor, and `configureRuntime()` runs before each.
- Process priority: the bind flags tie our process to the foreground application's importance while it is bound.

### The surface, and the permission that must not be granted

- Monado's compositor looks for its window once, when it starts (`monado/src/xrt/compositor/main/comp_window_android.c:140-182`):
  1. in process (not our case): a view in the application's activity;
  2. **if `Settings.canDrawOverlays(our app)` is true: it makes its own `TYPE_APPLICATION_OVERLAY` window on display 0,
     the phone** (`_create_android_window`, line 92, `display_id` 0) and never looks at a passed surface;
  3. otherwise it polls up to 100 times 20 ms for the window stored by `passAppSurface`
     (`service_target.cpp:368-378`), and fails with "could not get ANativeWindow" after those 2 s.
- So this approach needs the app **not** to hold "display over other apps" (`SYSTEM_ALERT_WINDOW`). Do not declare
  it. `SYSTEM_ALERT_WINDOW` is not needed for anything here. (Monado's stock runtime app is the opposite: it asks for
  that permission to draw its own overlay.) Note the mismatch this leaves: the Java `canDrawOverOtherApps()` we
  override only steers the application's side; the server side asks Android directly.
- The surface is a `SurfaceView` in a `TYPE_APPLICATION_SUB_PANEL` window whose token is the app's own window on the
  glasses' display, not focusable, not touchable, `FLAG_KEEP_SCREEN_ON` (Pocket XR's `OpenXrGlassesLayer.kt:66-135`). A
  sub-window because Android refuses a `Presentation` on a display where the app's activity already is.
  So **the app needs an activity (or Presentation) of its own on the glasses' display while an application runs**.
- Timing: the surface must arrive within those 2 s of the compositor starting. `open` is posted to the main thread
  from `onBind`, as in Pocket XR; a busy main thread at that moment is the risk.
- How the application's pictures reach the glasses: the application renders into swapchain images that Monado
  allocates as AHardwareBuffers and shares over the socket; each `xrEndFrame` hands layers to the compositor in our
  process, which reprojects them for the newest head pose and draws both eyes into its one Vulkan swapchain on that
  surface (FIFO present by default, `XRT_COMPOSITOR_PRESENT_MODE`), left eye in the left half. SurfaceFlinger shows
  the sub-window on the glasses. Pocket XR saw it composed as a single device overlay
  (`pocket-xr/docs/OPENXR-PLAN.md:158-162`).

### While an application runs

- **The application's activity must stay resumed.** Monado drives the session from the application's activity
  lifecycle: on pause the session goes FOCUSED, VISIBLE, SYNCHRONIZED, STOPPING
  (`monado/src/xrt/state_trackers/oxr/oxr_session.c:683-709`). The application runs on the phone's display (display 0)
  while our activity sits on the glasses' display; since Android 10 each display has its own resumed activity, so both
  can be resumed. If the phone locks or its screen turns off, the application pauses and the session stops, and
  locking also takes the glasses' display away. Pocket XR holds a `SCREEN_DIM_WAKE_LOCK` from the application context
  (`OpenXrGlassesLayer.kt:47-62`, permission `WAKE_LOCK`) and turns the phone's panel off through its privileged panel
  service; without such a service the phone's screen stays on, dimmed, showing the application's own (black) window.
- The app should stop drawing its own scene under the layer (`Host.onApplicationConnected(true)`), keep feeding the
  head, hands and controllers, and feed the vblank.
- The application's process loads our code: `Client`, `MonadoView`, `SystemUiController` run inside it, and
  `SystemUiController` hides its system bars.
- Permissions: none are needed from the application for our runtime. From our app: nothing for the runtime itself;
  `WAKE_LOCK` for the wake lock. Any app on the phone can bind the service, and a bound application can read the head
  and hand poses; Pocket XR lists this as open (`pocket-xr/docs/OPENXR.md:45`).

### Packaging

- `jniLibs { useLegacyPackaging = true }` (`android:extractNativeLibs="true"`), as Pocket XR has
  (`pocket-xr/app/build.gradle.kts:107`): the loader `dlopen`s the library by path from another process.
- The two AARs as file dependencies plus `androidx.annotation`. Their Kotlin classes need the Kotlin standard
  library, which a Kotlin app has.
- R8: `kotlin/proguard-monado.pro`. The AARs have no consumer rules. Besides keeping `org.freedesktop.monado.**`, it
  keeps Kotlin's names: Monado's classes are loaded in the application with the application's class loader as
  parent, so a renamed `kotlin` class could resolve to an unrelated class of a minified application. The smoke test
  app's release build uses these rules and keeps every class Monado loads by name.

## 6. Runtime discovery: being the broker

What the Khronos loader does on Android (`loader/src/loader/android_utilities.cpp`, `runtime_interface.cpp:33-49`,
`manifest_file.cpp:676-720`):

1. If the `XR_RUNTIME_JSON` override is set, it uses that file (`manifest_file.cpp:679`).
2. Otherwise it queries the **installable broker**, then, only if that gave nothing, the **system broker**:
   `content://org.khronos.openxr.runtime_broker/openxr/1/abi/arm64-v8a/runtimes/active` and the same under
   `org.khronos.openxr.system_runtime_broker` (lines 32-36, 71-82, 274-285). Path segments: `openxr`, the OpenXR
   major version, `abi`, the ABI name, `runtimes`, `active`. Projection: `package_name`, `native_lib_dir`,
   `so_filename`, `has_functions`. No selection, no sort order.
3. A null cursor, an exception or an empty cursor all count as "no answer" (lines 220-243).
4. For each row it builds `native_lib_dir + "/" + so_filename` and tries `dlopen`. The first row that opens wins
   (lines 293-325). If `has_functions` is 1 it then queries `.../runtimes/<package_name>/functions` for
   `function_name`, `symbol_name` rows and uses them as renamed entry points; if that fails it skips the row.
5. From the winner it makes a manifest in memory with `library_path` and loads the runtime.
6. With no broker answer it falls back to `active_runtime.<abi>.json` files under `/product`, `/odm`, `/oem`,
   `/vendor`, `/system` `/etc/openxr/1/`, which an app cannot provide.

**What a provider in the app must return:** for that URI one row: `package_name` = `dev.ultraxr`, `native_lib_dir` =
`applicationInfo.nativeLibraryDir`, `so_filename` = `libopenxr_monado.so`, `has_functions` = `0` (and `_id` = 0 if
asked). For any other major version or ABI an empty cursor. For the functions URI nothing is needed with
`has_functions` 0. `kotlin/dev/ultraxr/openxr/OpenXrBrokerProvider.kt` does exactly this, plus the Khronos broker's
fallback path `<sourceDir>!/lib/<abi>` when the libraries are not extracted (`broker/utils/.../RuntimeData.kt`,
`getAbiNativeLibraryDir`).

**Checks:** the loader makes none. No signature check, no system-app check, no permission: it trusts whichever
package owns the authority. The installable broker's provider is exported with no read permission
(`broker/installable_runtime_broker/src/main/AndroidManifest.xml`); only the system broker has one
(`org.khronos.openxr.permission.OPENXR_SYSTEM`, protection level normal). The provider must be
`android:exported="true"`. Applications built with the loader AAR declare `<queries>` for both authorities and for
the `OpenXRRuntimeService` action, and `uses-permission` for `org.khronos.openxr.permission.OPENXR` and
`OPENXR_SYSTEM` (`loader/src/loader/AndroidManifest.xml.in`); a permission nobody defines is simply not granted and
nothing checks it. **Do not define `org.khronos.openxr.permission.OPENXR` in the app:** the broker app defines it, and
two packages with different keys defining one permission cannot both be installed.

**If both are installed:** they cannot be. Android allows one installed package per provider authority; the second
install fails with `INSTALL_FAILED_CONFLICTING_PROVIDER`, whichever comes second (as far as I know also when the
provider is declared but disabled). So with the provider in the app, a phone that already has the Khronos "OpenXR Runtime Broker" app
(package `org.khronos.openxr.runtime_broker`) cannot install the app until that is removed, and the broker app cannot
be installed afterwards. The same holds against any other app that embeds this provider. Without the provider the
app coexists with the broker app: the broker lists every package with the `OpenXRRuntimeService` filter and the two
meta-data entries, and the user picks Ultra XR there once. Pocket XR ships that way, with only `<queries>` for the
broker (`pocket-xr/app/src/main/AndroidManifest.xml:74-77`). A build flavor with and one without the provider covers
both. This is from Android's documented behaviour, not tried here.

Applications that do not use the Khronos loader and broker (for example ones built only against a vendor's loader)
do not find this runtime at all.

## 7. hello_xr

`hello_xr.apk` is OpenXR-SDK-Source `release-1.1.65`, `src/tests/hello_xr`, built with its own Gradle project (Gradle
8.5, Android Gradle plugin 8.1.4), Vulkan and OpenGL ES flavors, debug. Changes, all in `patches/hello_xr-build-here.diff`:

- `ndkVersion` 23.2.8568313 to 27.2.12479018 (the one installed), and `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`.
- `abiFilters 'arm64-v8a'`.
- `main.cpp:271`: `ALooper_pollAll` to `ALooper_pollOnce`. NDK 27 marks `ALooper_pollAll` unavailable, so the file does
  not compile otherwise; this is the replacement the NDK's message names. It is the one source change.

After the build the APKs are realigned with `zipalign -P 16` (that plugin version aligns stored libraries to 4 KB) and
signed again with the debug key. Both libraries inside have 0x4000 LOAD alignment. It contains its own
`libopenxr_loader.so`, declares the broker `<queries>`, and is a NativeActivity in landscape.

To try: install the smoke test APK (or the real app), then hello_xr, start hello_xr. Useful logcat tags:
`OpenXR-Loader` (the broker query and the library path), `monado-ipc-client`, `MonadoImpl`, `UltraXrOpenXr`, and
Monado's own. hello_xr does not use hand tracking; it shows cubes at the grip poses and reacts to squeeze.

## 8. Emulator

Not attempted. There is no `/dev/kvm` here, the libraries are arm64 only, and the Android emulator does not run arm64
system images on an x86-64 host without it in any usable way. Nothing in this folder has run on Android.

## 9. Known limitations Pocket XR lists

From `pocket-xr/docs/OPENXR.md` ("Not done yet", "Before a public release") and `pocket-xr/docs/OPENXR-PLAN.md`:

- Early work; one phone (Galaxy Z Flip 7). Played Beat Saber through ALVR.
- Stereo depends on the glasses already being in their 3D (side by side) mode; nothing switches it for an application.
- No rumble. Controller grip angle is a first guess.
- Power button during a session untested. Unplugging the glasses during one: the application's window does not come
  back after a real display dropout.
- An application that pauses leaves its window over the app's scene until it is closed.
- Heat: thermal status 3 within two minutes of streaming; at that level the phone throttles and frame rate halves.
- Monado's pacer has no real feedback from the display (hence `nativeVblank`); `vkGetPastPresentationTimingGOOGLE`
  returned one frozen time on that phone.
- A slow application can fall to half rate (`U_PACING_APP_USE_MIN_FRAME_PERIOD` exists, gave no clear gain).
- Applications are told the phone's refresh rates, not the glasses': `XR_FB_display_refresh_rate` answers from
  `MonadoView.getDisplayRefreshRate` on the default display (`monado/src/xrt/compositor/main/comp_compositor.c:351-357`).
  Still so.
- Any app can bind the runtime and read the head pose.
- `jniLibs` are not in Pocket XR's public repository; licences of everything Monado pulls in still to be checked.

## 10. Limitations and risks found here

- **`SYSTEM_ALERT_WINDOW`** must not be granted to the app (section 5). If the app ever needs overlays, the compositor's
  window choice in `comp_window_android.c` needs a patch.
- **The broker provider excludes the Khronos broker app** (section 6).
- **Package name fixed at build time**, no `applicationIdSuffix` (section 2).
- **Opaque only.** The device offers `XRT_BLEND_MODE_OPAQUE` alone (`pocketxr_hmd.c:206`). On see-through glasses black
  is transparent anyway, but an application that asks for the additive blend mode is refused. Adding
  `XRT_BLEND_MODE_ADDITIVE` to that list is a one line change; not done because it was not asked for.
- **Behaviour changed from Pocket XR's libraries:** controllers are now in the head's space (no (±0.2, 1.3, -0.5)
  shift), and the head reports position tracking. Code written against Pocket XR's arm offsets would be 0.3 m high and
  0.5 m forward with these libraries.
- Hand joints: no angular velocity, linear extrapolation only, and no `XR_EXT_hand_interaction` profile.
- Monado debug options are read from system properties `debug.xrt.<NAME>` on Android
  (`monado/src/xrt/auxiliary/util/u_debug.c:72`), for example `adb shell setprop debug.xrt.XRT_COMPOSITOR_SCALE_PERCENTAGE 100`
  before the application starts.
- With a mirrored 1920 x 1080 mode on the glasses the driver still splits the picture in two halves; only the
  3840 x 1080 side by side mode is right. The app has to switch the glasses to it.

## 11. Integration checklist

1. Copy `jniLibs/` to `app/src/main/jniLibs/`, the AARs to `app/libs/monado/`, the four Kotlin files to the app.
2. Gradle and manifest as in the two snippets; decide about the provider.
3. Implement `OpenXrGlassesLayer.Host` and set `OpenXrGlassesLayer.host` in `Application.onCreate` (the service can
   start in a fresh process). `configureRuntime()` can call `OpenXrGlassesLayer.Defaults.configure(display)`.
4. In `onApplicationConnected`: start feeding `UltraXrFeed.head` (every few ms), `hand` and `controller` /
   `controllerAim` (every camera frame, with the frame's own time), `vblank`; call `recenter()` after the first head
   pose if LOCAL and STAGE should coincide; hold a wake lock.
5. Try first with `ultraxr-runtime-smoketest-debug.apk` and `hello_xr.apk`, which need no tracking.

## 12. What was verified, and how

Verified by building or running on this machine:

- The three patches apply cleanly in order on the pinned commit, and reproduce the tree that was built (checked in a
  separate worktree, and again by `build.sh`).
- The out of process native build and both AARs build; the libraries are AArch64, 0x4000 aligned, with the
  dependencies listed; `dev.ultraxr` is in both; the JNI symbols are exported; no compiler warnings in the driver.
- `build.sh` ran end to end with `HELLO_XR=1 HOSTTEST=1 SMOKE=1` into a fresh folder (clean clone, patches, native
  build, AARs, readelf checks, host test 30 of 30, hello_xr, smoke test app debug and release), exit 0. The libraries
  in this folder are from the first build of the same sources; the rebuilt ones differ by under 1 KB, from the build
  path.
- **The driver, on the host** (`hosttest/pxtest.c`, 30 checks, all pass): compiled for Linux together with Monado's
  real `u_builder`/legacy space overseer and base hand tracker, the same code paths the service uses. It checks:
  four devices, one shared origin that the builder leaves at zero; both unobstructed hand roles found by the
  builder's helper; head in STAGE = fed pose + 1.6 m; controller grip in STAGE = fed pose + 1.6 m with no hand shift;
  separate aim pose, and fallback to grip; button, trigger and touch mapping; hand tracker creation for both hands;
  inactive before feeding and after `active = false`; joint positions, flags (valid, tracked, linear velocity),
  radius and quaternion normalisation through the hand tracker; 20 ms extrapolation, the 30 ms clamp, prediction off;
  recentre puts the head over the origin facing -z and leaves hand-to-head geometry unchanged; origin offset zero.
- The Kotlin drop-ins compile against the AARs; the manifest merges to exactly the service, the provider and no
  `MonadoService` or `FOREGROUND_SERVICE`; a release build with R8 and `proguard-monado.pro` succeeds and keeps the
  Monado classes and Kotlin names; the APK is 16 KB aligned with both libraries extracted.
- hello_xr builds for Vulkan and OpenGL ES; APKs are 16 KB aligned and verify.
- Pocket XR's documented Gradle task fails here only at the jitpack.io dependency.

Read in source, not run (needs a phone):

- Everything in sections 5 and 6 about Android's behaviour: the bind, the surface hand-over and its 2 s window, the
  overlay permission branch, the sub-window on the external display, two resumed activities, the provider being found
  by the loader, the provider conflict, `dlopen` across apps on Android 16.
- That the IPC path carries hand tracking to an application (the server and client handlers exist and were read:
  `ipc/server/ipc_server_handler.c:1917`, `ipc/client/ipc_client_instance.c:204-207`); the host test covers the
  server side only.
- Pacing, latency, heat, and whether the default field of view and screen distance are right for the Air 2 Ultra.
- `OpenXrBrokerProvider` and the service have never answered a real query.

## 13. Patch 0004 and the rebuild of 17 November 2026

`patches/0004-ultraxr-additive-blend-single-view.patch` (apply after 0001 to 0003): additive blend mode, single-view
mode (`nativeConfigureSingleView`), runtime classes loaded with the boot class loader as parent, an off-screen surface
when EGL has no surfaceless contexts, `XR_EXT_hand_interaction` not advertised, and a controller that is not fed
reported as absent. Why each was needed: docs/WEBXR.md.

Built on Windows with the Android SDK already installed (NDK 27.2.12479018, CMake 3.22.1), `glslangValidator` from the
Khronos glslang release, and Monado's own Gradle wrapper:

    gradlew.bat -Pandroid.injected.build.abi=<arm64-v8a|x86_64> -PxrtAndroidPackage=dev.ultraxr \n      -PxrtNdkVersion=27.2.12479018 :src:xrt:targets:openxr_android:externalNativeBuildOutOfProcessRelease \n      :src:xrt:ipc:assembleRelease :src:xrt:auxiliary:assembleRelease

One ABI per run. A changed CMake argument makes a new build folder: delete `.cxx` and `build/intermediates/cxx` under
`src/xrt/targets/openxr_android` first, or the old folder's libraries are picked up. The arm64 libraries are in
`app/src/main/jniLibs`, the x86-64 ones (for the emulator build, `-Pemu`) in `app/src/emu/jniLibs`. Section 8 is out
of date: an x86-64 emulator image with arm translation runs the arm64 runtime, and the x86-64 build runs natively.
The host test of section 12 was not rerun for patch 0004.
