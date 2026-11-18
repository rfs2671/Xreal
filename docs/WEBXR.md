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
| three.js hand input cubes | Starts since the permission change below (it *requires* `hand-tracking`): VR session, 2 views, 2 hands, 25 joints, 12 selects, 1792 frames. The captured picture shows the two pointing lines only, no cubes |

VR pages report the blend mode `additive` too, which is right for see-through glasses: a VR scene is
drawn as light over the room, black stays transparent, and a scene with a bright sky hides the room
and will be glaring.

"Ran" here means the session, the two eye views, the moving head pose, the hands and the pinches all
reached the page. Nobody played these.

## An object actually picked up and moved (`tools/ui-automation/webxr_interact.py`, emulator)

In three.js XR dragging, unmodified: the simulated left hand, moved 10 cm to the side so that its
ray lay on a shape, pinched and held, was carried 20 cm, and let go. The shape went with the hand
and stayed where it was released (49% of the left eye's picture changed and was still changed two
seconds later; the other shapes did not move; the page counted the select). Checked by eye in the
captures as well, after an earlier version of this test had mistaken Wolvic's "press any button to
continue" card disappearing for a moved object.

Two things this taught about real use:

- **Wolvic points a hand's ray from the shoulder through the hand.** Where the hand is steers the
  ray; turning the wrist hardly does. Aim by moving the hand.
- **Wolvic shows a "press any button to continue" card when a session starts.** One pinch
  dismisses it.

It is a simulated hand: this shows the path from joints to a grabbed object works, not how well a
real tracked hand aims.

## On the actual Pixel (no glasses attached)

Done over wireless adb on the Pixel 10 Pro XL, 17 November 2026:

- `tools/pixel/pixel_check.py install`: 8 of 8. Ultra XR and the Ultra XR copy of Wolvic on the
  phone are byte-for-byte the built files, the phone's OpenXR broker hands out Ultra XR's
  runtime, screen timeout 30 s and stay-awake off are unchanged.
- The Khronos `hello_xr` sample (Vulkan) was started against the runtime with the simulated
  wearer. The runtime loaded on the phone's GPU (Vulkan 1.4), the sample connected, got a
  2688x1512 swapchain and reached `XR_SESSION_STATE_FOCUSED`. Nine seconds later an incoming
  phone call took the screen and the session dropped to `VISIBLE`; it was stopped after 42 s.
  The compositor logged 59 late frames, 2.5 ms late on average, part of that during the call.
  The picture itself was not captured (the screenshot shows the call), so this proves the
  runtime starts and serves an application on the phone, not what it draws.
- Not done on the phone: the browser has not been opened. Its first screen is its terms of
  service, which the owner has to accept. No WebXR session, no stereo, no real tracking and no
  hands have been exercised on the phone or the glasses.

## Frame pacing

The live run on the Pixel logged about 63 % of compositor frames as late (3,172 warnings in 84 s,
median 3.9 ms). What that number is and is not:

- The warning comes from the compositor comparing its own plan with its own clock. It does not
  say a frame missed the display. 3,172 warnings in 84 s also means the compositor was producing
  more than 37 frames a second, so it was not stuck at half rate.
- The compositor's pacer was written to be locked to the display's real refresh by timestamps fed
  from the app (`u_pc_fake_external_vblank`). Nothing feeds them: the app cannot observe the
  refresh of an external display (Android's frame callbacks follow the phone's own panel, which
  runs at 60 or 120 Hz on its own clock). So the pacer has been free-running at an arbitrary
  phase to the glasses' refresh. That is a scheduling defect, not a performance limit.

`tools/ui-automation/pacing_test.py` measures what matters instead: SurfaceFlinger's record of
when each compositor frame actually reached the display.

| Emulator, 40 s, browser's XR world | frames a second | frames on time | shown twice |
|---|---|---|---|
| paced by its own clock (as shipped until now) | 30.0 | 0 % | 1,208 of 1,209 |
| paced by the display's reported present times | 59.0 | 98.3 % | 41 of 2,379 |

The second mode is Monado's own display-timing pacer, which the Android target had switched off
upstream as untested; the Android swapchain does report real present times.

Then the same measurement on the Pixel (`tools/pixel/lab.py pacing`: browser's XR world, simulated
wearer, no glasses, so the compositor draws to the phone's own screen; 40 s each):

| Pixel 10 Pro XL | frames on time | shown twice | shown three times or more | runtime's "late" warnings |
|---|---|---|---|---|
| paced by its own clock, 3 ms compositor budget (as shipped) | 90 % | 124 of 1,510 | 24 | 409 |
| paced by its own clock, 9 ms compositor budget | 91 % | 106 of 1,448 | 22 | 5 |
| paced by the display's reported present times | 65 % | 493 of 1,508 | 31 | 13 |

(The "three times or more" column includes holes in the sampling over Wi-Fi, which this first
version of the script could not tell from long frames; the script now counts them apart.)

What this says:

- On the Pixel the shipped mode is the better one. The display-timed mode, which doubled the
  emulator's frame rate, makes a third of the frames miss on the phone. It stays off by
  default and remains a switch in Settings.
- The runtime's warnings do not track what reaches the display: a larger compositor budget
  removes almost all of them (409 to 5) and changes nothing on screen.
- About one frame in eleven is shown twice on the phone in the shipped mode, and none of the
  three settings tried removes that. Its cause has not been found. The 63 % figure from the
  live run was the runtime's warning count, not this.
- Not measured: pacing on the glasses' display, with real tracking running at the same time.
  That needs the glasses plugged in; the script can sample it during the next session.

So frame pacing on the Pixel is measured but not improved by this build.

## Timing on the emulator

From `webxr_pages.py` (x86-64 emulator, software-translated arm64 pages, simulated tracking):
frame interval about 20 ms on average, 8 to 9 % of frames longer than 25 ms; the browser uses
138 to 143 MB, Ultra XR 56 to 59 MB. These say the path has no gross stall. They are not
predictions for the Pixel, which has to be measured with the glasses on.

## Pages that require hand tracking

They used to be refused. Cause, from the browser's source: WebXR hand input has its own
permission in Chromium; Wolvic's engine maps every permission it has no name for to
"not supported", and `ChromiumPermissionDelegate.requestContentPermission` denies those outright.
A required feature that is denied ends the session request.

`tools/wolvic/patch_wolvic.py` now changes that method (nine bytecode units in one dex file, in
place) so that "not supported" is handled as the XR permission: the answer is whatever the site's
XR permission is. Side effect, stated plainly: any other permission Wolvic has no name for now
also follows the site's XR permission instead of always being denied.

Emulator: three.js hand input cubes, which lists `hand-tracking` as required, now starts a
session and receives both hands with joints. The same change is in the copy installed on the
Pixel (verified by SHA-256); it has not been run there.

## Playing without hand tracking: the phone as a touchpad

The first live runs ended at the browser's terms of service, which have to be accepted with a
pointer inside the glasses, and no pinch was registered. A game must not depend on that.

Android gives touches and keys to the application in front, which is the browser, not Ultra XR.
The supported way for another app to receive them is an accessibility service, so Ultra XR has
one (`xr/PointerService`): the owner switches it on once in Android's settings. While an XR
application runs it shows a touchpad on the phone's screen and listens to the volume keys; it
asks for no screen content and no accessibility events.

| On the phone | In the game |
|---|---|
| Drag | the pointer moves |
| Tap | select (a pinch) |
| Second finger down, or volume-down held | press and keep pressed: grab and carry |
| Volume-up | recentre the view and the pointer |
| A mouse | moves and clicks the same pointer |

The application is given a pointing right hand whose place the touchpad steers and whose pinch
the tap makes (`core/PointerInput`, `SimWearer.feedHand` with the real head pose). It is the same
kind of input a tracked hand gives, so whatever works with a hand works with it; when the
touchpad has not been touched for twenty seconds, tracked hands have the right hand back.

Consent is not bypassed: the terms of service are shown as before and the owner accepts them by
moving the pointer onto AGREE and tapping. "Enter games automatically" (Settings, off by default)
makes Ultra XR press a game page's own start button after the page has loaded; it is off until
the owner switches it on because on the very first run the terms are where that button will be.

Emulator (`pointer_test.py`, 4 of 4): the touchpad alone found and tapped the START XR button of
three.js XR dragging, the page started its session, taps arrived as select events, and a held
press with a drag carried a shape to a new place where it stayed (checked by eye in the
captures). Not testable there: the volume keys, because Android keeps key presses injected by a
test away from accessibility services. Not run on the phone.

## Hands: what was changed after the first sessions

The pinch rule is measured on a recording of the owner's hands; the rest are reasoned changes
with unit tests. None of it has been tried inside a game yet. The session record now collects
what is needed to measure them (each hand's finger length, the closest the fingertips came in
millimetres and in finger lengths, frames held through a loss).

- **Pinch rule from measurement** (`core/SessionTools.kt`, `PinchState`): fingertips count as
  together under 33 mm (or under 0.46 of the index finger's length) and apart again only over
  46 mm (and 0.65), on single results. The first rule, 18 mm for two results in a row, caught
  none of the owner's five recorded pinches; see `docs/TRACKING_ANALYSIS.md`.
- **Brief loss is bridged**: a hand that disappears for up to a quarter of a second (fingers
  hiding each other as they touch) is reported where it was, pinch included.
- **Fingertips handed over touching** while Ultra XR's rule says "pinching", because a browser
  applies its own, tighter distance.
- **The pointer is steadied** (`core/HandSteady.kt`): a one-euro filter on the hand's place
  (strong when it is still, weak when it moves), and for a quarter of a second from the start of
  a pinch the hand is reported where it was just before, unless it is carried more than 3 cm.

The touchpad does not depend on any of this.

## Not working

- **The Gecko engine** could not be run on the emulator (it crashes in graphics setup under arm
  translation), so only the Chromium engine has been exercised.
- **Hit testing** (placing objects on real surfaces). The browser's XR device does not offer the
  feature at all, and nothing in Ultra XR detects surfaces. Games that place objects where the
  hand points (three.js AR cones) work without it; games that need a detected floor or wall do not.
- **Spatial anchors.** Not implemented and not advertised by Ultra XR. (Wolvic's device code lists
  anchors as supported without anything behind it; pages that rely on it will not get working anchors.)
- A session that asks for hit testing or anchors makes Wolvic 1.4 end the page ("Environment
  integration is not supported"), so the test page does not ask for them.
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

## In a session

- **Recentre:** pinch with both hands and hold for a second and a half (`RecenterGesture`, unit
  tested; never tried with real hands).
- **Position fallback:** position is passed on as tracked only while the estimator has enough
  landmarks seen by both cameras (`PositionConfidence`); otherwise the last position is held and
  rotation continues, so the scene turns correctly but does not follow steps. Measured tracking
  quality is in native/vio/NOTES.md; nothing here improves it yet, because no long recording of
  real motion exists to tune on (Settings, "Record 60 seconds", makes one).
- **Session record:** each session appends a line to `journal.jsonl` on the phone (display mode,
  share of time position was tracked, distance moved, frames each hand was seen, pinches,
  recentres). `tools/pixel/pixel_check.py journal` reads it over adb.
- **Reconnect:** if the glasses are unplugged during a session the picture cannot be handed back
  to the running session (the runtime takes its surface once, at start). The session has to be
  left and started again. Not tested on hardware.

## Tools

- `gradlew assembleDebug -Pemu`: the app for an x86-64 emulator (runtime only, no tracking libraries).
- Simulated wearer (`xr/SimWearer.kt`), switched on by the launcher's test flags in debuggable
  builds, steered with `adb shell setprop debug.ultraxr.<name>`: `sim_motion`, `sim_look`,
  `sim_aim`, `sim_tap`, `sim_auto`.
- Test pages are served by the app on the device's localhost (`xr/TestSiteServer.kt`), because
  WebXR is hidden from plain http origins and forwarding a port froze the emulator's adb.
- Emulator notes: 4 GB of memory; stop the browser and wait before stopping Ultra XR, or the
  emulator hangs.
