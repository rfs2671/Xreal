# Ultra XR as a gaming system: where it stands

**The primary goal is now WebXR mixed-reality games in a browser: see WEBXR.md.** The OpenXR runtime
described here is the layer under it. Quest APK compatibility is out of scope.

The goal: wear the glasses, plug them into the Pixel, start a compatible VR game, look and move
around, and use hands or controllers. This page says, phase by phase, what exists, what was
measured, and what is not done. Nothing here is claimed for the real glasses unless it says so.

## What is being reused (and what is not)

| Project | Licence | Use here |
|---|---|---|
| Monado | BSL-1.0 | The OpenXR runtime itself, as a service inside the app (native/monado). |
| Pocket XR's Monado driver | GPL-3.0 | Head, Touch-shaped controllers, display and pacing feeds; extended here with hand joints, a separate aim pose and recentring. |
| Khronos loader and broker interface | Apache-2.0 / BSL-1.0 | Applications find the runtime through the standard broker query, which the app answers itself. |
| Basalt | BSD-3 | Camera and IMU position tracking (native/vio). |
| Monado's hand tracking models (Mercury) | see native/hands/LICENSES | Hand joints (native/hands). |
| openxr-dispatcher-open | MIT | Not used yet. The reference for Phase 5: it answers Quest-only extensions that Monado lacks. |
| xr-gate | MIT | Not used yet. An Air 2 Ultra capture, Basalt and hand pipeline to compare tracking settings against. |
| MiruXR, PortalVR | closed, no source found | Behaviour studied only. MiruXR does not list the Air 2 Ultra; PortalVR is a modified Monado for phones and tablets. |
| OVRPort's dispatcher | closed binary | Not used. OVRPort also strips entitlement checks, which this project does not do. |

## Phase 1: OpenXR stereo renderer and a standard sample

**Done on an emulator, not yet on the phone or glasses.**

- The runtime draws the left eye into the left half of the picture and the right eye into the
  right half. In the glasses' half-width 3D mode that is exactly what they expect, so no change
  to the runtime was needed: each eye gets a 960x1080 view with its whole field of view.
- A game needs 3D before it starts (changing the glasses' mode restarts the display link and
  would take the game's picture away). Tapping Play therefore starts 3D first, with the same
  bounded attempts as the desktop, and launches when it holds. If 3D cannot be had the launcher
  says so and does not start the game, because in 2D both eyes would see both halves.
- `tools/ui-automation/openxr_test.py` (11 checks, all pass): Khronos hello_xr (Vulkan) finds the
  runtime through the broker, the runtime starts with a head, two controllers and hand tracking,
  the session reaches FOCUSED, and the stand-in glasses display shows one cube per half, 22
  pixels apart between the eyes.
- Also tried: LÖVR 0.19.1 (open-source OpenXR engine). It finds the runtime, creates its instance
  and sees the hand trackers, then stops because the emulator's GPU lacks a Vulkan extension it
  needs (`VK_KHR_render_pass2`). That is the emulator; untested on the phone. hello_xr's OpenGL ES
  build crashes inside the emulator's arm translator before drawing; also untested on the phone.

Not done: any of this on the Pixel (it was not reachable over Wi-Fi debugging), and anything on
the glasses. The emulator translates arm64 code, so its frame timing says nothing about the phone.

When the glasses stay in 2D the runtime now draws one picture for both eyes (patch 0004), and the
launcher starts the game that way after 3D fails. The runtime is rebuilt on this Windows machine
with the Android NDK; no Linux is needed.

## Phase 2: 6DoF head tracking

**Not at game quality.** Measured earlier on the real glasses (native/vio/NOTES.md): position
holds when sitting still (241 s and 279 s runs), failed in a dark scene, and one uncontrolled
walk ended 1.8 m off after 7.4 m. Re-initialisation after the glasses are picked up took 56 s.
Position is therefore off by default in the desktop and gated by `PositionConfidence`.

To do, offline first: a replay harness that feeds a recorded camera and IMU session through the
tracker and scores drift and scale, so changes can be compared without the glasses; then the
0.40 degree camera rotation correction and the epipolar threshold change that are parked on the
`experimental/stereo-epipolar` branch. Recordings with known ground truth (a measured walk) do
not exist yet and need one session with the glasses.

## Phase 3: hands and controllers

**Plumbing exists, never verified on hardware.** Hand joints reach applications as
`XR_EXT_hand_tracking`; pinch is the trigger and a fist is the grip of a Touch-shaped controller,
with grip and aim poses from the tracked hand. There are no thumbsticks or face buttons from
hands. Not implemented: Bluetooth gamepads as OpenXR input (sticks and buttons only; a gamepad
has no position and none will be invented), `XR_EXT_hand_interaction`, haptics.

## Phase 4: third-party titles

Candidates that use the standard loader and are free to obtain: hello_xr (running, emulator),
LÖVR (reaches the runtime, emulator GPU limit), the Khronos conformance suite, Godot's XR demos
(hand tracking and room-scale movement; must be built), Wolvic (WebXR browser). None has run on
the phone.

## Phase 5: Quest titles

Unchanged from QUEST.md: unmodified Quest APKs do not run, and licence checks are not bypassed.
What the research adds: Quest builds commonly expect extensions Monado does not have
(`XR_FB_color_space`, `XR_FB_swapchain_update_state`, `XR_FB_foveation`,
`XR_META_performance_metrics`, `XR_FB_space_warp`), and no spatial-anchor extension exists in
Monado. The honest route for a title you own is an API layer that answers those harmlessly, as
openxr-dispatcher-open does; titles that require Meta's platform services or its entitlement
check stay out of reach.

## Phase 6: launcher and polish

The launcher lists installed OpenXR applications and starts them (now with the 3D gate). Not
done: per-title compatibility records, controller requirements, in-game recentre gesture,
recovery when the glasses drop out during a game.

## Mixed reality

Not implemented. The glasses are see-through, so the room is always visible and black is
transparent; camera passthrough is not needed for that. Applications that ask for the additive
blend mode are refused today (one-line runtime change, needs the rebuild). Spatial anchors and
room meshes have no implementation to reuse and depend on Phase 2.
