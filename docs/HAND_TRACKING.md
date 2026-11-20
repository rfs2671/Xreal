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

## Where the code is

`native/hands/` (library, build scripts, notes), `tracking/Tracking.kt` (`handsLoop`), `core/Hands.kt`,
`core/SessionTools.kt` (`PinchState`), `core/ArmRay.kt`, `desktop/HandPointer.kt`, `xr/XrHost.kt` (hands for
OpenXR games), `tools/tracking/mono_hands_trial.py` (the MediaPipe trial), `docs/TRACKING_ANALYSIS.md`.

Not stated here because I have not measured it: the cameras' field of view in degrees. It follows from the
calibration file on the phone (`files/factory_config.json`).
