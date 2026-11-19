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
  Added later the same day:
  - XR_META_spatial_entity_mesh: one GLOBAL_MESH entity whose triangles are the scene's planes (two a plane). Flat
    faces, not a scan of the room's real shape.
  - XR_FB_hand_tracking_mesh: a hand model made in the layer (a slab for the palm, a six-sided tube a finger; 157
    vertices, 270 triangles), bound to the runtime's 26 joints. It is not Meta's model, and it has not been drawn by
    a game yet: its winding (counter-clockwise outward) and joint frames are checked only by the test's own sums.
  - XR_FB_scene_capture: completes at once (there is no room set-up screen; the room is found by looking).
  - XR_FB_spatial_entity_sharing and _user: a share request completes with XR_ERROR_SPACE_CLOUD_STORAGE_DISABLED_FB.
    Sharing itself (Meta's service) cannot be provided.
  - XR_META_boundary_visibility: there is no boundary; a request to hide it succeeds and its event is sent. The
    headers this module builds with (1.1.42) lack the extension, so `meta_extra.h` carries its definitions.
- `questlab/src/main/cpp/lab.cpp` is the synthetic test: it asks what a Quest mixed-reality game asks.

## Measured on the Galaxy S21 Ultra (glasses not plugged in)

| Run | Result |
|---|---|
| Khronos hello_xr (Vulkan), native, no browser | connects to the runtime and gets a session; its picture not checked (no glasses) |
| Synthetic test, loader -> layer -> runtime | 41 checks, 40 pass; the one failure is "no wall found", expected without the cameras |
| The same with the six further extensions (second build) | 60 checks, 59 pass; the same one failure |
| Synthetic test packaged like a converted game (dispatcher in front) | 40 checks, 32 to 33 pass (first build; not run again with the second) |

In the passing run: all seven Meta extensions offered and enabled, passthrough created and 79 passthrough layers
removed from frames, scene query answered (room, floor 1.60 m under the origin, ceiling), planes located, an anchor
made 0.5 m ahead and found 0.000 m from where it was put, saved for the run.

## Measured on the Galaxy S21 Ultra with the glasses plugged in (3840x1080, lying still on a table)

| Run | Result |
|---|---|
| Khronos hello_xr (Vulkan), native, no browser | owns the glasses at 3840x1080; a capture of the glasses display shows one view an eye, the cube 45 pixels apart between them (not the same picture twice) |
| Synthetic test, 34 s on the glasses | 60 checks, 59 pass; the runtime kept the application the whole time; 2001 passthrough layers removed |

The one failure is again "no wall": the glasses lay still, so the room model measured neither the floor (1.60 m
is the height setting) nor a wall. The Scene API against a real room needs someone wearing them and looking around;
the test takes `--ei room_seconds N` to wait for that before it queries.

## Measured with the glasses worn and the wearer looking around the room

Two runs of the synthetic test (50 s and 25 s of looking before the scene query): 60 checks, 60 pass, both times.
The scene came back with a floor (measured, 1.61 and 1.72 m under the origin), a ceiling, 3 walls and 3 to 5 table
tops, every plane locatable, the room mesh with two triangles a plane.

What these runs also showed:
- During a run the number of walls from dense depth went 0, 1, 2, 3, 2 (and 0, 2, 3, 2, 4): walls come and go. The
  layer now leaves a wall or table the room model has dropped out of queries, the room's container and the mesh
  (its handle stays good). In the first run two of the three walls were the same wall twice (1 cm apart).
- The floor estimate moved between 1.46 and 1.79 m below the cameras within one run.
- Table tops overlap (two within 15 cm of each other in the second run): several are the same surface.
- No hand was reported in either run (0 readings): no hand was held up (the wearer confirmed). See the third run.
- Nothing here was judged by eye: the test draws no picture of its own.

A third worn run, for hands (about 22 s of reading each hand in turn, both hands held up and pinching): a hand was
reported in 863 of 2640 readings (33 %), every one of them with a valid Meta aim state, and the index pinch strength
reached 1.00. So hand joints reach a native app and the aim state follows them; a hand was absent in two readings
of three, and this run cannot tell tracking drop-outs from the hand simply being out of view. (The wall check failed
in this run by design: it waited only 5 s before the scene query.)

## Open

1. **Without glasses, a native app whose window is not showing loses the runtime.** Cause found: with no glasses
   window Monado's client makes a surface in the application's own activity and waits two seconds for it
   (`Client.blockingConnect`, "Failed to create surface" in the log). If the phone's screen is asleep, or something
   is in front, no surface comes, the client unbinds the service ("the application left") and the app stops serving
   the room, so the scene comes back empty. With the phone awake the same build passes. With glasses plugged in this
   path is not taken (the runtime draws on the glasses window); that case is still to be run.
2. **With the dispatcher in front, the system does not say it supports spatial entities**: the dispatcher answers
   xrGetSystemProperties itself and the layer's answer is lost. To fix in the layer or the dispatcher.
3. **Not offered by anything yet**: XR_META_environment_depth (a depth picture per eye per frame, for hiding game
   objects behind real ones). The app's dense depth runs about once a second at 240x320, so this would be far slower
   and coarser than a Quest 3's. Also not offered: XR_FB_triangle_mesh, XR_FB_hand_tracking_capsules,
   XR_FB_composition_layer_alpha_blend and _settings, XR_META_passthrough_preferences, XR_FB_body_tracking (and,
   without the dispatcher in front, colour space, foveation, swapchain state and performance metrics, which the
   dispatcher answers). A game that requires one of these will stop.
4. **The test process crashes when its thread ends** (after the report is written): a thread-local destructor in a
   library that has been unloaded. Not yet traced.
5. Anchors are kept for one run only. The ceiling is assumed 2.5 m over the floor. The room's walls need the glasses.
6. Stereo output of a native app on the glasses: checked from a capture of the glasses display (above), not yet
   by eye. hello_xr clears to opaque grey, which the glasses show as a bright haze; a mixed-reality game clears to
   transparent black.
7. OVRPort removes some of a game's ownership checks as part of its patching. Nothing in this repo does that, and
   whether to run OVRPort on a game is its owner's decision.
