# How Ultra XR tracks hands (brief for a developer, 20 November 2026)

The question this is written for: can the area in which a hand is tracked be made larger?

## Hardware

- XREAL Air 2 Ultra glasses. Two grayscale, global-shutter fisheye cameras on the front, 640 x 480 each, delivered
  as a stereo pair at 30 Hz over USB. No depth sensor, no colour camera, no infrared illumination.
- From this unit's factory calibration: baseline 137.3 mm; the two cameras are toed out 9.7 degrees against each
  other; the camera pair is pitched 7.2 degrees against the motion sensor. A 1000 Hz IMU sits in the glasses.
- The sensors are mounted on their side: each picture is turned to an upright 480 x 640 (portrait) before use, the
  left one 90 degrees counter-clockwise and the right one 90 degrees clockwise.
- All processing is on the phone (Galaxy S21 Ultra, Snapdragon 888), on the CPU.

## Pipeline

1. **Pictures.** Each stereo pair is copied to the hand thread (`tracking/Tracking.kt`, `handsLoop`). Normally every
   pair (30 Hz); every second pair when the phone's thermal status is "severe" or the battery saver is on.
2. **Tracker: Monado's "Mercury"**, the optical hand tracker of the Monado OpenXR runtime (commit `6c980493`), with
   XR Gate's C API and patches, built as one library, `libultra_hands.so` (`native/hands/`, details in
   `native/hands/NOTES.md`). Inference is ONNX Runtime 1.22 on the CPU. Mercury's own two models, unmodified:
   - `grayscale_detection_160x160.onnx`: finds hands. Each camera's whole picture is scaled down to 160 x 160 and
     the network gives a box for the left and for the right hand, in each view.
   - `grayscale_keypoint_jan18.onnx`: run on the crop of each box, gives 21 keypoints of the hand in that view.
   - Then a kinematic fit (`kine_lm`, Levenberg-Marquardt): one hand model with bone lengths and joint limits is
     fitted to the keypoints of **both** views through the stereo calibration. That gives metric 3D joints
     (21, or 26 in OpenXR order). Between frames it predicts where the hand will be and reuses the box.
   - Mercury is a stereo tracker: a hand has to be seen by both cameras.
3. **Calibration.** The factory calibration is a "fisheye624" model (6 radial, 2 tangential, 4 thin-prism terms).
   Mercury takes a Kannala-Brandt (kb4) model, so a kb4 model is fitted to the factory one and written in the
   layout Mercury reads (`core/FactoryCalibration.kt`).
4. **Into the room.** Mercury reports joints in the left camera's frame. They are moved into the world with the
   head pose at the picture's timestamp: world from IMU (the visual-inertial tracker, Basalt) times IMU from
   camera (calibration).
5. **Gestures** (ours, outside Mercury):
   - Pinch (`core/SessionTools.kt`, `PinchState`): thumb tip to index tip under 33 mm starts it, over 46 mm ends
     it, also as a ratio of the index finger's length (0.46 / 0.65). A second rule knows a pinch by its closing
     movement, because seen from behind the fingertips hide each other and the tracker leaves them 30 to 65 mm
     apart. A hand lost for under 250 ms keeps its pinch.
   - Pointing ray (`core/ArmRay.kt`): from an estimated shoulder (taken from the head pose) through the palm,
     smoothed, held still for the first 0.3 s of a pinch.
6. **Cost, measured:** about 26 ms for a pair. While a hand is tracked the tracker returned about 15 results a
   second in the recordings, not 30.

## What was measured about where it works

From the wearer's own recordings, replayed through the tracker on the phone (`docs/TRACKING_ANALYSIS.md`):

- A hand raised into the middle of the view was found in practically every result (171 of 173, 59 of 59).
- Hands held low, at chest height, which are at the bottom corners of the pictures, were not found once in twelve
  seconds.
- Brightening the pictures before the tracker, and switching off Mercury's quality filter, changed nothing
  beyond the run-to-run spread.
- A single-camera tracker (MediaPipe Hand Landmarker) run on each camera's upright picture was worse on these
  pictures: 18 % of pairs against Mercury's 84 % on the same recording, and it did not find the low hands either.

## Why the area is small (my reading, not measured piece by piece)

- The hand must be in both pictures, and the cameras are toed outwards: the part both see is smaller than what
  either sees.
- The detector sees the whole fisheye picture squeezed to 160 x 160. A hand near the edge is small, cut off and
  stretched there, unlike what the model was trained on.
- Mercury's models were trained for other headsets' cameras; nothing has been retrained for these.
- The cameras face forwards. A hand at chest height is at the very edge of the fisheye picture.

## Ideas not tried yet

- Before detection, redraw each fisheye picture as two or three ordinary (pinhole) views aimed forwards, down and
  to the sides, run the detector on each, and map the boxes back. The dense-depth code already redraws the
  pictures this way (`core/DenseStereo.kt`).
- When only one camera sees the hand, keep tracking from that one view, taking the hand's size from the last
  stereo fit for the distance.
- Fine-tune the detection model on pictures from these cameras (the recordings exist; labels would have to come
  from the frames where the stereo tracker does work, or from a stronger offline model).
- Another tracker made for wide fisheye headset cameras, for example Meta's open UmeTrack.
- Camera exposure and gain: whether they can be set on these glasses has not been established.

## Wider detection: what was measured first (20 November 2026)

The owner asked for hands to be tracked from face to chest height and across all the cameras see. Before changing
the tracker, two things were checked on his recording of 17 November (45 s, 227 stereo pairs, hands raised, lowered
and held low; `tools/tracking/hand_bench.py`, `hand_bench_report.py`, `hand_bench_stereo.py`; pictures and results
stay under `C:\uvio\hands_bench`, outside the repository).

**Do the cameras see chest height? Yes.** From the calibration (kb4, focal length 240 pixels, 480 x 640 upright) a
camera sees about 75 degrees above and below its axis and about 55 to each side. In the recorded pictures the
wearer's own chest and forearms are in the lower part of both pictures, a hand over the keyboard in front of the
chest is in both, and a hand held low at the side is in one camera's picture, at its edge. The display's 46 by 27
degrees is a small part of that. So it is the detector that does not find low hands, not the cameras.

**The same detector on fisheye-corrected tiles.** Mercury's detection model was run in Python exactly as Mercury
feeds it (letterboxed to 160 x 160, its normalisation, its 0.3 confidence), once on each camera's whole picture
(what is done now) and once on nine ordinary views of 70 degrees cut out of the fisheye picture (three ahead and up,
three ahead and down, three at chest height). Each second of the recording was labelled by eye: which hand is
plainly in which camera's picture; hands that could not be judged (at the very edge, holding the phone) are counted
neither way.

| | whole picture (now) | nine tiles |
|---|---|---|
| a hand plainly in a camera's picture is found | 117 of 320 (37 %) | 237 of 320 (74 %) |
| a hand plainly in both pictures is found in both (what the 3D fit needs) | 6 of 160 (4 %) | 99 of 160 (62 %) |
| a hand is "found" where there is none | 0 of 427 | 170 of 427 (40 %) |
| time for a camera's picture, on the laptop | 2 ms | 18 ms |

- The tiles also find, in nine of every nine pairs, the left hand held low at the picture's edge from second 19 to
  28, which the whole-picture detector never finds. That hand is one of those not counted (it holds the phone).
- More than half of the false finds are one thing: dark stands beside the laptop, taken for a right hand at 0.92
  confidence in one tile, in both cameras. A higher confidence does not separate them (at 0.9: 25 % found, 12 %
  false).
- Pairing the two cameras' finds and keeping only what lies between 12 and 85 cm away is not enough by itself: the
  detector's middle of a hand is rough (the two rays pass 1 to 7 cm apart for real hands), so this keeps only 44 of
  160 real hands and still 20 of 152 false ones.
- Mercury's own per-frame detection is worse in this replica (4 %) than Mercury's tracking on the phone (a hand in
  23 % of its results on this recording), because Mercury follows a hand once found. The numbers above are about
  finding, not following.

**What follows from it.** Tiles roughly double what the detector finds and reach low hands, but they cannot be used
by themselves: what tells a hand from a lamp stand is Mercury's next stages (keypoints, the fit, its quality
filter). So the change belongs inside Mercury's detection step, with its later stages as the judge, and the measure
is the replay of these recordings on the phone, before and after. Not done yet. Also not measured yet: the second
recording (19 November), speed on the phone, and anything about one-camera tracking or pinch.

## Tiles inside Mercury, measured on the phone (20 November 2026)

The change (`native/hands/src/patches/0002-ultra-hands-tiles.patch`, in `hg_model.cpp`): each time the detector runs on
a camera's picture, for a hand that is not being tracked and that the whole picture did not show, it also looks
through three fisheye-corrected tiles (nine in all, the low and the side ones twice in a round). What a tile finds
is put back into the fisheye picture and goes on through Mercury's keypoint model and fit as any detection does.
`tiles=0` in `hand_options.txt` is the tracker as it was; unset, the library looks through three. The library is
built on Windows with `native/hands/src/build_android_windows.sh`.

Both recordings were replayed through the tracker on the S21 Ultra (`tools/pixel/lab.py`, `UXR_IP=192.168.1.165`,
runs `h_tiles0`, `h_tiles3`), the first one twice each way, and scored against the labels
(`tools/tracking/hand_replay_compare.py`):

| 17 November, 45 s | results a second | ms a result | hand plainly in both pictures: tracked | hand in neither picture: reported |
|---|---|---|---|---|
| tiles off | 29.5 | 23.9 | 769 of 885 (87 %) | 11 of 847 (1 %) |
| tiles off, again | 27.2 | 27.7 | 611 of 783 (78 %) | 7 of 776 (1 %) |
| tiles on | 26.9 | 28.8 | 692 of 768 (90 %) | 9 of 761 (1 %) |
| tiles on, again | 26.9 | 28.6 | 692 of 769 (90 %) | 9 of 763 (1 %) |

19 November (70 s, hands held up, not labelled): a hand in 40 % (left) and 47 % (right) of results with tiles off,
41 % and 50 % with tiles on; 34.8 and 30.0 ms a result.

What that says, and what it does not:

- **Raised hands:** 90 % against 78 to 87 %. The two runs without tiles differ from each other by more than the
  runs with tiles differ from the better of them, so most of this is within the spread. One thing is the same in
  both runs each way: in seconds 7 and 8 the right hand is tracked in two or three of nine results without tiles
  and in nine of nine with them. A lost hand is found again sooner.
- **False hands:** no more than before (1 %), in a recording with the stands that fooled the detector offline.
  Mercury's later stages do reject them.
- **Cost:** not measurable against the run-to-run spread.
- **Chest height and low hands: no gain.** In second 18 (the right hand low over the keyboard, in both pictures)
  and in seconds 19 to 28 (the left hand low at the side, in one picture) no run tracks a hand, with tiles or
  without. Offline the tiles do find those hands, so what stops them is after detection. Two causes are likely and
  neither is shown yet: the hand at the side is in one camera only, and Mercury's fit needs two; and a hand at the
  picture's edge may be thrown out by Mercury's own checks (keypoints outside the view, the quality filter).

So the tiles are kept (they do no harm and find a lost hand sooner), and the owner's goal is not met by them. What
it takes next: to see, frame by frame, why a hand the detector found is dropped (Mercury has a detector debug
log); then tracking from one camera with the distance held from the last two-camera fit, as Frametop does; then
the question of speed.

## Where a low hand is lost, traced (20 November 2026, later)

**The tool.** `native/hands/test/ultra_hands_replay.c` runs a file of recorded stereo pairs
(`tools/tracking/hand_pairs_export.py`) through the library on the phone over adb, every pair in order, with
Mercury's own per-frame logs switched on (`MERCURY_XR_TRACKING_DEBUG_CSV`, `MERCURY_XR_DETECTOR_DEBUG_CSV`);
`tools/tracking/hand_trace.py` prints them a line a frame. Two runs with the same settings report the same hands in
every one of 1341 pairs, which the replays through the app do not (they run at recorded speed and drop different
frames each time: the same settings gave a right hand in 47 % and in 61 % of results on the 19 November recording).
All numbers below are from this tool.

**What the trace shows, second 18 of the 17 November recording (the right hand coming down in front of the chest).**

1. Up to 18.18 s the hand is tracked in both cameras, the fit is good (reprojection error 0.1 to 0.5 against a limit
   of 15), the keypoint model says "a hand" at 1.00 in both.
2. At 18.21 s the keypoint model says 0.87 in the right camera and 1.00 in the left. Upstream's rule
   (`hg_model.cpp`, "North Star seemed to need 0.97") makes that view inactive, and `hg_sync.cpp` then removes the
   whole hand ("because keypoint estimator said to"): rejection bit 4, `keypoint_rejected`. One camera, one frame.
3. It does not come back: the whole-picture detector does not see it there, and what a tile finds in one camera
   fails the detection gate, which (while the detector runs in both views) needs the hand found in both views in the
   same frame; the two cameras' tiles went round independently.
4. Seen at full size, the hand is at the very bottom edge of both pictures by 18.3 s and out of them by about
   18.4 s. The label "plainly in both pictures" for second 18 is true only of its first third.

**Seconds 19 to 28, the left hand.** It grips the phone at the left edge of the left camera's picture and is in the
other camera not at all. The detector finds it through a tile, its second looks (below) reject it
(0.31, 0.02, 0.00, 0.86), and nothing starts a hand from one camera. Not tracked, before or after.

**What was changed** (`native/hands/src/patches/0002-ultra-hands-tiles.patch`, `0003-ultra-hands-keep.patch`):

- **A tracked hand goes on with one camera** when the other is under 0.97 for a frame (and not under 0.30), if it
  has been tracked for eight frames; the unsure view sits that frame out and gets a new region from the fit.
  `keep=0` in `hand_options.txt` is upstream's rule.
- **Both cameras look through the same tiles in a frame**, so that a hand both see passes the gate.
- **A second look at what a tile found**, before it is believed: four views aimed at the find (70 and 95 degrees
  wide, 70 turned 25 degrees either way) must all say "hand" over 0.5; a find that fails is left alone for half a
  second. Without it the tiles start a hand on the dark stands beside the laptop (the keypoint model says 0.99 in
  both cameras) and keep it for seconds. `verify=0` switches it off.

**Measured, every pair of both recordings, on the S21 Ultra:**

| 17 November, 1341 pairs | hand plainly in both pictures: tracked | hand in neither picture: reported | ms a pair in the tool |
|---|---|---|---|
| upstream (no tiles, upstream's rule) | 787 of 900 (87 %) | 7 of 861 (1 %) | 56 |
| the same again | 787 of 900 (87 %) | 7 of 861 (1 %) | 55 |
| keep only | 803 of 900 (89 %) | 6 of 861 (1 %) | 57 |
| as it is now (tiles, second look, keep) | 846 of 900 (94 %) | 8 of 861 (1 %) | 87 |
| tiles without the second look | 831 of 900 (92 %) | 400 of 861 (46 %) | 77 |

| 19 November, 2100 pairs, hands held up, not labelled | left hand reported | right hand reported | ms |
|---|---|---|---|
| upstream | 46 % | 46 % | 64 |
| keep only | 51 % | 66 % | 60 |
| as it is now | 53 % | 75 % | 76 |
| tiles without the second look | 96 % | 95 % | 51 |

- **Gained:** a hand that is in both pictures is tracked more of the time (87 to 94 %; in seconds 7 and 8 the right
  hand goes from 0 to 2 of 9 pairs to 9 of 9), with as few false hands as before. On the unlabelled recording
  the hands are reported more often, most of it from the keep rule; without labels that cannot be called right or
  wrong.
- **Second 18:** 0 of 9 pairs before, 2 of 9 now: the hand is followed until both cameras lose it at the edge.
- **Not gained: hands at chest height that stay in view, and hands in one camera.** These recordings have next to
  none of the first (the hand passes through in a third of a second) and only the phone-gripping hand of the
  second. Starting a hand from one camera is not built: there is nothing here to tune it on or to measure false
  hands with, and the stands show how easily a false one starts.
- **Cost:** in the tool a pair takes half as long again while a hand is missing (tiles and second looks run for the
  missing hand). Through the app, with its own threads, results took 27 ms with either library.
- **The 96 % of tiles without the second look** on 19 November may be right (the hands are held up throughout) or
  may be full of false hands; on 17 November that setting reports a hand where there is none 46 % of the time, so
  it is not used.

**What is needed next:** one recording of the case itself: hands at chest and keyboard height, staying in view,
moving and pinching, one at a time and together, for forty seconds
(`adb shell am start -n dev.ultraxr/.ui.LauncherActivity --ei record_seconds 40` with the glasses on).

## Where the code is

`native/hands/` (library, build scripts, notes), `tracking/Tracking.kt` (`handsLoop`), `core/Hands.kt`,
`core/SessionTools.kt` (`PinchState`), `core/ArmRay.kt`, `desktop/HandPointer.kt`, `xr/XrHost.kt` (hands for
OpenXR games), `tools/tracking/mono_hands_trial.py` (the MediaPipe trial), `docs/TRACKING_ANALYSIS.md`.

Not stated here because I have not measured it: the cameras' field of view in degrees. It follows from the
calibration file on the phone (`files/factory_config.json`).
