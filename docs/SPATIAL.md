# How Ultra XR finds position, floor, walls and room (brief for a developer, 20 November 2026)

Written from the code as it is. Where something was measured, the measurement is in the document named beside it;
where it was not, that is said.

## What there is to work with

- XREAL Air 2 Ultra: two grayscale global-shutter fisheye cameras (640 x 480 each, a stereo pair at 30 Hz, used
  upright as 480 x 640), baseline 137 mm, toed out 9.7 degrees; an IMU at 1000 Hz. No depth sensor.
- Everything runs on the phone's CPU (Galaxy S21 Ultra, Snapdragon 888). The display is 46 by 27 degrees and adds
  light: it cannot darken the room.

## 1. Where the head is (6DoF)

- **Estimator: Basalt** visual-inertial odometry (stereo optical flow + IMU, a sliding window: 3 states, 7
  keyframes, square-root marginalisation), commit `0f3b2b52`, built as `libultra_vio.so` behind a small C API
  (`native/vio/`). No map, no loop closure, no relocalisation: it is odometry, and it drifts.
- **Calibration:** the unit's factory calibration ("fisheye624") fitted to Basalt's kb4 model
  (`core/FactoryCalibration.kt`). This unit's angle between the cameras is off by about 0.3 to 0.4 degrees; a
  correction is kept in `stereo_fix_unit.txt` and trimmed in small steps from the slope of the measured floor
  (`core/FloorCalibrator.kt`: a wrong angle makes a level floor rise or fall in a straight line with distance).
- **Fusion (`core/HeadTracker.kt`):** orientation is the gyroscope integrated at 1000 Hz, corrected towards the
  estimator's orientation; position is the estimator's, extrapolated by its velocity for at most 0.12 s. Poses are
  predicted to display time.
- **When position is believed (`core/PositionConfidence.kt`):** only while the estimator holds enough 3D landmarks
  and at least a couple are seen by both cameras. Otherwise the head is rotation-only and its last position is
  held; when position comes back the world is joined on where it was, so nothing jumps, but if the wearer walked
  meanwhile the world has slipped by those steps.
- **Restarts:** the estimator is started afresh when its "down" is tens of degrees from the accelerometer's, or when
  it wanders while the glasses lie still.
- **Starting (changed 20 November, see section 7):** the estimator starts after three frames in which the head is not
  turning fast. Until then it waited for a whole second of the motion sensor looking at rest, at the first start and
  after every restart.

## 2. Points in the room (sparse)

`core/StereoPoints.kt`, three pictures a second (fewer when the phone is warm): corners are picked in the left
picture and each is searched for in the right one along its epipolar line, one candidate distance after another
from 0.3 to 10 m; the distance whose patch matches best is taken if it is a clear winner. Each point is placed in
the world with the head pose of that picture.

## 3. Floor and table tops (`core/RoomModel.kt`)

- Points go into a grid of 5 cm boxes; a box counts once it was hit in two different pictures. Only the last 20
  seconds count.
- **Floor:** the lowest height at which counted boxes are spread over at least 0.75 square metres and which is at
  least 1.05 m under the highest the head has been. A level near the wearer's height setting is believed the third
  time it is found; one far from it needs about half a minute.
- **Table tops:** other heights that stand out, with a patch at least 30 cm wide.
- A surface is reported once found three times running. Once believed it goes only when the cameras look at where
  it should be, from within 3 m, and do not find it twelve times running.
- `core/RoomTidy.kt` merges doubles (walls within 12 degrees and 25 cm, table tops within 10 cm of height).

## 4. Dense depth and walls

- **Dense depth (`core/DenseStereo.kt`):** both fisheye pictures are redrawn as rectified pinhole views of
  240 x 320; disparity by semi-global matching (Hirschmueller) on a 5 x 5 census, 64 disparities, four directions,
  left-right check, then sharpened on the full-size pictures. Pure Kotlin, about 0.3 s a pair on the phone, run
  about once a second off the tracking thread. About half of all pixels get a distance; at 2 to 2.5 m a distance
  is good to about 10 cm (`docs/TRACKING_ANALYSIS.md`).
- **Walls (`core/WallMap.kt`):** every measured place between 0.4 and 1.9 m above the floor is counted on a floor
  plan of 5 cm squares; an upright surface becomes a straight bright line however rough one picture is. Lines are
  found by Hough voting, fitted, and the longest unbroken run is the wall. Walls are reported from the floor to
  2.3 m. These replace the walls of the sparse finder once there are any.
- **A fused mesh (`core/RoomMesh.kt`, a truncated signed-distance grid of 5 cm cells in hashed blocks, as in
  Chisel): built, measured, not used.** Its surface is too rough to hide things behind (several centimetres of
  disagreement between pictures), so nothing draws it and nothing is occluded by it.

## 5. The room the wearer marks, and finding it again

- **Marking (`core/RoomPlan.kt`):** the wearer looks at each corner where wall meets floor and pinches or taps,
  going once round the room. The plan (corners in x, z) is saved on the phone.
- **Placing it in a new session:** the world of a new session is turned and shifted against the plan's. The plan is
  laid onto the walls and table tops the cameras find: points every 20 cm along each found wall must lie within
  20 cm of the plan's outline for four fifths of them; candidate placings come from pairs of crossing walls; a
  placing is taken only if no different one does nearly as well. Or the wearer looks at any four corners again.
- **After a loss of position (`core/RoomRecall.kt`):** walls as they stood before the loss are remembered; once two
  non-parallel walls are found again, the one shift of the floor plan that lays new onto old is how far the world
  slipped, and the world is moved back (`HeadTracker.moveWorld`). Heading is not corrected this way: it comes from
  the gyroscope.
- One room at a time. Two rooms marked as one outline misaligned.

## 6. What a game is given

- **Native OpenXR:** Monado's runtime on the phone (`libmonado-service.so`), fed the head pose, hand joints and
  controller poses made from the hands (`xr/XrHost.kt`).
- **Quest applications:** an OpenXR API layer (`questlab/src/main/cpp/meta_layer.cpp`) answers Meta's extensions
  from the room model: `XR_FB_scene` (room, floor, ceiling, walls, table tops), `XR_FB_spatial_entity` with query,
  storage and container, `XR_META_spatial_entity_mesh` (the planes as triangles, not a real mesh),
  `XR_FB_passthrough` (the layers are removed: the glasses are see-through), `XR_META_environment_depth` for
  OpenGL ES sessions (a 160 x 160 depth picture drawn from the planes, so game objects are hidden behind known
  walls, tables, floor and ceiling; refused on Vulkan). Details and test results: `docs/QUEST_COMPAT.md`.
- **The desktop:** windows are placed in the same world (`desktop/`).

## 7. Starting sooner, and a saved map of the room (20 November 2026, evening)

Everything here was measured on the Galaxy S21 Ultra by replaying recordings through the app (`tools/pixel/lab.py`)
or through the shipped library in a test program. Nothing in this section has been worn yet.

### The estimator waited for a still head

The wrapper (`native/vio/src/ultra_vio.cpp`) held Basalt back until 30 frames running looked "at rest" (XR Gate's
limits: mean turn rate under 0.08 rad/s, that is 4.6 degrees a second). A head that is worn seldom gives a second of
that. `tools/tracking/vio_gate.py` works it out from a recording's motion data alone, and the replays agree with it to
a tenth of a second:

| recording | frames that count as at rest | first position, old gate | first position, new gate | a restart at a random moment would wait (old gate) |
|---|---|---|---|---|
| 17 November, at a desk, 45 s | 37 % | 17.5 s | 1.4 s | median 6.5 s, never for 12 of 45 moments |
| 18 November, 37 s | 47 % | 11.1 s | 1.5 s | median 3.7 s |
| 18 November, 78 s | 34 % | 16.3 s | 1.5 s | median 5.0 s, never for 17 of 79 |
| 18 November, moving about, 83 s | 7 % | 9.5 s | 1.3 s | never for 76 of 84 |
| 20 November, hands at the chest, 40 s | 16 % | 20.7 s | 1.2 s | median 10.6 s, never for 21 of 40 |

The same wait followed every restart, which fits what was logged worn: "0 landmarks, rotation only" for 26 and 60
seconds while walking on 18 November. Now the gate is three frames of a head turning slower than 1 rad/s (the limits
are options of the library, `startup_gate_*`; `native.KEY=VALUE` lines in `vio_options.txt` set them for a replay).

Is the path the same when the estimator starts while the head moves? The distance moved over every two seconds, where
both runs have a position, compared between runs (`tools/tracking/vio_loss.py`):

| recording | old gate against old gate again | new gate against old gate |
|---|---|---|
| moving about, 83 s, 23 m | median 1.0 cm, 90th 2.5, worst 7.3 | median 1.0 cm, 90th 4.0, worst 7.9 |
| 18 November, 78 s, 9 m | 0.0, 0.1, 2.5 | 0.2 to 0.3, 0.8 to 2.9, 3.0 to 8.2 |
| 18 November, 37 s | 0.0, 0.2, 0.2 | 0.1, 0.4, 0.7 |
| 20 November, 40 s | (one run) | 0.4, 0.5, 0.5 |

Position is trusted for 96 to 98 % of each recording in place of 48 to 89 %. No restart and no loss of position
happens in any of these replays with either gate, although the head turns at up to 254 degrees a second in one. **So
the losses seen while walking are not reproduced by any recording there is; what is fixed is the long wait after
them, not their cause.** A recording of a walk through the rooms is what is missing.

### A saved map: RTAB-Map beside the estimator

- **What runs:** RTAB-Map 0.24.5 (`librtabmap_core.so`, 19 MB, with PCL, g2o and SQLite inside; `native/slam/`),
  behind `libultra_slam.so` on a thread of its own at low priority. Basalt, the head tracker and Monado are as they
  were: the map never moves the head by itself.
- **What it is given:** room pictures (the pair the room finder already takes three times a second, with the
  estimator's pose for it), rectified to a pinhole pair of 480 x 640. Every picture until the head is found, then one
  in three; one in two while a map is made.
- **Making:** with no `files/room_map.db`, the session makes one: a place every 10 cm or 6 degrees, each with up to
  800 corners (GFTT), ORB descriptors and the corners' 3D positions from the two cameras; no pictures are kept. It is
  written when the desktop is left, when tracking stops, or when a replay ends (1.5 s; 11 MB for 144 places).
- **Finding:** with a file there, a session opens it read-only (1 to 2 s). A pair is recognised by its words, then
  matched to the place's 3D points (PnP with RANSAC, at least 12 agreeing, then bundle adjustment). The answer is where
  the estimator's world lies in the map's.
- **What the app does with it (`core/MapAlign.kt`, 7 unit tests):** with the head tracker's own transform that gives
  where the map lies in the app's world. One reading is not believed: the last five are kept and the one that at
  least two others agree with (12 cm, 3 degrees) is used. The first such is taken as it is. Later ones that differ
  move the app's world back: under 2 cm and 0.3 degrees nothing, up to 30 cm in steps of 1.5 cm and 0.25 degrees a
  reading, more than that at once. While the map is being found, the marked plan and the wall recall do not also move
  the world. A recentre carries the map along.
- **What is saved in map coordinates so far:** the desktop's windows (`DesktopWindow.map`). A session that finds
  itself in the map puts each back where it stood. The home screen says "learning the room" or "room recognised";
  Settings, Room memory, selected twice, forgets the map and learns anew.

Measured, the library alone on the phone (`native/slam/test/ultra_slam_replay.c`; the second stretch is given to it as
a new session whose world is turned 40 degrees and shifted 1.7 m, which it is not told):

| | map from | found in | first found | position against the estimator's own | a pair takes |
|---|---|---|---|---|---|
| moving about (83 s) | 2 to 45 s, 94 places | 45 to 83 s: 46 of 107 pairs recognised | 0.4 s in | median 8.3 cm, 90th 15.4, worst 17.0 | median 57 ms, worst 410 |
| 20 November | 2 to 25 s, 32 places | 25 to 40 s: 38 of 43 | 0.3 s in | median 4.6 cm, 90th 6.2, worst 6.4 | median 66 ms |
| 17 November's map, 20 November's pictures | | 0 of 110 | never | (a different place, as far as is known) | |

That reference is the estimator itself, which drifts between the two stretches, so the figures are the finding's
error and that drift together. Single readings scatter: 3 cm about their middle (median), 33 cm for the worst, and 3.5
degrees either way in heading; hence the agreement rule above.

Measured, in the app, across restarts of the app (`tools/tracking/map_report.py`): one replay makes and saves the map,
the app is killed, and the next replays of the same recording look for the head in it. A thing put down at the
head's place at second t of the first session is turned into map coordinates as a window's place is, brought back
through where the new session says the map lies, and compared with where the head is at second t of the new session:

| run | head found | a saved place, brought back: off by | within 10 cm |
|---|---|---|---|
| 1 | 3.0 s into the recording (5 s after the file was opened) | median 3.3 cm, 90th 10.1, worst 15.5 | 87 % of 62 seconds |
| 2 | 3.1 s | median 2.9 cm, 90th 11.4, worst 15.4 | 87 % |
| (before the worker's priority was lowered) | 3.0 and 3.5 s | median 4.6 and 3.6, 90th 10.3 and 11.3, worst 14.2 and 12.2 | 87 and 79 % |

**What that does and does not show.** It is the whole path the app takes (save, restart, open, find, place), on real
pictures, and under 10 cm for most of the recording but not all of it. It is also the kindest case: the same pictures
from the same viewpoints, and two estimator runs that differ by a centimetre or two. A real second session sees the
room from elsewhere and in other light. The target of 10 cm after a restart is **not yet shown worn.**

**What it costs.** With the map running, the estimator's time for a frame is 47 to 48 ms (median) against 35 to 40
without, and it gives 29.4 to 29.6 states a second against 29.8; at the default thread priority it was 50 to 54 ms.
A reading takes about 200 ms in the app (60 in the test program by itself). So the map is not free yet: it takes
processor time the estimator feels.

**Not done, not claimed (as of the evening; section 8 takes up the first four):**

- The world correction (drift taken out in steps, a large error at once) has run in unit tests only: in the replays
  the map never had to move the world. Whether a correction is seen as a jump is untested.
- Quest applications' anchors are still saved relative to the session's start (`meta_layer.cpp`); only the desktop's
  windows use the map.
- A saved map is not added to by later sessions, and holds one room (whatever was looked at while it was made).
- A restart of the estimator while a map is being made starts a separate piece of map (RTAB-Map's "new map"); not
  exercised.
- Loop closure while making a map happens (59 pairs matched earlier places in the 83 s recording); what it does to
  the map's accuracy has not been measured against anything.
- Room geometry, planes for games through the map, depth: untouched.

## 8. Anchors on the map, a map that grows, what it costs, corrections, and the room's surfaces (20 November 2026, late)

Again all of it measured by replay or by test programs on the Galaxy S21 Ultra. **A replay is the same pictures from
the same viewpoints in the same light; none of this says how well the room is found again in real use, and nothing
here has been worn.** `tools/tracking/worn_map_report.py SERIAL` reads the app's log after a worn session (map starts,
first recognition, every correction made at once, losses of position, restarts); the app writes a status line every
ten seconds for it.

### Games' anchors are kept in the map's coordinates

`room.json` now says where the saved map lies in its frame (`"map":{"known":true,"mp":[..],"myaw":..,"mv":..}`), once
the session knows. The Quest layer (`questlab/src/main/cpp/meta_layer.cpp`) keeps every anchor's pose in map
coordinates beside its pose in the room's frame, saves both, and from then on takes the anchor's place from the map:
a saved anchor is put back when the room is recognised and follows the map when the app corrects the world. With no
map known it behaves as before (relative to where the session started).

Tested with the synthetic Quest test on the phone and a stand-in for the map's place (`setprop
debug.ultraxr.fake_map x,y,z,yaw`), which makes one anchor 0.5 m in front of the head and saves it each run:

| run | the map stands at | the anchor of the run before is found | expected |
|---|---|---|---|
| 2 | where it stood | 0.50 m from this run's origin | 0.50 |
| 3 | 1 m to the side | 1.12 m | 1.118 (the anchor went with the map) |
| 4 | the same again | 0.50 m (run 3's anchor, made in the moved world) | 0.50 |
| 5 | back at the origin, turned 90 degrees | 1.12 m, moved 1.58 m | 1.118 and 1.58 |

Not tested: a real game, and the map's real place in a worn session (the stand-in replaces exactly that).

### A map that is added to

A saved map is now opened to be added to (`USLAM_EXTEND`; `room_map=find` in `vio_options.txt` gives the read-only
way back). The rules that keep existing anchors valid:

- the map's frame is that of its first place, for good;
- a session's places count as part of the map only while they are linked to that first place through the graph; until
  the session has recognised a saved place, nothing is said about where its world lies;
- the session works on a copy, and the copy takes the saved map's place only if the session ended tied to it. A
  session somewhere unknown leaves the file byte for byte as it was (checked by checksum).

So a new room is added by walking into it from a room the map knows, in one session.

| the library alone, on the 83 s recording | head found | recognised | position against the estimator's own |
|---|---|---|---|
| map from 2 to 30 s (58 places), find in 58 to 83 s | 3.6 s in | 28 of 70 pairs | median 9.9 cm, 90th 11.0, worst 11.9 |
| the same map added to from 28 to 60 s as another session (136 places), then find in 58 to 83 s | 0.4 s in | 43 of 70 | median 10.0 cm, 90th 11.1, worst 12.4 |
| the adding session itself (an unknown offset of its own) | at once | 31 of 90 | median 6.5 cm, 90th 8.6, worst 10.2 |
| 20 November's pictures added to that map (another place) | never tied | | the file's checksum unchanged |

In the app, across restarts (the test of section 7; a thing put down at the head's place in the first session):

| | head found | off by | within 10 cm |
|---|---|---|---|
| first session after the map was made, adding to it | 4.0 s | median 2.5 cm, 90th 10.1, worst 12.4 | 89 % |
| second, adding again | 4.6 s | median 4.3 cm, 90th 13.3, worst 16.2 | 78 % |
| third, reading only | 16.3 s | median 3.7 cm, 90th 14.4, worst 17.2 | 79 % |

**What is wrong with it:** walking the same room again adds its places again. The file went from 11 MB to 20 MB in two
sessions of the same recording, a picture took 280 and then 430 ms to look at in place of 120 to 190, the third
session needed 16 s to find itself, and opening the larger file once took 11 s. RTAB-Map's own remedy
(`Mem/ReduceGraph`) was tried: 247 places and 18 MB instead, and worse placing (worst 31 cm, 72 % within 10 cm); not
taken. Until places already known are not added again, a map should be learnt once and added to when there is a new
room, not every day. Also not exercised: a restart of the estimator in the middle of adding.

### What the map costs the estimator

`tools/tracking/map_cost.py`, one recording, runs without the map between the others (the phone warms up over a
batch, so a run is compared with its neighbours):

| | estimator, ms a frame (median) | states a second | hands, ms a result |
|---|---|---|---|
| no map (three runs through the batch) | 34.6, 41.8, 43.3 | 29.8, 29.7, 29.7 | 35.7, 39.2, 39.8 |
| map read, as section 7 left it (twice) | 48.4, 46.5 | 29.5, 29.5 | 40.1, 41.3 |
| worker kept to the small cores | 45.4 | 29.5 | 40.4 |
| OpenCV with one thread | 36.1 | 29.9 | 40.4 |
| both (**as it is now**; twice) | 37.5, 36.6 | 29.9, 29.9 | 35.0, 36.3 |
| both, and half the corners | 36.2 | 29.9 | 37.1 |

It was OpenCV's thread pool, not the map's own work: the map's pictures were being spread over every core. With one
OpenCV thread and the worker on the small cores, a run with the map is within what runs without it differ by, and no
state is dropped. A map picture still takes 140 to 250 ms in the app (it has the small cores to itself), which is why
it is given one picture in three. The hand tracker found hands as often as without (its counts differ more from run to
run than between settings). The estimator's 35 to 43 ms a frame is its own cost and is over the 33 ms a camera frame
lasts: it keeps up at 29.7 to 29.9 states a second because it uses two threads, but there is no headroom. That has not
been worked on.

### Corrections without a jump

- A small correction is no longer made at once: the step (a quarter of what is left, between 1.5 and 4 cm and up to a
  degree) is cut into twelve parts made 25 ms apart, so no frame moves the wearer's surroundings by more than 3.3 mm
  (unit test: twelve parts end where the whole step would, to 0.2 mm). These parts do not interrupt the room finder.
- Forced by replay (`map_test_slip` in `vio_options.txt`, `tools/tracking/map_slip_report.py`): at second 30 the
  app's world is pushed aside and the aligner is not told, as after a wrong join.

| pushed by | first seen by the map | within 2 cm again (by the app's own readings) | put back at once |
|---|---|---|---|
| 8 cm | 3.4 s later, 7.0 cm off | 7.7 s after the push (10.5 s with the later step rule; one run each) | no |
| 8 cm and 2 degrees | 3.0 s, 7.0 cm | 10.4 s | no |
| 20 cm and 3 degrees | 2.9 s, 20.3 cm | 17.2 s at 1.5 cm a step; 12.0 s with a quarter of what is left a step | no |
| 1 m and 25 degrees | 3.3 s, 99 cm | 4.0 s | yes, once: a jump, by design |

Against a run that was not pushed, the head's place came back to within what two runs differ by anyway (1 to 3 cm).
**What a replay cannot show is whether the wearer sees it:** the head's place in two runs of one recording differs by
1 to 2 cm from state to state, far more than a part of a step, so the smoothness itself is from the unit test and
the arithmetic, not from a measurement. Three seconds pass before the map says anything, because three readings must
agree; that is the price of not believing one.

### The room's surfaces are kept with the map

`core/RoomMemory.kt` (4 unit tests), `files/room_surfaces.txt`: the walls, table tops and floor height the room finder
has are kept in map coordinates, merged as it goes (the same wall seen again grows the one kept). A later session has
them the moment the room is recognised. Games and pages get both, each with a confidence in `room.json` (`"conf"`):
1.0 kept and found again in this session, 0.7 found in this session only, 0.4 kept and not seen yet.

By replay: 6 to 8 walls and table tops and the floor were back 0.4 s after the room was recognised (about 4.4 s into
the session; without it the first wall takes the time it takes to look at one). Walls the session then found itself
lay 1.3 to 5.6 cm (median) from the kept ones they were taken for, 7.9 cm at worst, over 1 to 4 walls a run.

This is a beginning, not better geometry: the surfaces are the same planes as before, only remembered. Nothing kept
is ever dropped (a wardrobe that was moved stays until the room is learnt anew), the kept surfaces are not used for
the floor the app itself stands games on, there is still no mesh and nothing for furniture that is not a table top.

## 9. Built on 20 November (night), measured only where the laptop could measure it

The phone was at 20 % and off its charger, and the owner had said to charge it before more testing. So this section
has two kinds of things, and they are kept apart:

- **measured on the laptop:** the geometry (it runs on a recording with `GeometryRecordingTest`), and everything that
  is arithmetic (unit tests; there are 220, two of which run only when given a recording);
- **built, unit-tested where it can be, and NOT measured:** everything that needs the phone. **None of it is switched
  on in the app:** each has a switch (`slam.Ultra/AddAlways=0`, `map_glide=1`, `room_mesh=1`, `native.thread_nice`
  and the estimator's settings in `vio_options.txt`), and the app as built behaves as section 8 left it until the
  numbers are in. One command runs all of it once the phone is charging: `bash tools/tracking/next_phone_batch.sh` (about 70 minutes; it refuses to start
  under 50 % unless the phone is charging). Until its numbers are in, none of these is an improvement, only a change.

### Not measured yet (needs the phone)

- **No duplicate places.** A map that is added to is now read first, like one that is only read. Places are added
  only after four pictures running were not recognised and the head is 40 cm from where one last was; once three
  running are recognised as places that were there before, it reads again. A session over a room the map has
  should add nothing and write nothing; a session into new ground should add that ground. What was being added must
  be tied to the map through the graph when the session ends, or the saved map is left as it was. The risk that is
  not known: whether RTAB-Map links the first new place to the map when it is switched from reading to adding
  (if not, new ground stays untied until the wearer walks back, and is thrown away if they do not).
  `Ultra/AddAlways=1` is the way of before.
- **A large correction glides.** In place of the jump, the world is turned and shifted back in slices 25 ms apart, at
  no more than half a metre and twenty degrees a second (a metre and 25 degrees: 2 s), every point on an arc to where
  it belongs. The slices compose exactly to the whole move (unit test, to a nanometre), and none moves a nearby point
  more than 2 cm. While it glides the map is not asked again. Whether a glide is easier on the eye than a jump is for
  the wearer to say.
- **The estimator's time for a frame** (target: under 25 ms; it is 35 to 43). Prepared: its threads ahead of ordinary
  ones (`thread_nice`, a new option of `libultra_vio.so`: Basalt's own threads are born with the priority of the
  thread that makes them), three and four threads, fewer optimiser and optical-flow iterations, a coarser corner
  grid, fewer keyframes, and combinations; each to be judged by its time and by whether the path stays the same.
  Nothing is known about which of these helps.
- **Reliability by replay:** the cameras covered for 6 and 15 s (`test_blind`), with and without the map, and sessions
  at 0.35, 0.6 and 1.6 of the light (`test_light`). A change of light made this way is a poor stand-in for a lamp
  switched on or daylight through a window; a walk through several rooms cannot be made up at all and needs a worn
  recording.

### Measured on the laptop: the mesh, furniture, and kept surfaces that are gone

`core/RoomMesh` is now filled in the map's coordinates from every depth picture while the map's place is known, kept
in `files/room_mesh.bin`, and read back when the map is opened; being in map coordinates it needs no moving when the
world is corrected. `/room_mesh.bin` on the local site gives it as triangles. `core/Furniture` finds what stands on
the floor in it; `RoomMemory.look` drops kept walls and table tops the cameras see through.

| | 83 s, moving about | 78 s, 18 November |
|---|---|---|
| depth pictures | 80 | 75 |
| mesh | 2516 blocks, 124 908 triangles, 10.3 MB | 1682 blocks, 52 654 triangles, 6.9 MB |
| written and read back | the same cells and triangles; 16 ms and 9 ms on the laptop | the same; 15 and 8 ms |
| adding a picture, on the laptop | 4 ms | 3 ms |
| furniture found | 6 things: tables 0.96 x 0.58 m and 0.70 x 1.08 m at 0.85 and 0.75 m, one 0.66 x 0.83 at 0.95 m, three "tall" | 3 things, all "tall"; one is 0.40 x 2.34 m and 1.83 m high, which is a wall the wall finder missed |
| the same furniture from each half of the recording | 3 of the first half's 6 are among the second half's 7 | none of the first half's 4 are among the second half's 2 |
| kept walls looked at again | of 3 real walls none dropped; of 2 made up across the wearer's path, 1 dropped (after 8 s) | of 1 real none; of 2 made up, 1 dropped (after 19 s) |

What that says, plainly:

- **The mesh is kept exactly, and it is as rough as it was.** Counting every cell that has been seen, a whole room
  hangs together as one thing; only cells seen about four times as often as the minimum, close to where the surface
  runs, separate into things. It is still not good enough to hide things behind.
- **Furniture is a first attempt and is not stable.** The sizes and heights of the tables found are plausible, and
  there is nothing to check them against. Half of what one half of a walk finds is not what the other half finds, and
  an unfound wall is taken for a cupboard. It is given to pages in `room.json` (`"furniture"`, each with a
  confidence) and to nothing else: games do not get it.
- **A kept surface that is gone is dropped when it is looked through for eight pictures more than it is seen in
  place; one that stands is not.** On two recordings no real wall was dropped; one of two made-up walls was each
  time. The other was never looked through from within 3 m for long enough.

## 10. What the phone said of section 9 (20 November 2026, late night)

The owner said to run the tests on the battery that was left, until it ran out; it did, at 1 %. So this is one
evening's runs on one recording (83 s, moving about one room), most things once. **Replays, not worn.** The build with
everything below switched on together was made after the phone went dark and **has not itself run on the phone.**

### Places are added only where the map has none: taken

The library by itself (`ultra_slam_replay`, `UX_EXTEND`; each session's world turned and shifted by its own unknown
amount):

| a map of 2 to 30 s (58 places), then sessions over | places after each | written | each session found itself |
|---|---|---|---|
| 2 to 30 s, three times | 58, 58, 58 | nothing: nothing was added; the file is as it was | 0.4 s in; 79 of 80 pairs; 1.9 cm (median) from the estimator's own |
| 28 to 60, 2 to 83, 40 to 83, 2 to 83 | 112, 129, 132, 135 | each | 0.4 s in; median 6.4 to 9.4 cm, 90th 9 to 20, worst 9.5 to 20.7 |
| the same first session the way of before (every place added) | 136 after one session; 11 MB to 20 MB in two (section 8) | | |

Finding in 58 to 83 s afterwards: 62 of 70 pairs recognised, 0.4 s in (43 of 70 with every place added; 28 of 70, 3.6 s
in, in the map of 2 to 30 s alone); position median 9.6 cm, 90th 20.4, worst 21.0 (10.0, 11.1, 12.4 with every place
added). **So the map stops growing and is recognised more often, and the worst positions in ground that was added
are worse (20 cm against 12).** That is the price as measured, and it is not understood yet.

Two things had to be put right to get there, both found by these runs:

- New ground stayed untied to the map until the end and was thrown away. Now the first new place is linked to the last
  recognised one by the way the head went between them (0.1 to 1.8 m in these runs).
- The question "is this place linked to the map's first" was asked of `Rtabmap::getLastLocationId()`, the id given to
  the last pair; a pair much like the one before is merged into that one and its id is gone. It is asked of the last
  place that is really in the graph now.

In the app (one run after a map was made): nothing was added and nothing written (7.6 MB before and after); a thing
put down in the first session came back median 2.2 cm off, 90th 9.0, worst 12.0, within 10 cm for 93 % of 69 seconds.

### The estimator's time for a frame: 32 ms, not 25

The same settings do not give the same time twice: the unchanged estimator took 29.0 ms in the first run of a batch
and 40.4 to 41.6 ms in later ones. The phone's warmth (and, here, its emptying battery) moves the figure more than
any setting, so only neighbours in a batch are compared:

| | batch 1, ms a frame | batch 2 | states a second | hands, ms |
|---|---|---|---|---|
| as it was (2 threads, 7 optimiser rounds) | 29.0 first, 41.6 last | 40.4, 41.3 | 29.7 to 30.0 | 32 to 40 |
| the estimator's threads ahead of others (`thread_nice` -8) | 28.6 (second run) | 35.8 | 29.9 | 37, 40 |
| 4 threads | 47.9 | | 29.4 | 40 |
| 4 threads, ahead of others | 32.4 | | 30.0 | 45 |
| 5 optimiser rounds | 38.9 | | 29.8 | 40 |
| **ahead of others, 3 threads, 5 rounds (taken)** | 32.4 | 32.1 | 30.0, 30.0 | 43, 44 |

The path is the same (two-second spans against the old settings: median 0.4 and 0.8 cm, worst 3.5 and 6.3; two runs
of the old settings differ by 0.6 to 0.9, worst 3.9 to 8.5). The hand tracker pays about 3 ms. **25 ms is not
reached**: on a warm phone the estimator is at 32. What would be next is not settings: fewer corners with a check that
tracking holds while walking, and the optical flow on the GPU.

### A large correction glides: taken up to 1.5 m

A push of 1 m and 25 degrees (`s_100g`): seen by the map 3.1 s later, glided back in 2.3 s, within 2 cm of the map
6.0 s after the push; afterwards 2.2 cm (median) from a run that was not pushed. The head's place changed by up to
53 mm between two states while it glided (12 to 15 mm between two runs anyway): more than the slices were meant to
allow, because they were sized at the world's origin and not at the head. That is changed (arithmetic, not measured
again). A glide of 6 m took 14 s (below), so beyond 1.5 m the world is put back at once.

### Covered cameras: the map recovers the room; the estimator must not be believed straight away

`test_blind`: the cameras see nothing from second 30 to 36 (and to 45).

| | position believed again | where the world then was | put back |
|---|---|---|---|
| 6 s covered, with the map | 1.1 s after the pictures return | 6.24 m and 4 degrees from where it belongs | recognised 18 s after the pictures returned, glided back over 14 s |
| 6 s covered, no map | 1.1 s | as far off | never |
| 15 s covered, with the map | 1.2 s | 6.27 m and 12 degrees | recognised 29 s after the pictures returned |

The reason for the 6 m is in the estimator's own output: it comes back "trusted" a second after it sees again, while
it is still flying at up to 6 and 11 m/s from seconds of the motion sensor alone (31 jumps of more than 5 cm between
states). The world is joined on to that. **This is the worn "everything drifts after I lose tracking" seen in a
replay for the first time.** Built on it and not measured (the battery ended): `restart_after_loss=1` starts the
estimator afresh when it sees again after more than 1.5 s without position, and the map is asked with every picture
after a loss or a new world (that part is on). Runs `x_blind_r`, `x_blind15_r`, `x_blind_r_nomap` are ready.

### Another light

Every picture made darker in the finding session (`test_light`): at 0.6 of the brightness the head was found 8.4 s in
(4.1 s as recorded) and a saved place came back median 3.5 cm off, 90th 12.8, within 10 cm for 80 %; at 0.35, 8.0 s,
3.0 cm, 11.6, 83 % (as recorded: 2.1 to 2.2 cm, 9.0 to 9.5, 90 to 93 %). A picture scaled darker is not a lamp
switched on.

### The mesh in the app: stays off

With `room_mesh=1` the estimator's time and rate were as in the runs beside it (38.6 ms, 29.9 states a second), the
mesh was written (9.9 MB) and up to 7 pieces of furniture were reported. But **three of eleven kept surfaces were
dropped as "seen through" in a replay of the very room they were found in.** Either they were never there (the
sparse finder's table tops are often wrong) or the rule is too quick; until that is known it is not to be on.

### What is on in the build now, and what is not

| | |
|---|---|
| places added only where the map has none | on |
| the estimator ahead of other threads, 3 threads, 5 rounds | on |
| large corrections glided, up to 1.5 m | on |
| the map asked with every picture after a loss | on (not measured by itself) |
| the estimator started afresh after a loss (`restart_after_loss=1`) | off: not measured |
| the mesh, furniture and dropping in the app (`room_mesh=1`) | off: drops surfaces it should not |

## 11. Recovery after a loss of position (21 November 2026): built, and measured on the phone by replay; NOT worn

(What follows down to "Measured on the phone" was written before the phone could be used; the measurements are below.)

The phone was at 1 % and off its charger, so nothing here has run on it. What could be measured without it was: the
previous build's behaviour, from the states it recorded in the covered-camera replays; and the new judge of the
estimator's states, run over the recorded states of about forty replays. What the estimator does once it is started
afresh after a loss, and whether the saved map then gives the position in time, can only be seen on the phone:
`bash tools/tracking/recovery_phone_batch.sh` (35 minutes, phone charging). Rollback: the tag `pre-recovery` and
`C:/uvio/rollback/app-c4c43c0.apk` are the build of before; in the new build `recovery=0` in `vio_options.txt` is
the old behaviour, which is how the batch compares the two.

### The previous build, measured (`tools/tracking/recovery_report.py` on the runs of section 10)

| cameras covered | position still used while blind | in use again | how far out then | worst afterwards | within 10 cm for good |
|---|---|---|---|---|---|
| 6 s, with the map | 0.47 s (up to 10 cm wrong) | 1.0 s after the pictures return | 135 cm | 648 cm | 37.7 s after the pictures return |
| 6 s, no map | 0.37 s | 1.1 s | 151 cm | 658 cm | never |
| 15 s, with the map | 0.47 s | 1.1 s | 240 cm | 621 cm | never in the 38 s that were left |

Why, from the recorded states: with no corners the estimator ran on the motion sensor alone and reached 4 m/s; when
the pictures came back its velocity fell to half a metre a second at once, enough landmarks were counted, and a second
later its position was used although for eight more seconds it moved by 20 to 29 cm between states.

### What is built

1. **Every state is judged before its position is used** (`core/TrackingHealth`). Blind (fewer than 25 corners
   tracked; walking and turning it has 100 to 190, covered none) or flying (a velocity, or a step between two states,
   of more than 3 m/s; the fastest head in the recordings is 1.6): the position is not used from that very state on,
   and OpenXR is told the position is not tracked, as it always was while position is held. Over the recorded states of
   about forty replays of five recordings in which nothing went wrong it never fired: the share of states shown is
   the same to a tenth of a percent. Over the covered-camera runs it fires on the first blind state (0.03 s in place
   of 0.4 to 0.5 s). A third rule, that the position must move as the velocity says, was thrown out by the same
   benchmark: 8 to 14 false losses in every 83 s of ordinary walking.
2. **After a loss of more than half a second the estimator is not carried on with.** At the first state in which it
   sees again it is started afresh (it starts within three frames since section 7), so what follows begins from rest
   and not from where it flew to.
3. **Position counts again only when the fresh estimator has behaved** for 0.7 s and has had enough landmarks seen by
   both cameras for a second.
4. **The saved map first.** While the fresh estimator settles, the room's pictures go to the map with the estimator's
   poses although the app does not use them yet; when two readings agree where the new world lies in the map, the world
   is joined on exactly there (`MapAlign.restoreReading`, `HeadTracker.joinNextAt`): the wearer is where the map has
   them, and nothing has to glide or jump afterwards. Only if the map has said nothing a second and a half after the
   estimator is fit is the world joined on where the head was held, as before, to be corrected later.
5. **Meanwhile the world is frozen:** the head keeps its last position and turns from the motion sensor, as it always
   did while position is held. Nothing is hidden.

If it works as built, position should be back about 1.2 to 1.5 s after the cameras see again, at the map's accuracy.
That is arithmetic about timers, not a measurement.

### Measured on the phone (S21 Ultra, replay of the 83 s walk with the cameras' pictures blanked; `C:/uvio/vio_bench/recovery2`, `recovery4`, `recovery5`)

As first built, the health gate did its part and the map did not. Two faults were found and mended, each measured:

- **The map library opened its file again whenever the estimator began a new world**, on its worker's small cores:
  4.3 s (its own log), in which it looked at no picture. So the map "said nothing in time", the world was joined on
  where the head was held (2 to 3 m out after a walk), and was put right 10 to 16 s later. The same 4.3 s were lost at
  the start of every session. Now `triggerNewMap()` (which for a read map drops exactly the odometry cache, the last
  localisation and the correction) and nothing is opened.
- **The map got its pictures from the room's loop**, which hands one over only between its own work and not at all
  while the estimator is not in use. The search has pictures of its own now, seven a second, from the first state in
  which the fresh estimator sees (`Tracking.mapSearchLoop`), and for at most three seconds of a search the library's
  worker has all the cores at an ordinary priority (`Ultra/SearchUrgentMs`, 0 to switch off): a picture then takes
  40 to 140 ms in place of 350 to 620.

| cameras covered | | position still used while blind | in use again after the pictures return | how far out then | worst afterwards | within 10 cm for good |
|---|---|---|---|---|---|---|
| 6 s, map | before (`recovery=0`) | 0.63 s | 1.0 s | 143 cm | 802 cm | after 42 s |
| | as first built | 0 | 2.8 s | 202 cm | 203 cm | after 13.1 s |
| | file not reopened | 0 | 2.4 s | 7 cm | 7 cm | at once |
| | and the search urgent (twice) | 0 | 1.3 s, 1.3 s | 11 cm, 9 cm | 12 cm, 10 cm | after 4.5 s, 3.1 s |
| 15 s, map | before | 0.63 s | 1.1 s | 237 cm | 726 cm | never (38 s left) |
| | now | 0 | 1.5 s | 6 cm | 8 cm | at once |
| 2 s, map | now | 0 | 2.5 s | 10 cm | 13 cm | after 3.5 s |
| 6 s, no map | before | 0.67 s | 1.1 s | 147 cm | 809 cm | never |
| | now | 0 | 1.3 s | 204 cm | 208 cm | never: nothing can say where the wearer walked to |

- No runaway in any run of the new way: the furthest the world was ever out is what the wearer walked while blind
  (and with the map, 13 cm).
- With nothing going wrong the new way changes nothing: against `recovery=0` over 40 two-second spans the paths
  differ by 0.0 cm (worst 0.1), position is in use for 99 % of the recording both ways, the estimator takes 20 ms.
- The 2.5 s after the two-second cover: the map had the head at 1.6 s; the fresh estimator was not yet counted on.
- The error figures carry the reference's own noise: with nothing going wrong the same comparison reads 3 to 4 cm
  median and up to 9 cm. So "11 cm at the moment of return" is at the edge of what this measurement can tell from
  the 10 cm asked for, and is not claimed as under it.
- The head is found at the start of a session 4 to 6 s into the recording now (8 to 11 before).

Not measured: any of this worn; a loss in a place the map has never seen (then it is the no-map row); a loss by fast
motion or darkness rather than covering (the dimmed-picture runs of section 10 were not repeated).

### Worn, 21 November 2026: it FAILED, and why (`C:/uvio/vio_bench/worn1121`, `hand1`)

Worn on the S21 Ultra, a hand over the cameras twice. Position was hidden 0.7 s after the hand went up (by the older
count of landmarks, not by the new judge), and when the hand came away the estimator was believed at once: the world
was 3.88 m and 3.38 m out, and put back by the map 1.8 s and 1.4 s later. The judge of this section never fired:
under a real hand the pictures are dark and noisy, the corner detector goes on finding 108 corners in them, and the
velocity stayed under 1.2 m/s. The flat grey pictures of the replays above give no corners, which no worn cover does.
So the table above measures a cover that does not occur, and is not evidence for worn use.

Also seen: lying on a table the estimator was started afresh every five seconds, and the map search (given pictures
of its own that morning) handed each wandering to the map as places: 316 to 421 places while nothing moved.

Mended:

- The loss is told from the landmarks as well (`TrackingHealth`, "starved"): none seen by both cameras, or under
  5 at all, for 0.7 s, after it has once seen; blind from then until 8 landmarks, 2 by both. `starve=0` is the way of
  that morning. In 1.9 hours of earlier replays of walking that state never lasted over 0.63 s.
- The map is given pictures only from an estimator that sees, on glasses that are not lying still.
- The replay's cover can be what a hand gives: `test_blind_noise=20` (dark, noise of 20 grey levels, new in every
  picture). It gives 108 corners, 0 to 2 landmarks, under 1 m/s: what was seen worn.

By replay with that cover (the 83 s walk, S21 Ultra):

| cover | | still used while blind | in use again | how far out then | worst afterwards |
|---|---|---|---|---|---|
| 6 s, map | the morning's build (`starve=0`), twice | 0.5 s, 0.7 s | 1.2 s | 129 cm, 136 cm | 169 cm, 174 cm; right after 21 s, 19 s |
| | now, twice | 0.6 s, 0.5 s | 1.6 s, 1.7 s | 8 cm, 7 cm | 9 cm, 8 cm |
| 20 s, map | now | 0.6 s | 2.1 s | 5 cm | 6 cm |
| 2 s, map | now | 0.5 s | 2.4 s | 7 cm | 9 cm |
| 6 s, no map | now | 0.6 s | 1.6 s | 155 cm | 156 cm, never right |

Nothing going wrong: no loss called, no restart, the path differs from `starve=0` by 0.7 cm median (worst 4.1).
For the half second before the loss is told the head is shown up to 12 cm wrong. NOT worn again yet.

### The 20 cm in newly added ground: looked into, no cause found, nothing adopted

On the phone, the library alone: a map of 2 to 30 s, then the sessions 28 to 60 s and 2 to 83 s added, and the worst
position of the second session against the estimator's own.

| | places after both | worst position in the second session (each figure one run) |
|---|---|---|
| every place added (the way of before) | 310 | 14.3 cm |
| as built (link weight 400) | 126 to 129 | 23.6, 16.2, 19.6, 13.1 cm |
| link weight 40 | 128 to 131 | 13.5, 16.1, 15.2, 15.2 cm |
| link weight 10 | 129 to 130 | 14.8, 20.5 cm |
| link weight 100 / 4000 | 130 / 131 | 17.2 / 16.4 cm |
| adding after 2 misses and 0.15 m / after 1 miss | 136 / 189 | 17.6 / 18.4 cm |

The same setting gives 13 to 24 cm from one run to the next, which is as much as the settings differ. So the "20 cm
against 12" of section 8 was one draw from that spread and not the price of adding fewer places, and no setting is
repeatably better: nothing is changed. Duplicates stay away in every variant but the last. What the spread itself
comes from (the order in which the worker meets the pictures, and a reference that drifts with the estimator between
the two stretches) is not separated yet.

## What is known to be weak (measured, `docs/QUEST_COMPAT.md`, `docs/TRACKING_ANALYSIS.md`)

- **A saved map only since 20 November** (sections 7 and 8), for the desktop's windows, games' anchors and the room's
  surfaces; **not yet worn**, so how well a room is found again from other viewpoints and in other light is not
  known. Before it, an anchor put on a wall was 0.73 m off in the next run and 2.70 m off after walking.
- **A map that is added to keeps growing** when the same room is walked again (section 8). A change against that is
  built and not measured (section 9).
- **After the cameras are covered for some seconds the world is metres out** until the map is recognised again, which
  took 18 to 29 s (section 10). Mended and measured by replay (section 11): back in 1.3 to 2.5 s where the map has the
  head, 6 to 11 cm out. Not worn.
- **The estimator takes 32 ms a frame on a warm phone**, not the 25 asked for (section 10).
- **Multi-room use has not been tested at all**: there is no recording of a walk through several rooms.
- **Position was lost while walking** (seconds at a time, worn, 18 and 19 November); each loss of more than 2 s makes
  the room model forget its surfaces. The long wait after a loss is fixed (section 7); why it is lost is not known,
  and no recording reproduces it.
- **Planes only.** No usable mesh, no occlusion by real objects other than the planes, nothing for furniture that
  is not a table top.
- **Walls only where the wearer has looked**, and only as straight runs.
- **Steam (PC VR) games are not supported.** Nothing streams from a PC. The room and tracking above would serve such
  a client (head and hand poses out, pictures in), but none is built.

## Where the code is

`native/vio/` (Basalt and its wrapper), `native/slam/` (RTAB-Map and its wrapper), `core/TrackingHealth.kt`, `core/MapAlign.kt`, `core/RoomMemory.kt`, `core/RoomMesh.kt`, `core/Furniture.kt`, `tracking/Tracking.kt` (the loops that tie it together),
`core/HeadTracker.kt`, `core/PositionConfidence.kt`, `core/FactoryCalibration.kt`, `core/FloorCalibrator.kt`,
`core/StereoPoints.kt`, `core/RoomModel.kt`, `core/RoomTidy.kt`, `core/DenseStereo.kt`, `core/WallMap.kt`,
`core/RoomMesh.kt`, `core/RoomPlan.kt`, `core/RoomRecall.kt`, `xr/XrHost.kt`, `questlab/src/main/cpp/meta_layer.cpp`.
Research and roadmap: `reports/XREAL Quest level spatial roadmap.md`, `docs/SPATIAL_RESEARCH.md`.

## 12. "6dof anchoring is shaky at best" (worn, 21 November 2026, afternoon): the steps between states

Replay of the worn two minutes (`worn1121b.uxrec`, the first 108 s of `live-1795294419621.uxrec`, S21 Ultra).
The head given to the renderer was the newest state carried forward by its own speed, and each new state
replaced the last outright. Thirty times a second the head's place therefore jumped by what the last state's
speed had been wrong by. `HeadTracker` now keeps what a new state changes as an offset that dies away in 50 ms
(`GLIDE_S`), and approaches the turn's correction at every motion sample instead of halfway at every state
(`glide=0` in `vio_options.txt`: as before).

Measured on the head as the renderer gets it, asked for 60 times a second during the replay (`TAG-render.csv`,
`tools/tracking/render_judder.py`): what is left of the head's place after smooth motion is taken out, per
frame, two runs each.

| head | before, rms mm | after, rms mm | before, 99th percentile | after |
|---|---|---|---|---|
| still (under 0.05 m/s) | 0.62, 0.66 | 0.18, 0.17 | 2.2, 2.3 mm | 0.65, 0.65 mm |
| moving slowly (to 0.2 m/s) | 1.74, 1.91 | 0.70, 0.74 | 5.4, 5.6 mm | 2.4, 2.3 mm |
| walking | 3.46, 3.50 | 1.67, 1.56 | 9.3, 10.3 mm | 5.8, 5.3 mm |

At 2 m, 5 mm is 6 pixels of the display. The turn (from the gyroscope) is unchanged by this: 0.03 degrees a
frame still, the same in all four runs. This is a replay: whether the picture now looks steady has not been
seen in the glasses. Not looked at: late display frames (about 5 in 100 with one window, more with six).

## 13. Worn, 21 November 2026, evening: floor 50 cm too high as a game started; no hand found

Four minutes: five windows closed from the phone, then the ball-shooter game. The wearer: "hand tracking is
completely off [not shown at all, desktop and game]. starting floor is really off by at least 20 inches [too
high], bottom nav menu is too high overlaping with other screens, everything else seems to work fine".

- Floor: the log has position away for 0.9 s at the instant the game connected (the estimator began afresh;
  why is not known, it is logged from now on), the head held where it was while moving at 0.28 m/s, and the
  world joined on at the held place. A head that rises in that time leaves the world, and the floor measured
  in it, that much too high; the floor given to the game then followed new measurements at 4 cm a second.
  Changed: half a second without position (it was two) drops what was found of the room and puts the floor at
  its usual depth under the head, and the game takes that floor at once (`Tracking.roomRegains`). Not
  reproduced and not worn: there is no recording of that session (the recording did not start).
- Hands: the tracker gave 30 results a second (26 ms each, the camera fix) and found no hand in any of them
  for 90 s of desktop. The afternoon's recording replayed through the same build finds the right hand in 62 %
  of results, so it is not the build. Sunset was at about 16:35 and the session at 17:21: the room was lit by
  its spotlights alone. Not confirmed: it needs a recording in that light.
- The dock: see `Shelf`/`DesktopSession.shelf` (it steps aside while a window covers it).
- The saved room map had no places for three sessions and so never grew: an empty one is now begun afresh.
