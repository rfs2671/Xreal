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

## What is known to be weak (measured, `docs/QUEST_COMPAT.md`, `docs/TRACKING_ANALYSIS.md`)

- **No persistent map.** Anchors saved by a game come back relative to where the next session started: one put on a
  wall was 0.73 m off in the next run and 2.70 m off after walking. The marked plan is the only thing that carries
  over, and correcting drift against it is built but its walk test has not been done.
- **Position is lost on fast head turns and while walking** (seconds at a time); each loss of more than 2 s makes
  the room model forget its surfaces.
- **Planes only.** No usable mesh, no occlusion by real objects other than the planes, nothing for furniture that
  is not a table top.
- **Walls only where the wearer has looked**, and only as straight runs.
- **Steam (PC VR) games are not supported.** Nothing streams from a PC. The room and tracking above would serve such
  a client (head and hand poses out, pictures in), but none is built.

## Where the code is

`native/vio/` (Basalt and its wrapper), `tracking/Tracking.kt` (the loops that tie it together),
`core/HeadTracker.kt`, `core/PositionConfidence.kt`, `core/FactoryCalibration.kt`, `core/FloorCalibrator.kt`,
`core/StereoPoints.kt`, `core/RoomModel.kt`, `core/RoomTidy.kt`, `core/DenseStereo.kt`, `core/WallMap.kt`,
`core/RoomMesh.kt`, `core/RoomPlan.kt`, `core/RoomRecall.kt`, `xr/XrHost.kt`, `questlab/src/main/cpp/meta_layer.cpp`.
Research and roadmap: `reports/XREAL Quest level spatial roadmap.md`, `docs/SPATIAL_RESEARCH.md`.
