# Quest APK compatibility: where it stands (19 November 2026)

Goal: run native Meta Quest games on a Galaxy S21 Ultra with XREAL Air 2 Ultra glasses, through this app's OpenXR
runtime, with no browser. First target: Wall Town Wonders. No Quest game has been run yet: there is no game APK here.

## The chain

    game (patched by OVRPort) -> libopenxr_loader.so (OVRPort's dispatcher; the open one, MIT)
      -> libopenxr_loader_generic.so (the Khronos loader) -> libuxr_meta_layer.so (this repo, an API layer in the APK)
      -> Ultra XR's runtime (Monado), found through the app's runtime broker

- The dispatcher (github.com/Molier/openxr-dispatcher-open, built here with NDK 27.2 for arm64, 81 kB) reports the
  extensions Quest titles check for and answers colour space, foveation, swapchain state, performance metrics and
  PCM haptics itself.
- `questlab/src/main/cpp/meta_layer.cpp` answers XR_FB_passthrough (layers are taken out of the frame: the glasses
  are see-through), XR_FB_spatial_entity with query, storage and container, XR_FB_scene (room, floor, ceiling, walls,
  table tops from the app's room.json) and XR_FB_hand_tracking_aim (pinch strengths from the hand joints).
- `questlab/src/main/cpp/lab.cpp` is the synthetic test: it asks what a Quest mixed-reality game asks.

## Measured on the Galaxy S21 Ultra (glasses not plugged in)

| Run | Result |
|---|---|
| Khronos hello_xr (Vulkan), native, no browser | connects to the runtime and gets a session; its picture not checked (no glasses) |
| Synthetic test, loader -> layer -> runtime | 41 checks, 40 pass; the one failure is "no wall found", expected without the cameras |
| Synthetic test packaged like a converted game (dispatcher in front) | 40 checks, 32 to 33 pass |

In the passing run: all seven Meta extensions offered and enabled, passthrough created and 79 passthrough layers
removed from frames, scene query answered (room, floor 1.60 m under the origin, ceiling), planes located, an anchor
made 0.5 m ahead and found 0.000 m from where it was put, saved for the run.

## Open

1. **The runtime lets go of a native app whose screen is not in front.** In the later runs another activity came
   in front of the test within a fraction of a second, and about two seconds after connecting the runtime service
   was unbound ("the application left") while the test's frames went on. The app then stops serving the room, so
   the scene comes back empty. Whether a real game, which stays in front, is affected is not known.
2. **With the dispatcher in front, the system does not say it supports spatial entities**: the dispatcher answers
   xrGetSystemProperties itself and the layer's answer is lost. To fix in the layer or the dispatcher.
3. **Not offered by anything yet**: XR_FB_hand_tracking_mesh, XR_META_spatial_entity_mesh, XR_META_environment_depth,
   XR_FB_scene_capture, XR_FB_spatial_entity_sharing, XR_META_boundary_visibility. A game that requires one will stop.
4. **The test process crashes when its thread ends** (after the report is written): a thread-local destructor in a
   library that has been unloaded. Not yet traced.
5. Anchors are kept for one run only. The ceiling is assumed 2.5 m over the floor. The room's walls need the glasses.
6. Stereo output of a native app on the glasses: not checked (needs the glasses plugged into the S21 Ultra).
7. OVRPort removes some of a game's ownership checks as part of its patching. Nothing in this repo does that, and
   whether to run OVRPort on a game is its owner's decision.
