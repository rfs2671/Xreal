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

## Where the code is

`native/hands/` (library, build scripts, notes), `tracking/Tracking.kt` (`handsLoop`), `core/Hands.kt`,
`core/SessionTools.kt` (`PinchState`), `core/ArmRay.kt`, `desktop/HandPointer.kt`, `xr/XrHost.kt` (hands for
OpenXR games), `tools/tracking/mono_hands_trial.py` (the MediaPipe trial), `docs/TRACKING_ANALYSIS.md`.

Not stated here because I have not measured it: the cameras' field of view in degrees. It follows from the
calibration file on the phone (`files/factory_config.json`).
