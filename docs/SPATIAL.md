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

**Not done, not claimed:**

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

## What is known to be weak (measured, `docs/QUEST_COMPAT.md`, `docs/TRACKING_ANALYSIS.md`)

- **A saved map only since 20 November, and only for the desktop's windows** (section 7); not yet worn. Anchors saved
  by a game still come back relative to where the next session started: one put on a wall was 0.73 m off in the next
  run and 2.70 m off after walking.
- **Position was lost while walking** (seconds at a time, worn, 18 and 19 November); each loss of more than 2 s makes
  the room model forget its surfaces. The long wait after a loss is fixed (section 7); why it is lost is not known,
  and no recording reproduces it.
- **Planes only.** No usable mesh, no occlusion by real objects other than the planes, nothing for furniture that
  is not a table top.
- **Walls only where the wearer has looked**, and only as straight runs.
- **Steam (PC VR) games are not supported.** Nothing streams from a PC. The room and tracking above would serve such
  a client (head and hand poses out, pictures in), but none is built.

## Where the code is

`native/vio/` (Basalt and its wrapper), `native/slam/` (RTAB-Map and its wrapper), `core/MapAlign.kt`, `tracking/Tracking.kt` (the loops that tie it together),
`core/HeadTracker.kt`, `core/PositionConfidence.kt`, `core/FactoryCalibration.kt`, `core/FloorCalibrator.kt`,
`core/StereoPoints.kt`, `core/RoomModel.kt`, `core/RoomTidy.kt`, `core/DenseStereo.kt`, `core/WallMap.kt`,
`core/RoomMesh.kt`, `core/RoomPlan.kt`, `core/RoomRecall.kt`, `xr/XrHost.kt`, `questlab/src/main/cpp/meta_layer.cpp`.
Research and roadmap: `reports/XREAL Quest level spatial roadmap.md`, `docs/SPATIAL_RESEARCH.md`.
