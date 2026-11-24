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

## A third recording, with low hands, and the second look set by it (20 November 2026, midday)

The owner made a 40 s recording with the glasses on (`chest-20261120.uxrec`, 1199 pairs; kept outside the
repository). He did not follow the script asked for; it is what he does at his desk: the right hand raised and
pointing (seconds 7 to 11), then both hands low over the desk and the laptop in a dim room with dark sleeves
(about 24 to 38). Labelled by eye, a second at a time, with most of the low seconds marked as not to be judged
(the hands are dark and small at that size); 270 results fall in seconds with a hand plainly in both pictures, 840
in seconds with none.

Every pair through the tracker on the phone (`ultra_hands_replay`), as before:

| 20 November, 1199 pairs | hand plainly in both pictures: tracked | hand in neither: reported | hands reported in the seconds not judged |
|---|---|---|---|
| upstream | 57 of 270 (21 %) | 0 of 840 | 1 % |
| keep only | 142 of 270 (53 %) | 0 of 840 | 1 % |
| tiles, all four second looks must agree (the build of the morning) | 142 of 270 (53 %) | 0 of 840 | 1 % |
| tiles, three of four must agree (**as it is now**) | 178 of 270 (66 %) | 0 of 840 | 4 % |
| tiles, two of four | 214 of 270 (79 %) | 0 of 840 | 9 % |
| tiles without the second look | 223 of 270 (83 %) | 0 of 840 | 35 % |

The same settings on the 17 November recording, where the stands beside the laptop are: three of four gives exactly
what four of four gave (846 of 900 tracked, 8 of 861 false); two of four reports a hand where there is none in
303 of 861 results (35 %). So three of four it is.

- **Upstream loses the raised hand after two seconds** here (seconds 9 to 11: nothing); the keep rule holds it
  (9 of 9). That is the largest single gain in this recording.
- **The low hands:** upstream and the morning's build track none of them. With three of four the right hand is
  tracked from second 33 to 36; without the second look it is tracked from 25 to 39. So the hands at 25 to 32 are
  there to be had and the second look still refuses them. Whether what is reported in those seconds without the
  second look is right in place and in which hand it is has not been checked frame by frame.
- **Not measured on this recording:** pinches, fingertip steadiness, how far the reported hands are from the true
  ones. The 19 November recording was not run again with three of four.

## The low hands recovered: a hand may begin from one camera (20 November 2026, afternoon)

**Are the hands the tracker reports without the second look in seconds 25 to 32 real?** Yes: drawn on the left
camera's pictures (brightened) at six moments, the reported right hand lies on the right hand over the desk and the
reported left hand on the left hand.

**Why the second look refused them.** It did not: logged with their times, the second looks pass (three of four)
for the right hand eleven times between 25.9 and 27.6 s, always in one camera. What stops the hand is the detection
gate after it, which (while the detector runs in both views) wants the hand found in both views in the same frame.

**How often does a find pass where there is no hand?** Never, in what there is to count: 42 finds in the ten
seconds with the stands and the phone hand (17 November), 31 finds in fifteen empty seconds (20 November), none
passed. In the eight seconds of low hands 20 of 78 passed, 18 of them with another pass of the same camera and hand
within 0.4 s and 50 pixels.

**The change** (in the two patches): a find that passed its second looks twice in the same place within twelve
runs of the detector may begin a hand from its one camera (`ULTRA_HANDS_ONE_VIEW=0`, or `one_view=0` in
`hand_options.txt`, is the gate as it was). The other camera gets its region from the fit. **The hand is not
reported until both cameras' keypoint models have been sure of it in one frame**: before that its distance rests on
one view and the assumed hand size. Once it has been, it goes on through a camera's doubt as described above.

**Measured, every pair, on the phone:**

| | 20 November: hand plainly in both pictures tracked | reported where there is none | 17 November: tracked | reported where there is none |
|---|---|---|---|---|
| upstream | 57 of 270 (21 %) | 0 of 840 | 787 of 900 (87 %) | 7 of 861 |
| tiles, three of four, two-camera start only | 178 of 270 (66 %) | 0 of 840 | 846 of 900 (94 %) | 8 of 861 |
| **with the one-camera start (as it is now)** | 254 of 270 (94 %) | 0 of 840 | 848 of 900 (94 %) | 12 of 861 |
| tiles without the second look | 223 of 270 (83 %) | 0 of 840 | 831 of 900 (92 %) | 400 of 861 |

- On 20 November the low right hand is now reported in nine of nine pairs from second 26 to 35 (upstream: none),
  and the left hand in seconds 25 to 27. In the seconds marked as not to be judged a hand is reported in 29 % of
  results (upstream 1 %); those are mostly the low hands themselves.
- The reports "where there is none" on 17 November are at the edges of the labels, not false hands: one result in
  second 4, half a second before the hand labelled from second 5, and second 43, where the left hand labelled until
  second 42 is still on its way down (upstream has 7 of these, now 11).
- The stands start no hand (seconds 19 to 28 of 17 November: nothing reported, as upstream).
- **Cost in the tool:** 81 ms a pair on 20 November against 19 ms upstream, 97 against 56 on 17 November: with a
  hand missing, the tiles and second looks run every frame. Through the app, with its own threads, the earlier
  builds made no difference to the time a result took; this build has not been timed there. This is the next
  thing to look at.
- **Still not measured:** the left hand low at the side in one camera only (it would begin, and then wait for the
  second camera that never sees it: by design it is not reported); pinches and fingertip steadiness at chest
  height; how far reported hands are from true ones; the 19 November recording with this build.

## In the app, at the speed of the cameras (20 November 2026, evening): what the numbers above leave out

The tool above gives the tracker every pair however long a pair takes. The app does not: a pair that arrives while
the tracker is busy is dropped. Three things came out of timing the new library in the app.

**1. The replays through the app before this evening compared nothing.** The library read its switches once for the
life of the process, and `tools/pixel/lab.py` did not end the process between runs ("am kill" leaves a process that
holds a service), so a run kept the switches of the run before it. That, not chance, is why those replays showed no
difference between settings. Put right: the library reads its switches each time, the app sets or clears every one
of them at each start of tracking, and `lab.py` ends the process (as the app itself, not a force-stop). The numbers
from the tool are not touched by this. With it put right, two runs of the same setting agree (upstream: 58 of 269
and 58 of 271).

**2. With three tiles a run the library was worse in the app than upstream.** 39 ms a result against 15, 23 results
a second against 30, and fewer hands tracked than upstream (16 and 36 of about 180 against 57 of 270): the work for
a hand that is missing took the time the hands that are there needed. In the tool it had looked best.

**3. With one tile a run** (and the second looks' views worked out at every fourth pixel) it is better than
upstream in the app as well. The 20 November recording through the app, upstream and now, twice each:

| in the app, 40 s | hand plainly in both pictures: tracked | reported where there is none | results a second | ms a result: no hand / with a hand |
|---|---|---|---|---|
| upstream | 58 of 269 (22 %) | 0 of 840 | 30.0 | 14.5 / 28.9 |
| upstream, again | 58 of 271 (21 %) | 0 of 830 | 29.9 | 14.5 / 28.9 |
| now | 141 of 229 (62 %) | 0 of 818 | 28.0 | 21.4 / 37.4 |
| now, again | 152 of 217 (70 %) | 0 of 815 | 28.1 | 21.9 / 36.9 |

- The raised hand of seconds 7 to 11 is held throughout (upstream loses it after two seconds).
- **The low hands are tracked only in part at this speed:** the right hand in second 27 and in seconds 33 to 36
  (one run), 33 and 34 (the other). Given every pair, the same library tracks it from 26 to 35. So what the tool
  shows is what the library can do, not what the wearer gets; the difference is time.
- It costs 7 ms a result, and two results a second.
- With one tile the tool itself gives 215 of 270 (80 %) on 20 November, none false, and 846 of 900 on 17 November
  as before.

**Where the time goes is not settled.** The tool's own timings moved between runs by more than the settings did
(the phone had been at it for an hour; a pair with no hand took from 11 to 80 ms within one run), so they are not
used. Second looks are few (0 to 7 sets a second) and are not it. A candidate that lies on the hand already tracked
is now left out before its second looks; that changed nothing measurable.

## Trying to get the low hands at the cameras' speed: what was learned, nothing adopted (20 November 2026, night)

The library was not changed by this; what is installed is still the build of the section above.

- **What a frame costs.** One run of the detector takes about 10 ms on the phone (one ONNX Runtime thread; the two
  views run side by side). Upstream's frame with no hand is 13 ms, with a hand tracked 29 ms in the app. Every
  tile on top of the whole picture is 10 ms more, and a set of four second looks 40 ms in one frame. That, not the
  second looks' own preparation, is the cost; the detector itself would have to be faster (it runs on the CPU) for
  more looking to fit in a frame.
- **Taking turns instead of adding** (every other run of the detector the whole picture, the runs between one tile
  or one second look) brings a frame back to upstream's 13 ms and finds almost none of the low hands: a find then
  takes a third of a second to check, and two passes in the same place seldom come within the time. Not adopted
  (`C:\uvio\hb\hg_model.turns.cpp`, outside the repository).
- **Letting a find that all four second looks agree on begin a hand by itself** starts a hand on the stands of the
  17 November recording when frames are dropped (reported where there is none in 35 % of results). Not adopted.
- **The test tool now has the app's threads and a mode that drops pairs as the app does** (`UH_REALTIME=1`). With
  it, which pairs are processed depends on how long each took, and two runs differ; it takes 57 ms for a pair with
  a hand where the app takes 29, so it drops more than the app does. Good for seeing what dropped frames do, not
  for numbers.
- **A hand reported on the stands for ten seconds is upstream's own behaviour.** In the 17 November recording the
  right hand comes down past the stands beside the laptop in second 18. Through the app at the cameras' speed,
  upstream then keeps reporting a right hand, on the stands, until second 28 in one run of two, and the library as
  it now is does so in one run of two as well. Given every pair, neither does. So the "none false" of the tables
  above is true of every-pair runs and of the 20 November recording in the app, and is not true of this recording
  in the app, with or without the changes:

| 17 November through the app, at speed | hand plainly in both pictures: tracked | reported where there is none | results a second |
|---|---|---|---|
| upstream | 703 of 813 (86 %) | 11 of 772 (1 %) | 27.9 |
| upstream, again | 629 of 786 (80 %) | 47 of 750 (6 %), and a right hand on the stands, seconds 18 to 28 | 26.4 |
| now | 625 of 709 (88 %) | 53 of 701 (8 %), and the same | 24.4 |
| now, again | 603 of 675 (89 %) | 12 of 675 (2 %) | 24.5 |

  What would stop it is a tracked hand having to be confirmed from time to time (by the whole-picture detector
  near it, or by second looks), and being dropped when nothing confirms it for a while. Not built: second looks
  pass for a real low hand only about one time in four, so the rule needs care not to drop real hands, and there
  is one recording with such a thing in it.

## Faster models: XNNPACK, not the GPU (20 November 2026, late)

**Measured first, each model by itself on the phone** (`native/hands/test/ort_bench.c`, 200 runs, a fixed input,
median; the output is the same to five figures in every row):

| | detector, ms a run | keypoint model, ms a run |
|---|---|---|
| plain CPU kernels, 1 thread (as it was) | 5.2 | 8.9 |
| plain CPU kernels, 2 threads, no spinning | 9.5 | 15.3 |
| XNNPACK, 1 thread | 2.8 | 5.5 |
| XNNPACK, 2 threads | 2.0 | 3.9 |
| XNNPACK, 4 threads | 1.5 | 2.7 |
| NNAPI | 10.5 | 22.0 |
| NNAPI with its CPU fallback off | does not run: no GPU or DSP device takes the model | |

XNNPACK is in the same ONNX Runtime library the app already ships; nothing is added. NNAPI, the way to the GPU and
the DSP, is slower than the plain kernels here and cannot place these models on either. A GPU or NPU would take
another runtime (Qualcomm's QNN) and probably another form of the models; not tried.

**In the tracker** (`native/hands/src/patches/0004-ultra-hands-xnnpack.patch`; `xnnpack=0` in `hand_options.txt` is
the plain kernels): one XNNPACK thread a model gains nothing there (two views run at once), two do: in the test
tool a pair with a hand tracked takes 34 ms against 60, a pair with none 9.7 against 13.5. With the tiles and the
rest unchanged the hands reported are the same in 1195 of 1199 pairs.

**In the app, at the cameras' speed:**

| 20 November | hand plainly in both pictures: tracked | where there is none | results a second | ms a result |
|---|---|---|---|---|
| plain kernels | 67 %, 72 % | 0 of 822, 0 of 816 | 27.9, 26.5 | 26.9, 28.5 |
| XNNPACK (**as it is now**) | 71 %, 67 % | 0 of 840, 0 of 840 | 28.6, 29.5 | 22.2, 21.0 |
| XNNPACK and three tiles a run | 83 %, 83 % | 0 of 838, 0 of 836 | 26.6, 26.2 | 31.2, 31.7 |

| 17 November | tracked | where there is none | results a second |
|---|---|---|---|
| upstream, plain kernels (from the section above) | 86 %, 80 % | 1 %; 6 % and a hand on the stands | 27.9, 26.4 |
| XNNPACK (as it is now) | 94 %, 93 % | 4 %, 3 % (the edges of the labels); nothing on the stands | 28.1, 27.3 |
| XNNPACK and three tiles a run | 92 %, 92 % | 9 %, 9 %: a right hand on the stands from second 18 to 28, in both runs | 24.3, 24.6 |

- **Three tiles a run is not taken**, although the time is there for it and it tracks more of the low hands of
  20 November (the left hand in seconds 25 to 28): on 17 November it puts a hand on the stands beside the laptop in
  both runs. One tile does not, in either.
- So what the faster models buy for now is time: 21 ms a result in place of 27, the tracker keeping up with the
  cameras, and on 17 November the hand found again at once in seconds 7 and 8.
- The low hands at the cameras' speed are as before: tracked in part. What stands between them and three tiles is
  the hand on the stands, which is the same thing upstream does by itself in one run of two: a tracked or newly
  found hand nothing confirms. That is the next thing to build.
- `tools/pixel/lab.py`: a start request that arrives twice is no longer taken for the end of a run (the first run
  after a restart was being cut short).

## Worn in the desktop, 21 November 2026: why "no pinch" (`C:/uvio/rec/live1121.uxrec`, `C:/uvio/vio_bench/hands1121`, `tone1`)

The owner used a hand in the desktop for 95 s; the app counted 2 pinches. The raw pictures of that stretch show the
arm stretched out in the middle of both cameras' views. Two causes, both measured on that recording:

- **The cameras gave fewer pictures once the desktop was open.** 58 frames a second before, 37 to 41 from the second
  the desktop with six windows was shown, 55 after. With frames missing at random a pair has both pictures less
  often still: replayed, the tracker gets 8 pairs a second in that stretch (20 to 29 before it). Not mended: it needs
  the glasses plugged in to measure (the camera reader against the desktop's load).
- **The hand stood before a doorway full of daylight.** Offline, Mercury's detector on the whole picture finds a hand
  in both cameras in 2 of 37 pairs; on the tiles, 30 of 37; on tiles of pictures lifted in the dark
  (`out = 255 (1 - exp(-in / 55))`, scaled), 35 or 36 of 37. Through the whole tracker on the phone
  (`hand_tone=55` in `vio_options.txt`, runs `t_off`, `t_55`, twice each, the same both times): a hand in 66 % of
  results against 57 %, in 60 of 78 seconds against 52, the longest stretch without one 6 s against 10; 6 ms a
  result dearer. On the recording of 17 November, in ordinary light, it is not better: the right hand in 447 results
  against 424, the left in 248 against 352. So it is NOT adopted (`HAND_TONE = 0`); a curve that acts only when part
  of the picture is burnt out is the thing to try.

A window held by a pinch on its handle now also goes further and nearer as the hand is pushed away and drawn in
(`DesktopSession`, `PUSH_GAIN`): with the made-up hand a window was carried 5 degrees and pushed from 2.00 to 3.23 m
by 20 cm of the hand. Drawing nearer is the same code and was not seen. No real hand has done either.

## Where the code is

`native/hands/` (library, build scripts, notes), `tracking/Tracking.kt` (`handsLoop`), `core/Hands.kt`,
`core/SessionTools.kt` (`PinchState`), `core/ArmRay.kt`, `desktop/HandPointer.kt`, `xr/XrHost.kt` (hands for
OpenXR games), `tools/tracking/mono_hands_trial.py` (the MediaPipe trial), `docs/TRACKING_ANALYSIS.md`.

Not stated here because I have not measured it: the cameras' field of view in degrees. It follows from the
calibration file on the phone (`files/factory_config.json`).

## Cameras losing pictures under the desktop, 21 November 2026: found and fixed

Measured with the glasses plugged in and lying on a table (S21 Ultra), from the new "camera:" line the app
writes every five seconds (pictures and pairs a second, pictures cut short, the reader's own work).

| | pictures / pairs a second |
|---|---|
| no desktop | 60 / 30 |
| desktop, four windows, before | 55 / 25 |
| desktop, four windows, camera read first | 60 / 30 |
| desktop, six windows (two YouTube/Xbox, four web), camera read first | 60 / 30 |

No picture was ever cut short and no read timed out: whole pictures went missing. The glasses throw a picture
away when the phone does not fetch the last one in time, and the thread that fetches them ran at ordinary
priority behind the windows' rendering. It now runs at priority -16 (`UvcStream.PRIORITY`, `cam_priority=` in
`vio_options.txt`). The worn session of the morning (37 to 41 pictures a second with six windows) was also
writing a 6.8 GB recording on that same thread; that case was not re-run.

Not fixed: with six windows the hand tracker itself takes 36 to 125 ms a result (21 to 26 ms with four), about
15 results a second. Raising the trackers' priority (`work_priority=-8`) gave 29 to 58 ms in a single run and
more late display frames; the battery ran out before a second run, so it is not adopted. The six-window run
with the old camera priority could not be made either (the phone stopped powering the glasses at 10 %).
No hand was in front of the cameras in any of this: it measures rates, not tracking of a real hand.

Bench: `am start ... --ez desktop true --ez automation true --ei open_shelf N` opens shelf app N in the running
desktop (debug builds).

## Worn again, 21 November 2026 (afternoon): "doesn't do anything", and what was changed

Two minutes worn, one to six windows. Cameras 60 pictures a second throughout, no tracking loss. The right hand
was followed (about 16 results a second) and 9 pinches were counted; none moved, sized or closed a window, and
the focus changed dozens of times between the close word, the window, the page and the dock. The wearer:
"hand tracking is worst i tested so far. doesnt do anything, 1cm off the actual screen 0 tracking (which makes
it useless for screen size) stuff keep changing, 6dof anchoring is shkey at best".

Causes found in the code, and changed:

- A ray that just missed a window met nothing: the pointer's end was not drawn, and a corner could only be taken
  within 4 to 9 cm of it. The hand now has a band of 3.5 degrees round every window (`Desktop.hit(..., band)`,
  `WindowHit.frame`): pinched there and carried, the window moves; pinched at a corner of the band, it is sized.
  The ray's end is drawn on nothing too.
- The ray moved the focus the instant it crossed anything. It now has to rest 0.18 s on a thing first, and beside
  a window the focus stays on the part of the window it was on.
- A pinch on a word of the handle counted only if the ray was still on that word when the fingers opened. It now
  counts unless the ray has gone to another window.

Checked with the made-up hand only (S21 Ultra, glasses on the table): pinched 2 degrees beside the right edge
and carried 8 degrees up, the window was taken up and put down; pinched outside the top right corner and drawn
out, it was sized. Not worn. Not looked into yet: the shaky anchoring, and why only 9 pinches were counted
(recording `live-1795294419621.uxrec`, on the phone, is there for both).

### The same session replayed (21 November 2026, evening): the pinches were there

`worn1121b.uxrec` replayed twice on the S21 Ultra. The right hand was found in 68 to 69 % of the results while it
was up (23 results a second in replay). The pinch rule as it stands gives 9 and 10 pinches, held 14.6 and 15.3 s
in all: the pinches were made and held (dragging), and the desktop did nothing with them. So the fault was in
what a pinch does, not in seeing it; that is what the band round the windows and the handle rule above change.
Other thresholds (38/48, 40/50, 42/52 mm, two results in a row) give the same 9 to 11 on this recording and no
gain on the recordings of 17 and 19 November: not changed.

Seen in the pictures: a dim room, the cameras exposed for a doorway full of daylight and four spotlights, the
hand and a dark sleeve in front of them, 39 to 50 degrees to the right of the cameras' axis. A hand held in a
pinch for over a second, seen from behind, measures 24 to 41 mm between the fingertips. Brightening the hand
tracker's pictures (`hand_tone=55`: found in 60 and 67 %; `stretch=1`: 73 %, one run) is no repeatable gain.
At 45 s a hand low in the picture holding something bright (probably the phone) was followed and read as 38 to
39 mm: the hand is now not a pointer for 1.5 s after any touch on the phone's screen.

## Desktop hands worn, 24 November 2026: "the tracking is awful", "menu over the actual window"

From the worn log (08:49 to 08:54): in the ball scene the left hand took the ball six times; in the desktop with
three windows (a page loading from 08:51:11) the hand was seen in 483 of about 2,100 frames, one pinch, and the
tracker took 28 to 49 ms a result with no hand and 57 to 95 ms with one (replay on a quiet phone: 17 and 39 ms
median), so a hand's picture was 124 to 196 ms old when used. 15 ms a frame on the CPU for the desktop's drawing, 10 %
of frames late. The desktop draws only two fingertips and a ray; the full hand is drawn in the test scenes.

Bench on the phone, glasses lying on the table, six windows, no hand: tracker 32 to 54 ms a result (mean 43) with the
priority as it is; 22 to 45 ms (mean 36) with `work_priority=-8`, but 26 % of display frames late against 14 %. Same
finding as 21 November: raising the trackers' priority trades hand speed for display frames, so it is not adopted.
Two more runs were made with the glasses off the phone and are not counted. Not fixed: the cost of six windows'
drawing and pages against the hand tracker. Cause of the slowdown shown (tracker twice as slow as quiet); remedy not found.

Menu over the window: a new window stands at 2.0 m, the shelf too, and the window was drawn over the shelf only if
nearer than it, so marks and status line were drawn over a window at the usual distance. A window up to 2.25 m now covers
the shelf (`Shelf.windowCoversShelf`, tested); not seen in the glasses.

## The hand upgrade, 24 November 2026: what was measured, what was changed, what was not

Asked for: faster, snappier, more accurate, Quest-like. Measured first (replays of the owner's recordings of 22 and
23 November 2026 on the S21 Ultra, a quiet phone; scripts in `C:\uvio\handup`):

| before | |
|---|---|
| tracker, a pair with a hand | median 36 to 39 ms, P95 59, P99 69 to 72, 26 results a second (no hand: 17 ms, P99 58) |
| tracker under six windows, worn | 57 to 95 ms with a hand, a picture 124 to 196 ms old when used |
| fingertip jitter, hand nearly still | index tip 11.4 mm RMS against the wrist, thumb tip 5.9, the gap between them 7.0, the palm 3.4 |
| pinch, onset against the gap crossing 40 mm | median 56 ms later |
| ray, a click's movement of the ray (n=42, 0.12 s before it began to the release) | median 1.84 degrees, P90 8.38 |
| prediction: error of holding the last result against the next measured, 40 / 80 ms ahead | 7.6 / 12.5 mm (fast hands 14.9 / 25.8) |

Changed (each measured on its own before it was kept; the shipped Kotlin was benchmarked on the same recordings,
`HandBenchTest`, which writes `C:\uvio\handup\bench_out.txt`):

- **Pinch begins on the way in** (`PinchState.update(gap, finger, tNs, ageNs)`): onset 56 ms to 36 ms after the gap
  crossed 40 mm (3 pinches that the benchmark could match; the pinch rule's other numbers did not move: onsets 118 to
  119, brief ones 10 to 11). Not begun on a result older than 250 ms. Tried and **not** taken: a faster release on
  quick opening (6 more onsets, 5 more brief ones).
- **Ray** (`ArmRay`): smoothing by time and by how fast the ray turns, in place of three tenths a call at whatever
  rate it was called; the fixed 0.3 s freeze replaced by going back to where the ray was 0.12 s before the pinch and
  following the hand only past a 2.3 degree dead band. A click moves the ray a median 0.49 degrees against 1.84
  (P90 7.46 against 8.38). A still hand's ray: 0.59 degrees RMS before, 0.62 now.
- **Prediction** (`HandPredictor`, in the desktop's and the scenes' reading of a hand, `predict=0` turns it off): the hand's
  own motion, from the mean velocity of its joints over four results, above 5 cm/s, at most 80 ms: 40 ms ahead 7.6 to
  6.9 mm (fast hands 14.9 to 11.9), 80 ms ahead 12.5 to 11.3 (25.8 to 20.7); a still hand untouched.
  **Not applied to the OpenXR games' feed**: the runtime already carries each joint forward 30 ms from its last two
  results (`native/monado/patches/0003`), which in the same measurement is no better than holding (7.9 against 7.6 mm
  at 40 ms; better only for fast hands). Replacing it needs re-feeding at the display rate; not done.
- Kept as it was: PinchState's closing-movement rule, `HandSteady` and `HandTurnSteady` in the game feed.

Not done, and why:

- **NPU / Hexagon (QNN).** The two models are about 6 ms of a 22 to 39 ms result (2.0 and 3.9 ms a run, 2 threads,
  XNNPACK), and the rest is not neural. Where the rest goes (image preparation, the kinematic fit, the threads
  waiting on the windows) has not been profiled in the tracker, so no inference change was made. The search for an
  open tracker better than Mercury for stereo fisheye found none that is both permissively licensed and shown to be
  better (UmeTrack is CC BY-NC; MediaPipe is single camera and RGB); notes in `C:\uvio\research\hands_sota_1124.md`.
- **Fingertip jitter** is the tracker's: the fit of the fingers is 7 to 11 mm RMS noisy while the palm is 3 mm. A One
  Euro filter on the joints cut it to 4.8 mm only with 45 ms of lag, and 6.7 mm at 15 ms. So the 2 mm target is not
  reachable by filtering these results; the pinch rule works on the gap in finger lengths, with the closing movement.
- **Per-joint confidence, personal bone lengths, anatomical reconstruction of hidden fingers:** not built. Mercury
  reports no confidence, and the one kinematic hand model it fits already fixes the bone lengths.
- **Latency pinch to picture, 99 % recognition, under 0.5 % false activation:** not measured. There are 18 pinches in
  the recordings whose gap went under 22 mm and no labels for the rest.
- **Worn:** none of it has been worn.

## Second phase, 24 November 2026: the tracker profiled, what was tried, what is measured, what is not

Scripts and batches: `tools/tracking/hand_upgrade/` (the paths in them are this PC's `C:\uvio\...`). Native changes:
`native/hands/src/patches/0006-ultra-hands-profile.patch` (nothing changes unless an environment variable is set).

### Where the tracker's time goes (quiet phone, every pair processed, `ultra_hands_replay`, 3100 pairs of the 22 November recording)

| stage | ms a frame (mean) | ms a call | calls a frame |
|---|---|---|---|
| the whole frame (hand: median 27.8, P95 40.3, P99 45.0; no hand: median 9.8, P99 36.2) | 23.5 | 23.5 | 1.0 |
| detection, wall clock | 8.5 | 10.0 | 0.85 |
| whole-picture detector run, one view (two run at once) | 9.9 CPU | 4.6 | 2.1 |
| of it: preparing the picture | 0.8 | 0.4 | 2.1 |
| of it: the network | 9.0 | 4.2 | 2.1 |
| tiles and second looks | 5.1 | 4.8 | 1.06 |
| keypoints, wall clock | 11.9 | 11.9 | 1.0 |
| a keypoint job (one hand in one view) | 21.2 CPU | 11.1 | 1.9 |
| of it: crop and warp | 2.3 | 1.2 | 1.9 |
| of it: the network | 18.8 | 9.9 | 1.9 |
| the kinematic fit | 3.5 | 3.2 | 0.97 |

So the networks are not 6 ms of 39: they are 28 CPU-ms of a 23.5 ms frame (two threads), the larger part. The 6 ms figure was each model
alone with four threads. Detection runs in almost every frame (upstream looks for a second hand whenever it does not have both), and the
keypoint jobs take two rounds when both hands are tracked (four jobs, two workers). In the app, with the cameras, the estimator and the
room's depth work running as well, the same tracker takes 36 to 44 ms with a hand (P99 63 to 87): twice the quiet figure.

### Tried on the phone, each against a reference run (`cmp_runs.py`: runs repeat to the bit, so a difference is the change's)

| change | speed | what it did to the hands | taken? |
|---|---|---|---|
| INT8 models (QDQ, per-channel, percentile calibration on 600 real inputs; all convolutions, or without the first, last six and the depthwise ones) | slower: the network 6.9 ms against 4.9 (detector), 18.5 against 10 (keypoint) in the tracker; XNNPACK in ONNX Runtime does not run QDQ convolutions fast | the keypoint heat map's peak moved 0.7 to 2.9 cells (4 to 17 px of 128) | no |
| NNAPI with the INT8 or FP16 models | 25 and 58 ms; "no device takes the model" with the CPU off | | no |
| FP16 models on ONNX Runtime's CPU kernels | alone 25 % faster (detector 2.2 against 2.9 ms, keypoint 4.4 against 6.0, one thread); in the tracker not faster (32 to 38 against 30 to 36 ms) | the keypoints the same to 0.00 px, but 3 of 620 detector verdicts changed and the tracked hands differed: 182 more right-hand pairs, palm 200 mm off on average | no |
| FP16 in XNNPACK | does not run on this build (`xnn_create_convolution2d_nhwc_fp16` fails) | | no |
| XNNPACK 1 thread, or the plain kernels | 36 to 43 and 46 to 58 ms against 30 to 32 | same | no (2 is best) |
| 3 or 4 workers instead of 2 | 38 against 36 ms (more threads than the four big cores) | same | no |
| tracker on the four big cores (`taskset f0`) | harness: hand frames 29 against 36 ms (-19 %), P99 43 against 49; frames with no hand 23 against 15 | the same to the bit | harness only. Through the whole app on the worn session (`f_*` replays): 38.7 and 35.6 against 43.9 and 40.3 ms, P99 103 and 70 against 87 and 87, 26 gaps over 100 ms in one run against 11: not repeatably better, not adopted (as on 21 and 22 November) |
| looking for the second hand every 3rd frame (`ULTRA_HANDS_DETECT_EVERY=3`) | hand frames -14 % | on the labelled 17 November, tracked where plainly there 89 and 86 % against 92 and 92; every 2nd frame: a right hand reported where there is none in 34 % of results | no |
| the room's own work off | tracker 36.3 and 37.2 against 43.9 and 40.3 ms, P99 63 and 65 against 87 | | the cause of part of the slowness, see the next row |
| the room's pictures a third as often while a hand is tracked (`room_hand_slow`, now the default) | through the app, worn session: median 35.6 against 40.2 and 39.1 ms, P95 57 against 63 and 61, P99 68 against 82 and 72 | the room's surfaces were not compared (the room file of two of the three runs was empty) | yes |
| tiles and second looks left out while a hand is tracked (`ULTRA_HANDS_TILES_WHEN_ONE=0`) | built, not run | | no |

### Built and kept

- Stage timers in the tracker (`ULTRA_HANDS_PROFILE=1`), input dumping for calibration (`ULTRA_HANDS_DUMP_DIR`), worker and thread overrides for measuring.
- The room's pictures a third as often while a hand is tracked (above).
- A stale-result limit that follows how old results usually are (1.5 times their mean age, 110 to 200 ms; it was a flat 250 ms).
- The ray's look-back at a pinch shortened in proportion to how fast the ray is turning (above 0.8 radians a second it falls away). In sweeping clicks (4 of
  them) the ray at the pinch is 2.4 degrees from the live one, against 5.5 for the fixed 120 ms; median click drift 0.2 degrees, against 0.49 fixed and 1.84 before 24 November. Its 99th percentile on the same measure rose (20 against 12 degrees), which
  measures how far the hand moved during the click, not an error.
- Rays that can start at the index tip, the knuckles, half way, or from the eye (`HandReader(rayFrom, eye)`, `ArmRay(eye)`), for measuring; not used.
- `xr_predict=1` (off): the OpenXR games' hands carried to the display by `HandPredictor`, fed at 125 Hz, with the runtime's own 30 ms look-ahead (which in the
  measurement is no better than holding the last result) switched off. Not run with a hand.
- The hand lab (`Hand lab` on the launcher; `render/LabScene`, `core/GestureLab`): about 9 minutes of cues in the glasses: 40 single pinches, 14 quick pairs, 12 holds, 16
  two-hand pinches, 36 aimed at targets of known direction, 16 with the head turning, and 104 seconds in which no pinch is asked for (hands held still, hands waved). It scores itself (found, false, drops, result age at each
  onset, the ray's error at each target pinch) into `files/lab/lab-<time>.json`, and writes the tracker's joints and the head's place beside it, so that the pinch rule, the ray and the prediction can be run again over exactly that session
  (`HandLabEvalTest`, which also compares ray origins, shoulder against eye against fingertips, by their error at the targets).

### Not measured, not built, or blocked

- QNN and Hexagon: not tried. The Qualcomm AI Engine SDK is behind an accepted licence; INT8 through ONNX Runtime is slower on this phone and loses accuracy (above), so an NPU route needs the SDK, a better quantisation (training-aware) and a runtime other than ONNX Runtime's XNNPACK.
  A TensorFlow Lite GPU delegate was not tried (it would share the Adreno with the glasses' picture).
- Under 20 ms (25 ms loaded) is not reached: 28 ms median with a hand on a quiet phone, 36 ms in the app. The measured levers are worth 10 to 20 %; the rest is ONNX Runtime on the CPU.
- Camera to action, 99 % recognition, under 0.5 % false activation, 2 mm jitter: not measured; the hand lab exists to do it and has not been worn. Fingertip jitter is the tracker's (7 to 11 mm RMS).
- Personal hand size, per-joint confidence, hidden fingers: not built. Mercury fits one kinematic hand with a hand-size parameter that it refines in its first 100 frames, and reports no confidence; a confidence from reprojection error needs a change inside the fit.
- Chest-height detection, exposure, motion blur: not investigated beyond 20 November's findings.
- Nothing in this section has been worn.

## Third phase, 24 November 2026: the Hexagon, batching, where the fingertip noise comes from, who gets the processors

### Qualcomm QNN / Hexagon (HTP): blocked on the SDK, with everything else ready

- ONNX Runtime's page says its QNN provider "has been built and tested with QNN 2.22.x and Qualcomm SC8280, SM8350, Snapdragon X" (SM8350 is this phone's Snapdragon 888).
  Microsoft's Android package (`com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0`, 9.4 MB, Maven Central) contains ONNX Runtime with the provider **and none of Qualcomm's libraries**
  (it was downloaded and unpacked: `libonnxruntime.so` 24 MB and its JNI library only).
- The phone has Samsung's own SNPE and a v68 DSP skeleton for it in `/vendor` (`libSNPE.so`, `libsnpe_dsp_v68_domains_v3_skel.so`, `libQnnHtpAltPrepStub.so`), not the libraries the provider loads, and an app may not load them.
- **Tested on the phone** (`native/hands/test/qnn_bench.c`, built with the NDK against that package): the provider is created, then `QNN SetupBackend failed ... Unable to load backend, error: dlopen failed: library "libQnnHtp.so" not found`
  (in the Android log, tag `onnxruntime`; **nothing on stderr and no error from the call**), and the model then **runs silently on the CPU** (7.5 ms for the float keypoint model, 5.2 ms for the INT8 one: the CPU's own times).
  So any benchmark of it must read the Android log for `SetupBackend failed`; `tools/tracking/hand_upgrade/run_qnn.sh` does.
- What is needed from the owner (Qualcomm requires a login and an accepted licence; not something to do for them):
  1. Qualcomm AI Runtime SDK (QAIRT / "Qualcomm AI Engine Direct"): https://qpm.qualcomm.com/#/main/tools/details/Qualcomm_AI_Runtime_SDK (the link ONNX Runtime's page gives; also https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk). Neither page could be read by the tools here, so the exact steps are not confirmed.
  2. From its `lib/aarch64-android/`: `libQnnHtp.so`, `libQnnHtpV68Stub.so`, `libQnnSystem.so`, `libQnnHtpPrepare.so`; from `lib/hexagon-v68/unsigned/`: `libQnnHtpV68Skel.so` (file names and places from the SDK's usual layout, not read from their page). Copy the five to `C:\uvio\qnn\sdk\`.
  3. `bash C:\uvio\qnn\kit\run_qnn.sh`. It pushes everything, runs both models on the CPU, on the HTP as float with fp16 compute, as 8-bit and as 16-bit activations, and prints each time with the provider's own account of whether it took the graph.
  Which SDK version matches ONNX Runtime 1.29 is not stated (the page says 2.22.x; the provider's own log mentions QNN API 2.32 and later). Try the newest first.
  Redistribution of those libraries inside this app: the licence has not been read; do not ship them until it has.
- The Hexagon v68 runs integer graphs: a float model needs quantising. 8-bit activations lose too much on these models (the keypoint heat map's peak moves 0.7 to 2.9 cells); **16-bit activations with 8-bit weights**
  (supported by the provider) keep the peak within 0.28 cells on average (P95 1.4) and the detector's verdicts the same in 246 of 248 slots, on 600 real inputs. Those models are in the kit (`q_a16`).

### Batching the four landmark jobs (ONNX Runtime XNNPACK, quiet phone)

A batch-of-four keypoint model (symbolic batch dimension; its answers equal four single runs to 3e-7) takes 14.9 ms with 2 threads and 10.8 ms with 4, against 3.8 and 2.6 ms for one: **linear, no saving on the CPU**
(two samples: 7.4 and 5.1 ms). Batching would pay on an accelerator that has a dispatch cost; not on this one. Not taken.

### Where the fingertip noise comes from (what could and could not be shown)

- Mercury already weights each landmark by its own confidence (`confidence_xy` scales the 2D residual; `confidence_depth` cubed scales the relative depth), fits one hand size and reports a reprojection error, so
  "Mercury has no confidence" was wrong: it has them inside the tracker and does not export them. (Not changed.)
- Stereo geometry: at 40 cm, with the cameras' 240 px focal length and 137 mm baseline, one pixel of disparity is about 4.9 mm of depth, so a landmark that moves by one pixel moves the fingertip about 5 mm along the line of sight. The palm,
  whose landmarks are many and agree, shakes 3 to 5 mm across and 9 mm along the line of sight in the recordings; the fingertips, which have one landmark each, 14 to 26 mm across and 37 mm along. (That is on windows in which the **palm** was still;
  the fingers in them may really have moved, so these are upper bounds. The lab's hold-still cues give the clean figure, and `HandLabEvalTest` now splits it along and across the line of sight for the palm, wrist, thumb, index and middle fingertips.)
  The depth axis being worse than the others by 1.5 to 2 is what the geometry predicts; the lateral part is the landmark network's.
- Not attempted: a change in the fit (confidence-propagated uncertainty, anatomical priors beyond Mercury's kinematic model, a persisted hand size), because without a clean still-hand figure there is nothing to measure it against.

### Who gets the processors (thread priorities)

`room_priority=` and `hand_priority=` in `vio_options.txt` (Android thread priority; the room's depth and the place search, and the hand thread and the hand tracker's own worker threads). Default 0: nothing changed. Six replays of the worn session to compare
(`s_base`, `s_room` 10, `s_both` room 10 hand -4) **did not complete**: Samsung's app freezer (`FreecessController`, in the log: "disable frozen app (dev.ultraxr) wakelock", `PARTIAL_WAKE_LOCK 'ultraxr:replay' DISABLED`) froze the app at
121 s of the first run with the screen off. Keeping the screen on for the duration would avoid it; not done (a phone setting).

### Not done

Hybrid optical-flow and neural tracking, confidence-driven detection frequency, a rewritten fit, a TensorFlow Lite GPU delegate (it needs the model converted with TensorFlow, and shares the Adreno with the glasses' picture), a freshness rule based on confidence rather than age (the tracker's
confidences are not exported), measured pinch-to-picture latency, 30-minute stability, and everything the hand lab is for.
