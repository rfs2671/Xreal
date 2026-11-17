# WebXR on Ultra XR: route, status, and what is proved

The product goal is a WebXR mixed-reality gaming browser on the Pixel 10 Pro XL with the XREAL
Air 2 Ultra. This page records the route chosen, what has been proved and where, and what has not.

**Read the labels.** "Emulator" means an x86-64 Android 14 emulator with a second display standing
in for the glasses and a simulated wearer standing in for tracking. Nothing on this page has run
on the Pixel or the glasses.

## The route

Chrome and Android's WebView do not use an OpenXR runtime an app provides: on Android, Chrome's
immersive WebXR goes through ARCore or a headset maker's own integration, and WebView has no
immersive WebXR at all. A working OpenXR runtime therefore does not give Chrome WebXR.

The browser that does is **Wolvic** (Igalia, MPL-2.0): a WebXR browser whose generic builds use
the standard Khronos OpenXR loader and runtime broker, which is exactly what Ultra XR answers.
Two engines exist: Gecko (the mature one; arm64 builds for generic OpenXR headsets) and Chromium
(newer; the only x86-64 build). It is used unmodified from Igalia's releases, as a separate app.

    page -> Wolvic (WebXR) -> Khronos loader -> Ultra XR broker -> Monado runtime in Ultra XR
         -> glasses display (two half-width eye views, or one picture in 2D)
    tracking (camera + IMU position, hands) -> Ultra XR -> runtime -> Wolvic -> page

## What had to change in the runtime (native/monado/patches/0004)

Found by running Wolvic against it; each was a real defect, not an emulator quirk, unless marked.

| Change | Why |
|---|---|
| Runtime classes load with the boot class loader as parent | Loaded with the application's loader, they picked up the application's shrunken Kotlin library: Wolvic failed in `xrCreateInstance` with `NoSuchMethodError`. Any app built with a code shrinker would. |
| Additive blend mode offered | The glasses are optical see-through. Without it `immersive-ar` is not offered to pages. |
| Single-view mode | With the glasses in 2D, both views cover the whole picture from the head's centre, so one picture is drawn. The two eye pictures are never shown side by side to both eyes. |
| `XR_EXT_hand_interaction` no longer advertised | The driver does not serve it. Advertised, the browser waited for a hand profile that never became active and ignored the hand joints. |
| A controller that is not fed reports as absent | Reported as merely "not tracked", the browser took it as present and never used the hands. |
| Off-screen surface when EGL has no surfaceless contexts | Emulator only as far as known; harmless elsewhere. |

In the app: the broker answers for the ABI it is installed as; a browser is given hands as joints
only (it makes the pinch a click itself), other applications get joints and Touch-shaped
controllers; a game or browser session in 2D uses the single-view mode automatically.

## Proved on the emulator (`tools/ui-automation/webxr_test.py`)

Wolvic 1.4 (Chromium engine, x86-64) in a test copy with two changes (`tools/wolvic/patch_wolvic.py
emulator`): it accepts the emulator's only graphics configurations, and it treats an unknown device
model as a generic hand-tracking headset (see "The device model" below). All 20 checks pass:

- WebXR is present; `immersive-vr` and `immersive-ar` are both offered
- an `immersive-ar` session starts from a hand pinch on the page's button
- frames arrive (about 20 ms apart in the emulator)
- two views, left and right, side by side; eyes 64 mm apart; off-axis projections for the
  glasses' converged optics
- the AR session's blend mode is `additive`
- reference spaces `viewer`, `local`, `local-floor`, `bounded-floor`
- the head pose is the simulated head's: 0.500 rad of yaw peak to peak and 0.100 m of sway, the
  exact values fed, with `emulatedPosition` false
- on the display: the left half holds the left eye's view and the right half the right eye's; a
  point 1.5 m away is 33 pixels apart between them (32 predicted)
- two tracked hands as input sources (`tracked-pointer`, profile `generic-hand`), all 25 joints each
- finger positions are real: the thumb and index tips are 4 mm apart in a pinch and 147 mm apart
  when open, as the simulated hands are
- pinches arrive as `select` events (14 in the run)
- with the stand-in glasses in 2D (checked by hand, same page): eye separation 0, symmetric
  projections, one square drawn once in the middle of the whole picture

## The device model

Wolvic gives pages input sources only on device models it knows (a table in its native code:
Quest, Pico, Lynx-R1, Magic Leap 2 and a few more). On any other model, an emulator or a Pixel,
its controller type is "unknown" and the web engine drops every input source: pages get no hands,
no joints, no `select`, although the same hands work in Wolvic's own windows. An unmodified
Wolvic release on the Pixel would start sessions but give games no input.

`tools/wolvic/patch_wolvic.py` makes the copy that is used instead: Igalia's release with one
value changed, so that an unknown model counts as the Lynx-R1 (a headset used with hands). The
emulator copy made by that tool is the one all the results here come from. The phone copy (arm64,
the same release and the same one change) is installed on the Pixel but has never been run.
A build from source would be cleaner, but Wolvic's engines need their own patched builds of
GeckoView or Chromium, which this machine cannot produce.

## Existing third-party pages (`tools/ui-automation/webxr_pages.py`, emulator)

The pages are not ours and are not changed. The test presses each page's own "enter XR" button and
then reads what the page's session was given.

| Page | Result |
|---|---|
| three.js XR dragging (pick shapes up with a ray) | `immersive-ar`, additive, 1764 frames, two views, head yaw 0.5 rad, two hands with 25 joints, 12 selects |
| three.js XR ball shooter (physics) | `immersive-ar`, additive, 1626 frames, two views, head yaw 0.5 rad, two hands with 25 joints, 11 selects |
| three.js AR cones (place objects) | `immersive-ar`, additive, 1812 frames, two views, head yaw 0.5 rad, two hands with 25 joints, 12 selects |
| A-Frame hand tracking showcase | `immersive-vr` (hand tracking asked for as optional), 1739 frames, two views, head yaw 0.5 rad, two hands with 25 joints, 12 selects |
| Moon Rider (a rhythm game, moonrider.xyz) | `immersive-ar`, additive, 1754 frames, two views, head yaw 0.5 rad, two hands with 25 joints, 12 selects. The game is made for two controllers; not played |
| A-Frame Anime UI | `immersive-vr`, 1639 frames, two views, head yaw 0.5 rad, two hands, 12 selects |
| three.js hand input cubes | Refused: the page *requires* `hand-tracking`, and this engine does not grant that feature (it still delivers joints to pages that ask for it as optional) |

VR pages report the blend mode `additive` too, which is right for see-through glasses: a VR scene is
drawn as light over the room, black stays transparent, and a scene with a bright sky hides the room
and will be glaring.

"Ran" here means the session, the two eye views, the moving head pose, the hands and the pinches all
reached the page. Nobody played these, and the simulated hands do not aim at anything, so whether
a shape can actually be picked up is not shown.

## Not working

- **Pages that require hand tracking** (rather than ask for it) are refused by this engine. The
  Gecko engine may differ; it could not be run on the emulator at all (it crashes in graphics
  setup under arm translation), so only the Chromium engine has been exercised.
- **Hit testing and anchors.** Not implemented, and not asked for by the test: a session that
  requests them makes Wolvic 1.4 end the page ("Environment integration is not supported").
- **Bluetooth gamepads as XR input.** Android delivers gamepad events to the focused app, which
  is the browser, not Ultra XR. Pages can read a gamepad through the browser's Gamepad API; the
  runtime cannot turn one into an XR controller. A gamepad has no position and none is invented.

## Not tested at all

- Anything on the Pixel beyond installing: Ultra XR and the Wolvic copy are installed and the
  phone's runtime broker answers a query correctly; neither a session nor Wolvic itself has run there.
- Anything on the glasses.
- Real tracking through this path. The simulated wearer proves the plumbing, not the tracking.
- Whether any game is actually playable. Third-party pages were run (see below), not played.
- Frame timing and heat on the phone.

## Tools

- `gradlew assembleDebug -Pemu`: the app for an x86-64 emulator (runtime only, no tracking libraries).
- Simulated wearer (`xr/SimWearer.kt`), switched on by the launcher's test flags in debuggable
  builds, steered with `adb shell setprop debug.ultraxr.<name>`: `sim_motion`, `sim_look`,
  `sim_aim`, `sim_tap`, `sim_auto`.
- Test pages are served by the app on the device's localhost (`xr/TestSiteServer.kt`), because
  WebXR is hidden from plain http origins and forwarding a port froze the emulator's adb.
- Emulator notes: 4 GB of memory; stop the browser and wait before stopping Ultra XR, or the
  emulator hangs.
