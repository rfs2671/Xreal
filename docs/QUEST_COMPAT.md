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

A fourth worn run, cued (left hand up for 20 s, then right for 20 s, pinching slowly; the test now counts each hand
on its own): left hand seen in 0 of 1200 readings; right hand in 1115 of 1200 (93 %), longest stretch without it 85
readings (1.4 s), every reading with a valid aim state, but 0 pinches and a strongest index pinch of only 0.22.
Correction: the wearer raised the RIGHT hand first and the left second, the reverse of the cue. Read that way the
run shows no labelling fault. The app's record of the session (journal.jsonl) has 760 hand-tracker frames labelled
right (about 25 s: the right hand, up from the cue until shortly after the switch) and 265 labelled left (about 9 s:
the left hand afterwards), and the test, which then read one hand at a time, was looking at the other hand both
times. The 93 % "right hand" in the second window was the real right hand for its first seconds, a 1.4 s gap, and
then the phone's touchpad pointer, which the app feeds to applications as the right hand while the phone's screen is
being touched ("the touchpad pointer is the right hand" in the log, 7 s into that window). That also explains the
steady pinch strength of 0.22 with no pinches. An earlier version of this section concluded that the tracker
mislabels hands; that conclusion is withdrawn.

What is still open from this run: the smallest thumb-to-index gap the tracker saw was 50 mm on the right hand and
30 mm on the left (19 and 26 mm in the third run, where 11 pinches were counted), so whether pinches are reliably
seen is not settled. And a test of hands must keep the phone's screen untouched, or the pointer stands in for the
right hand. The test now reads both hands every frame and reports "as the other hand" separately (checked on the
phone without glasses, 59 of 60); hand steadiness has not yet been measured with it.

A fifth worn run, cued and recorded (raw camera data kept privately, outside Git), both hands read every frame,
20 s a hand, 60 readings a second:

| Asked for | As that hand | As the other only | As both | No hand | Pinches | Strongest pinch |
|---|---|---|---|---|---|---|
| left | 15 | 0 | 1185 | 0 | 6 | 1.00 |
| right | 820 | 232 | 0 | 148 | 1 | 1.00 |

- The left hand was reported in every reading of its window, with no gap, and its pinches registered (the app's
  record: 7 pinches, smallest thumb-to-index gap 18 mm).
- In the right window, the first 232 readings (3.9 s) as "left only" and the 148 with no hand (2.5 s) are the
  wearer still holding the left hand up until the cue arrived and then changing hands; after that the right hand
  was reported in every reading (820, 13.7 s).
- Right-hand pinches were again not seen: the app's record has 0 right pinches and a smallest gap of 40 mm (50 mm
  in the fourth run), against 18 mm on the left. The one pinch counted in the right window was the left hand's,
  before the change. Two runs now agree: pinching with the right hand does not register.
- During the left window a right hand was also reported in 1185 of 1200 readings. The recording's pictures settle
  it: the right hand is in view, low and to the right, holding the phone (the cameras see far wider than the
  display). It is a real hand, correctly labelled; a hand at rest in the cameras' view is reported to games.

**Right-hand pinches, found and changed (app, `core/SessionTools.kt` PinchState).** Replayed through the tracker,
the right hand's pinches in this recording close only to 30 to 65 mm as the tracker measures them (the hand is seen
from behind and the fingertips hide each other); the rule was "under 33 mm". Every one of them does show as a fall
of 30 mm or more to a steady low. PinchState now also starts a pinch on that movement. On the same recording,
replayed on the S21 Ultra itself at 25 results a second: right hand 6 pinches before, 15 after, two seconds apart
as they were made; left hand 7 before, 9 after; the two resting hands 1 and 0 before and after. On the recording of
17 November: right 3 before, 4 after; left 3 before, 8 after (a run of quick dips to about 40 mm, 0.6 s apart). 
Live with the new build, worn, cued as before (20 s a hand, a pinch about every two seconds):

| Asked for | As that hand | As the other only | As both | No hand | Pinches the test saw | Pinches in the app's record |
|---|---|---|---|---|---|---|
| left | 1200 (100 %) | 0 | 0 | 0 | 10 | 11 |
| right | 867 | 247 | 30 | 56 | 8 | 6 |

The right hand was up for about 15 of its 20 s (the first 4 s are the left hand still up until the cue arrived, and
two of the test's 8 are its pinches), so 6 right-hand pinches are about one every two and a half seconds, where the
two runs before had 0. The right hand's smallest gap was 22 mm this time, so some of the 6 may have been caught by
distance; how many came from the new rule is not separated in this run.

A fault met on the way: if a native application starts before Ultra XR has taken the glasses (here: the app had
just been reinstalled and was opened before the glasses were plugged in), the runtime is made without the glasses
and without tracking, the application draws in its own window on the phone and gets no head, room or hands, and
nothing says so. Reopening Ultra XR does not mend it: the runtime is made once a process. The app had to be
restarted with the glasses attached. To fix in the app (make the runtime again, or refuse the session).

## Later on 19 November: what was closed

| What | How it stands now | Checked how |
|---|---|---|
| A game started while the runtime was made for another display state (glasses plugged in or out since) got no glasses, head, room or hands | The binder applications get now stays and the Monado server behind it is made again when an application arrives and the display is not the one it was made for (`openxr/OpenXrRuntimeService.kt`) | S21 Ultra, forced display change while another application was attached: the newcomer passed 59 of 60. Not yet with a real unplug and replug |
| The test process crashed after its report | Its OpenXR thread no longer ends (the runtime library leaves thread-local clean-up behind when it is unloaded; a game's render thread lives as long as its process) | No fatal signal in four runs |
| Walls and table tops came out twice | `core/RoomTidy`: same-facing walls within 25 cm, and table tops over each other at one height, are made one | Unit tests with the positions of the worn run. Not yet worn |
| "The floor height wobbles" (written above) | Withdrawn: that was the head moving. In replays the floor's height in the room's frame stays within 2 to 5 cm (standard deviation about 1 cm) | Three replays |
| Anchors lasted one run | Saved anchors are written to the game's data folder and read back at the next session. There is no saved map of the room, so they come back where they were relative to where the session started: start in the same place facing the same way | S21 Ultra: saved in one run, found 0.50 m from the origin in the next |
| With the dispatcher in front the system did not report spatial entities | The open dispatcher took Monado for Pico's runtime (Monado lists Pico's controller extension) and withheld Meta's structures. One-line patch (`tools/quest/prebuilt/monado-is-not-pico.patch`), prebuilt vendored | Test packaged like a converted game: 67 of 68 |
| XR_META_environment_depth not offered | Offered for OpenGL ES sessions: a 160x160 depth picture an eye drawn from the scene's planes. It hides game objects behind the walls, tables, floor and ceiling the room model has, and nothing else (no hands, people or furniture that is not a plane). Vulkan sessions are told the feature is unsupported | S21 Ultra: 30 of 30 frames, no GL error, the floor 8.2 m ahead at the lower edge of a level view |
| No installer or launcher for games | `tools/quest/quest_pack.py GAME.apk OUT.apk --install SERIAL` adds the layer (and the loader chain where missing), aligns, signs with the debug key and installs. Installed OpenXR apps and Quest games (marked `com.oculus.intent.category.VR`) are listed on the phone's launcher and in the home view's app pages and are started as games, not in a window | Packaged and installed the test with it; started it from the phone's launcher (67 of 68). Starting one from the home view on the glasses: not yet run |
| Right-hand pinches | See above: the closing movement now starts a pinch | Replay, then live |

Regression suites on the emulator with this build: desktop 40 of 40, room 21 of 21, enter 3 of 3. Unit tests: 137.

Every extension in the test's "Quest games also ask for" list is now offered when the dispatcher is in front.

## Real-game readiness (19 November, afternoon)

No feature was added in this round; it is about knowing what a real game does when it runs.

### What a run of a game now leaves behind

The layer writes, to logcat under the tag `UltraXRQuest` and to `uxr_quest_diag.txt` in the game's own folder
(`/sdcard/Android/data/<package>/files/`):

- the game's name, engine and the OpenXR version it asks for; every extension it enables, sorted into answered by
  the layer, passed to the runtime, and unsupported (those are left out so that the game goes on);
- the graphics API of the session (Vulkan or OpenGL ES), the blend modes the runtime takes, every swapchain
  (format, size, samples, layers), reference space, binding suggestion (with the profile's name), action-set
  attachment and session start, each with its result; the session's states; the interaction profile of each hand;
- the layers of a frame whenever what the game submits changes, and every failed `xrEndFrame`;
- `UNSUPPORTED CALL`: a function neither the runtime nor the layer has. The game gets a stand-in that answers
  `XR_ERROR_FUNCTION_UNSUPPORTED` in place of a null pointer, and each one is named and counted;
- `UNSUPPORTED FEATURE`: environment depth on a Vulkan session, passthrough on a game's own surface, hand removal;
- on a crash (SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE): one line with the signal, the address, the frame count
  and the last OpenXR call the layer saw, written before Android's own crash dump, which still follows;
- a summary every 1800 frames and when the session ends.

The dispatcher's own "unsupported:" lines (tag `OpenXRDispatch`) are kept and collected alongside; nothing of it
was rebuilt.

| Tool | What it does |
|---|---|
| `tools/quest/quest_inspect.py GAME.apk` | Reads the APK only. Package, engine, libraries, Meta's libraries, converted or as bought, manifest features and Quest metadata, every OpenXR extension named in the game's code sorted into layer / runtime / dispatcher only / nobody, blockers known before a run |
| `tools/quest/quest_pack.py GAME.apk OUT.apk --install SERIAL` | As before: adds the layer and the loader chain, signs, installs |
| `tools/quest/quest_diag.py PACKAGE --serial SERIAL --seconds 60 [--capture N]` | Starts the game as Ultra XR's launcher does, turns the dispatcher's detailed log on for the run, and writes a report (how far it got, unsupported things, failed calls, missing libraries, Meta platform lines, crash with the top of the stack), the whole logcat, the OpenXR lines alone, Android's crash dump, the layer's file and pictures of the glasses |
| `tools/quest/worn_test.py SERIAL` | Runs the test app while somebody wears the glasses, buzzes the phone as a cue for each phase, takes pictures of the glasses |

None of them changes anything inside a game, and none answers or removes a game's ownership check.

### Wall Town Wonders, the day its APK is here

    python tools/quest/quest_inspect.py WallTownWonders.apk --out wtw_inspect.txt
    python tools/quest/quest_pack.py WallTownWonders.apk wtw_uxr.apk --install R5CR60SFF8M
    python tools/quest/quest_diag.py <its package> --serial R5CR60SFF8M --seconds 90 --capture 4

The first says whether it is converted, which engine it is, whether it brings Meta's platform SDK, and which
extensions it names that nobody answers. The third says how far it got and what stopped it.

### Measured on the S21 Ultra without the glasses (the phone alone: no cameras, so no walls and no hands)

| Run | Result |
|---|---|
| Test app packed like a converted game, OpenGL ES, with the room view drawn | 76 of 77 (the one: no wall without cameras). 448 stereo pictures in 8 s, none refused; environment depth in all 448 |
| The same on a Vulkan session, each frame a drawn layer over the passthrough layer | 75 of 76 (the same one). 167 frames drawn, no Vulkan or swapchain failure; the depth provider is refused cleanly (`XR_ERROR_FEATURE_UNSUPPORTED`) |
| Touch controller bindings (`/interaction_profiles/oculus/touch_controller`) | Accepted; both hands report that profile; `xrSyncActions` 0 failures in 645 frames |
| Khronos `hello_xr` (Vulkan, its own renderer) packed with the layer | Runs; session FOCUSED; no failed call |

Found and fixed by these runs: the layer made its own floor-level space without `XR_EXT_local_floor` enabled when
the application had not asked for it (the runtime logged a validation failure and the layer fell back to the
eye-level space). The layer now enables the extension itself.

### Measured through the glasses, worn (19 November, 13:28 to 13:40, S21 Ultra)

Five runs of `worn_test.py` and one of `hello_xr`. In the first the room had no walls yet (the test asked 7 s after
tracking began); in the second the glasses were put down. The numbers below are from the third and fourth
(OpenGL ES), the Vulkan run and `hello_xr`.

| Asked | Result |
|---|---|
| Stereo on the glasses (3840x1080, one view an eye) | Yes, in every run, OpenGL ES and Vulkan: grids, cubes and `hello_xr` are drawn an eye with the offset between them |
| Frames | None refused in any run (1018, 1146 OpenGL ES; 1095 Vulkan) |
| Vulkan eye tints (left red, right blue) | Seen correctly by the wearer; the picture of the glasses agrees |
| `hello_xr` (Vulkan, its own renderer) | FOCUSED, no failed call, nothing unsupported, cubes in stereo. Its background is opaque grey: an application that clears to a colour hides the room |
| Hands | Tracked in 98 % of readings when held up (run 3). Told apart poorly when both are up: the hand asked for was often reported as both or as the other |
| Pinch as a Touch trigger | Arrives: 4 and 10 pulls (run 3), 4 and 6 (run 4), strongest 1.00; aim pose located in about half to two thirds of readings |
| Walls and tables in the scene | 1 wall and 3 tables (run 3), 1 wall (run 4). The room model itself held up to 4 walls |
| Environment depth (OpenGL ES) | In every frame. A cube 0.5 m behind a wall was hidden in 147 of 182 judged frames in run 3, in 32 of 129 in run 4 (after the room had been forgotten and found again); a cube in front was seen in 208 of 219 and 128 of 128 |
| Vulkan depth | Refused cleanly, as designed |
| Room alignment after a restart of the application | **Fails.** The anchor put on a wall was 0.73 m off the wall in the next run, and 2.70 m off after walking |

Why the alignment fails, from the runtime's log of the same minutes:

- walking, the position was lost twice ("0 landmarks, rotation only", 4 s and 2 s); every loss of more than 2 s
  makes the room model forget its surfaces and start again;
- the floor estimate wandered between 0.26 and 0.83 m below the cameras for a minute (a table top taken for the
  floor) before it came back to 1.6 m;
- putting the glasses down recentres the view when they are picked up.

So a game that places things on walls keeps them only while tracking holds; this is the next thing to fix, in the
room model and tracking, not in the Quest layer.

Not verified in this session: launching from the home view by the wearer (nothing was picked within 90 s),
unplugging and replugging the glasses.

### Finding the room again after a loss of position (19 November, 13:45 onwards)

`core/RoomRecall`: the walls as they stood before position was lost are remembered, and once two walls that cross
are found again the world is shifted back onto them (position only, not heading). Refused when the shift is longer
than the wearer could have walked or when two shifts fit equally well; given up after a minute.

- Made-up rooms (unit tests): 500 of 500 random losses undone, the worst 0.10 m off.
- On the glasses, three worn runs: **not exercised.** In the first, position was lost before two walls had been
  found; in the other two there was no loss of more than 2 s. So there is no hardware number for it yet; the
  runtime's log says "world put back" with the distance the first time it happens.
- What those runs do show: with no loss between two runs, of two anchors put on walls one was still on its wall
  and the other 0.25 m off, where that wall had not been found again (before: 0.73 m and 2.70 m).
- The losses seen come with fast head turns (one at 140 degrees a second, landmarks from 109 to 1): the cameras
  give 30 pictures a second and the picture smears. That is the tracker's limit, not the room model's.
- Occlusion varied from run to run: a cube behind a wall hidden in 77 of 111 and 190 of 209 judged frames; a cube
  in front drawn in 86 of 86 and 310 of 481.

### Walls and tables keep their identity (19 November, 14:00)

Found while looking into the uneven occlusion: the layer gave each wall and table top its entity by its place in
the room data's list, and that order changes from one reading to the next. A game holding "its" wall could find it
on another wall half a second later. Each surface found is now matched to the entity that was the same surface
before (same facing, within 0.35 m of its plane for a wall and 0.20 m for a table top, overlapping it); only a
surface never seen becomes a new entity, and one no longer found keeps its handle and is left out of queries.
The layer's log has a `ROOM` line with how many there are, how many are known but not found, how many were ever
made, and how many moved more than 0.25 m between readings.

Checked on the phone with the glasses lying on a table (no walls there): the run passes as before, 75 of 77, the
two being the missing wall and the anchor's distance after the glasses were moved. **Not yet seen with real walls.**

The uneven occlusion itself (a cube 0.3 m in front of a wall hidden in 171 of 481 judged frames in one run) is not
explained with certainty. In that run two walls were 0.22 m apart in depth and overlapping sideways: a cube in
front of the further one sits about at the nearer one's face, so hiding it may have been right (a cupboard in
front of a wall) or the same wall found twice. It needs a look in that room.

### The first real engine build: Unity's mixed-reality example (19 November, 16:14)

`SampleScene.apk` from the release page of Unity-Technologies/mr-example-meta-openxr (Unity 2022.3, the OpenXR
plug-in with the Meta OpenXR package, AR Foundation, Vulkan; free, as published by Unity). It needs no conversion:
it has the ordinary OpenXR loader and no Meta bridge library.

As packed at first it did not find the runtime ("Failed to find default runtime"): its loader is from 2022 and
looks only where a headset maker installs a runtime, and an app built for Quest does not declare the runtime
broker. `quest_pack.py --plain-openxr` puts both right (the kit's Khronos loader in place of the app's; the
manifest's target API lowered from 32 to 29 so the broker is visible).

    python tools/quest/quest_pack.py SampleScene.apk SampleScene-uxr.apk --plain-openxr --install SERIAL
    python tools/quest/quest_diag.py com.unity.example.mrmetaopenxr --serial SERIAL --seconds 45 --capture 3

Result on the S21 Ultra with the glasses attached, lying on a table (nobody wearing them):

- session on Vulkan, IDLE to FOCUSED; 1800 frames in the first summary, no failed `xrEndFrame`, no unsupported
  function called, no crash;
- 13 extensions enabled; passthrough, scene, scene capture, spatial entities with query and storage, and hand aim
  are answered by the layer; Unity's AR Foundation plane, anchor, camera and session subsystems all start;
- its welcome panel is drawn in stereo on the glasses;
- the Touch Pro bindings are refused (`trigger_haptic_fb` is not a path the runtime knows); the plain Touch
  profile is what both hands report;
- the picture has a pale sky behind the panel. On these see-through glasses that is a haze over the room; whether
  the app clears to transparent once its passthrough is on has to be looked at by a wearer.

Worn, 70 seconds (16:18): 3600 frames, no failed `xrEndFrame`, nothing unsupported, no crash. The pictures of the
glasses show the welcome panel with its sky behind it, then, after a button was pressed by pinch, the app's
"Default Input Controls" panel on a clear (see-through) background, with Unity's own hand model and the ray from
the hand drawn. Unity's log has the pointer press going through its UI input module. So hands, the aim ray, a
pinch as a click and the change to passthrough all work in a real Unity build.

Not yet done with it: its planes on real walls, its anchors, spawning its objects.

### What the wearer said of Unity's example, and what came of it (19 November, 16:20 to 16:40)

"The panel moved with me, started way too close. Once the video started playing hand tracking disappeared
completely. Screen is upside down. Hand gestures won't recognise unless I'm reaching far."

- **Upside down.** True of the whole view for the first 45 s of the fourth run (three pictures), righted with a
  jump of the head's position. It began when the glasses were picked up from lying still. The log had no
  orientation in it, so the cause is inferred: the estimator's idea of down. `core/HeadTracker` now compares the
  estimator's tilt with the accelerometer's (past 25 degrees only its heading is used, and it is restarted after
  half a second), takes its own tilt afresh when it is far wrong, and logs both every 10 s. Unit tests; not yet
  seen on the glasses.
- **Hands going.** The layer now counts, for each hand, how often the game asks and how often a hand is there
  (`HANDS` lines), and where the palm was when found (distance and height from the line of sight). In the fourth
  run Unity asked for both hands every frame throughout: the right hand was there in 5337 of 8816 readings, the
  left in 37 (the wearer holds the phone in it). So it is the tracking that loses them, not the game.
- **Why hands are lost**, from the pictures of the recording of 17 November: the hand tracker needs the hand in
  both cameras. Held up in front of the face it is in both; lowered to the side it is in one camera only (the left
  hand at the left edge of the left picture, the right at the lower right of the right one) and cannot be found.
  In the replays hands were found from 0.24 to 0.60 m away, so nearness alone is not the limit; "reaching far"
  brings the hand up and inwards, into the part both cameras see. The pictures are also dark where the hand is
  (the exposure follows the lamps).
- **Heat.** At thermal status "moderate" hand tracking was halved (15 to 7 results a second, where a pinch is two
  or three results long at 15). Now the room is looked at a third as often first and hands are halved only at
  "severe". Not measured for heat yet.
- **The panel's distance.** About half a metre by the offset between the two eyes' pictures, which is where
  Unity's template puts it for a headset with twice the field of view. Nothing was changed.

### Unity's example again, with the new logging (19 November, 16:50)

Worn, 80 s recorded. The first start of this session never reached OpenXR (no line from Unity or the loader, cause
not found); stopped and started again it ran: 5400 frames, no failed `xrEndFrame`, nothing unsupported.

- The view was upright from the start; the estimator's down stayed within 0.4 degrees of the accelerometer's in
  every check. The guard was therefore not exercised.
- Where the right hand was found (543 looks): 40 to 50 cm from the head in 465, 30 to 40 cm in 45, 50 to 60 cm in
  29, 20 to 30 cm in 4, never nearer; between level with the line of sight and 15 degrees below it in 360, 15 to
  30 below in 167, lower in 16, never above. The left hand was never found (the wearer holds the phone in it).
- The right hand was there in 1958 of 3599 readings in the first half minute, 1298 of 3600 in the second, none in
  the third.
- The phone was hot: thermal status 3 (severe) 20 s in, skin 41.8 C, processor 49.8 C, after an afternoon of runs.
  At severe hand tracking is halved and the room is looked at every 1.7 s; dense depth took up to 0.9 s a picture.
  Numbers from this run are numbers from a hot phone.

### "Hands are 12 inches below where they are" (19 November, 17:00)

The picture had been turned 10 degrees down since 13:49:47 that day: ten single taps on the touchpad's
picture-tilt button, one to two seconds apart, during a worn test (the wearer was holding the phone). Everything
drawn after that, in every run from the third worn run on, sat 10 degrees low against the real room; a hand at
45 cm is drawn 8 cm low by that alone. The setting is back at 0 (the factory calibration has the displays within
0.2 degrees of the motion sensor in pitch), and the buttons now need two taps within 0.6 s.

Earlier results of that afternoon that this touches: the judgement of wall grids and cubes by eye (none was
given), and the wearer's reports about Unity's example. The counts taken from the test's own pictures do not
depend on it.

Whether 10 degrees is all of the 12 inches is not known until the wearer looks again.

### Unity's example on a cool phone with the tilt at 0 (19 November, 17:07)

Worn, 90 s, phone at 31 C (thermal status 0 throughout). No failed `xrEndFrame`, nothing unsupported, no crash.

- The wearer got through several steps of the tutorial: the pictures show its plane-data step and, later, its
  menu video, with Unity's hand model drawn at the buttons. So presses arrive again.
- This time the left hand was the one found (2256 of 3600 readings in the second half minute), 30 to 50 cm from
  the head, level with the line of sight to 30 degrees below it, never above; the right in 68.
- The room model had one wall at most while it ran.

The wearer's word afterwards: the drawn hands sat on his real hands. So the 10 degree tilt was the whole of the
"12 inches", and with it at 0 the picture and the tracking cameras agree as far as an eye can tell.

### The hand's pointing ray (19 November, 17:30)

The wearer, of Unity's example: "why is the pointer logic so off, pressing takes forever."

The layer's aim state (XR_FB_hand_tracking_aim, which Unity's hand ray uses) pointed along the index finger's
first bone. A finger curls to pinch, so the ray swung down just as the pinch was made and the press landed
elsewhere; and the "pinching" bit needed the tips under 24 mm, where this tracker measures the owner's pinches at
17 to 31 mm. Now, as on Quest: the ray runs from the shoulder (taken from the head: 22 cm below the eyes, 17 cm
to the side, 6 cm back) through the hand, starts between the thumb's and index finger's knuckles, is smoothed,
and is held for the first 0.3 s of a pinch; the pinch bit starts under 33 mm and ends over 46 mm, the rule
measured on the owner's own pinches. Compiled and installed; not yet tried by the wearer.

### Unity's example with the new pointer, and a zoom-out setting (19 November, 17:25)

- A start made while the glasses were being plugged in was covered by Android's USB permission prompts and never
  drew; started again it ran: 3600 frames in 60 s, no failed `xrEndFrame`. The right hand was there in 3428 of
  3600 readings in the second half minute.
- Position was lost for 11 s and the room recall armed for the first time on the glasses ("5 walls are
  remembered"). Two walls were not found again within its minute, so it gave up without shifting anything: still
  no hardware number for it.
- The wearer: "looking straight I only see the top of the black panel and it moves with me, so I can't even move
  back." That is the template's panel, placed for a headset with about 100 degrees of view, half a metre away and
  below the line of sight, following the head. A runtime cannot move it.
- Settings, "World scale", now goes to 200 %. Above 100 % the application is told a wider field of view than the
  glasses have, so everything is drawn smaller and such a panel fits; the price is that nothing drawn sits on the
  room or the hands any more. It is a choice for menu-heavy applications, not a fix, and is left at 100 %.
- The wearer's word on the new pointing ray and pinch: better.

### Marking the room by hand (19 November, evening)

Asked for by the owner: "can't it be trained manually? I point from 4 corners to show walls or surfaces."

What there is now:

- `core/RoomPlan`: the corners the owner marks, kept in `files/room_plan.txt` with the table tops the cameras saw
  at that moment as landmarks. In a later session `fit()` places the plan in that session's world (a turn and a
  shift) from the walls and table tops the cameras find; it refuses when two places fit equally (a plain rectangle
  looks the same from both ends). On made-up rooms: 190 of 300 random part views of an L placed, none wrongly.
- When the plan is placed, applications get its walls, whole, in place of the found ones; after a loss of position
  the world is turned and shifted back onto it.
- The set-up is a mode of the test app, run by `tools/quest/room_setup.py SERIAL`: the place marked is where the
  wearer looks (a white ring where the middle of the view meets the floor), taken by a pinch of either hand or a
  tap on the phone's touchpad; a board at the top of the view says what to do next, how many corners are marked
  and whether a hand is seen; the phone buzzes for each corner. A pinch on the first corner closes the room.

Two worn tries failed, and why:

1. The place was where the hand pointed. A hand pointing at the floor is below what the cameras see of it, and the
   test app's ray was the runtime's old wrist-to-knuckle one. Four pinches, none with the ray on the floor.
   (XrHost's controller aim now runs along the arm, as the layer's hand aim does.)
2. Marking by looking, but nothing in the glasses said so. The wearer did not know what to do and put the glasses
   down after a minute. The board of words was added after this.

Checked on the phone with the glasses lying on a table: the board is readable and clear of the ring; a tap on the
touchpad marks a corner (it is a pinch of a hand, not a trigger: the tool now asks both ways); a room sent
straight to the app is kept, written and announced in `room.json`. **Not yet done by a wearer**: marking a whole
room, the hand-over of the corners through the layer, and a later session placing the plan.

Only walls are marked. Table tops are still the ones the cameras find.

Third worn try (18:14 to 18:19): **the owner marked his room.** 26 corners going round, closed on the first, handed
over through the layer and kept by the app ("room plan kept: 26 corners, 26 walls ... 2 table tops as landmarks");
the glasses showed "Room saved: 26 walls". The plan is 5.2 m by 7.5 m across, 20.6 square metres, 26.9 m round, with
walls from 0.4 to 2.8 m long: an irregular room with its nooks, not a rectangle, which is the kind `fit()` can
tell apart from itself. The wearer's word on the posts against the real corners: close enough. Not yet seen: a later
session placing this plan.

### A fresh session did not find the marked room (19 November, 18:22)

Ultra XR restarted, the plan read ("26 corners, 2 table tops as landmarks"), the room view run worn for 90 s. The
cameras found two walls; the plan was not placed.

Why: the first matcher asked every found wall to be one of the plan's walls. The owner's plan follows the nooks in
walls of 0.4 to 2.8 m; the cameras find the long straight run of a wall, which passes several of them. `fit()` now
takes a point every 20 cm along each found wall and asks that four fifths of them come within 20 cm of the plan's
outline, tries the places that lay two crossing found walls along two walls of the plan, takes the best only when
no different place does nearly as well, and settles it finely against the outline.

On made-up views of the owner's own plan (straight runs of 1.2 m or more, parts of them, 4 cm of noise, 200 random
places): **never placed wrongly**; placed with two runs 2 of 66, three runs 11 of 56, four runs 30 of 78. The L
of the earlier test: 190 of 300, none wrongly. So from walls alone a fresh session will place this room seldom and
slowly; the cameras found two walls in that minute. While the plan is not placed, the log now lists the walls and
table tops found every 8 s, so a fit that does not come can be studied from the log.

What would make it dependable: the wearer looks at two of the marked corners and taps at each (a turn and a shift
are two points), and the walls then only keep it in place. Not built.

### Placing the kept room from corners the wearer looks at (19 November, 18:40)

`RoomPlan.fitCorners`: in a fresh session the wearer looks at corners of the room and pinches, any of them in any
order; each is some corner of the plan, it is not said which. Every way of taking the two taps furthest apart for
two corners of the plan the same distance apart is tried and kept when every tap lies within 25 cm of a corner of
its own; a rival place counts only when its taps lie nearly as well.

On the owner's plan (26 corners, some 0.4 m apart), 300 random places, taps 7 cm off each way: three taps are never
taken as enough; four place it rightly in 214, wrongly in 2, and ask for another in 84; five rightly in 272,
wrongly in 1. So a wrong place is rare but possible, which is why the walls are then drawn in green for the wearer
to check against the real ones.

`tools/quest/room_setup.py SERIAL` now places a kept plan (and marks a room only when there is none, or with
`--new`). The app's side is checked on the phone (three corners refused, four placed). The tool's placing mode has
compiled and not yet run on the glasses.

## Open

1. **No Quest game has been run.** There is no game APK here. Everything above is measured with the synthetic test,
   which asks what a mixed-reality game asks but draws nothing and is not a game engine. What a real game adds:
   its own engine's start-up (Unity or Unreal), Meta's platform library (entitlement, achievements, user id), a
   Vulkan renderer in most titles, composition layers and input bindings the test does not use. The first run of
   Wall Town Wonders will show which of these stop it; that cannot be known before.
2. **A game must be converted before `quest_pack.py` is of use.** A Quest game as bought talks to Meta's runtime
   and platform; OVRPort makes it an OpenXR app that asks for the dispatcher. OVRPort removes some of a game's
   ownership checks as part of that. Nothing in this repo does, and whether to run OVRPort on a game is for its
   owner to decide.
3. **Environment depth for Vulkan games** is not provided (the feature is reported unsupported to them), and the
   depth there is has only the room's planes in it.
4. **Anchors across runs** depend on starting in the same place (no saved map of the room).
5. **The hand model has not been drawn by a game**; hand tracking's left/right labels and steadiness were good in
   the cued runs, but two recordings are little to judge false pinches from.
6. **Not yet seen through the glasses:** the merged walls and tables, a game started from the home view, the
   runtime made again after a real unplug and replug, and any picture a native app draws (judged from a capture).
7. Without glasses, an application whose window is not showing still loses the runtime (Monado's client waits two
   seconds for a surface). It does not arise with the glasses attached.
