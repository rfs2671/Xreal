# Tracking, measured on recorded real data

One recording exists of the glasses on the owner's head: 240 s made on 17 November 2026 during the
first live run (`live-1794950506753.uxrec`, 4.5 GB, kept outside the repository because it shows
his room and the people in it). Everything below was measured on that one recording, on the
development machine, with `tools/tracking/analyse_recording.py` and `tools/tracking/stereo_check.py`.
One evening, one dim room, mostly sitting at a laptop: it is a narrow sample.

A second file, `live-1794950800293.uxrec` (3.0 GB, on the phone only), was made by mistake a few
minutes later with the glasses lying on a table. It holds no head motion and is not used. It has
not been deleted.

## What is in the recording

| | |
|---|---|
| Motion sensor | 234,506 samples at 1000 Hz. 43 gaps longer than 10 ms, the longest 36 ms. Reports reached the phone at most 27.5 ms apart. |
| Cameras | 7,178 left and 7,185 right frames at 30 Hz each. 22 and 15 frames lost (0.3 % and 0.2 %); longest interval 133 ms (three frames in a row). |
| Light | Median picture brightness 65 and 67 of 255: a dim room with one bright laptop screen and a few lamps. |
| Motion | Still (under 3 degrees a second) for 58 % of the time; 95 % of the time under 45 degrees a second; fastest 299. |

The sensor stream itself is healthy. Nothing here explains bad tracking by lost data.

## The two cameras and the calibration

The position estimator (Basalt) follows each point from the left camera into the right one and
keeps it only if the two sight lines meet within `optical_flow_epipolar_error` (0.005). The points
that pass are what give position its real-world scale. On the phone the log shows 10 to 25 of
about 150 points passing, which is weak.

`stereo_check.py` finds left-to-right matches from the pictures alone (optical flow, forward and
back, then a geometry test that does not use the calibration) and asks this unit's factory
calibration about each: 1,625 real matches from 60 stereo pairs.

| | |
|---|---|
| Baseline, angle between the cameras (calibration) | 137.3 mm, 9.71 degrees |
| Epipolar error of real matches, median | 0.0053 |
| Real matches inside the estimator's limit of 0.005 | 46 % |
| Inside 0.01 | 90 % |
| Inside 0.02 | 95 % |
| Best single rotation correction to the right camera | 0.47 degrees |
| With that correction: median error, share inside 0.005 | 0.0020, 79 % |

So the estimator's limit throws away more than half of the genuine left-right matches. Either the
calibration's angle between the cameras is off by a few tenths of a degree for this unit, or the
frame flexes that much on the head; the recording cannot tell which.

The correction is not one clean number. Fitted on each quarter of the recording by itself it comes
out as (0.16, -0.22, -0.09), (0.14, -0.19, 0.36), a degenerate fit, and (0.32, -0.96, -0.62)
degrees: two of the three axes agree in the first two quarters, the third does not, and the third
quarter (mostly one person close to the cameras) gives nothing usable. That is enough to say the
limit is too tight for this unit, not enough to hard-code a correction.

What follows from it, not yet measured: raising the limit to 0.01 should roughly double the points
seen by both cameras. Whether that improves position or lets bad matches in has to be measured by
replaying the recording through the estimator (see below). The setting can be changed without a
build: `config.optical_flow_epipolar_error=0.01` in `files/vio_options.txt` on the phone.

The search from the left picture into the right one, with OpenCV's optical flow standing in for
the estimator's, succeeded for about 31 % of corner points when started from where the calibration
puts a point 1.7 to 8 m away, and 21 to 24 % when started from nowhere or from 0.5 m. The starting
depth matters less than expected in this room; most of what fails, fails for lack of texture in
the dark parts of the picture.

## Hands

Correction to what was reported during the live run. The frames between 69 s and 90 s were first
read as "an open hand held up for twenty seconds that the tracker missed". At full size they show
the owner's right hand resting on the laptop's keyboard and his left holding the phone: hands in
ordinary use, not a hand held up to be tracked. That the tracker reported no hand there is not a
failure of detection.

What is still true: in the third run the right hand was tracked for 269 frames (about 9 s), the
left for none, and no pinch was registered; and the room was dim. There is no recording of that
run, so the thumb-to-index distances during it are not known. The session record now keeps, for
each hand, the smallest thumb-to-index distance seen and how many frames it was under 2, 3 and
5 cm, so the next session answers this without anyone having to describe it.

Changes made so that a hand that is tracked can click:

- While Ultra XR's own pinch rule says "pinching", the application is given a hand whose thumb tip
  and index tip touch. A browser decides a pinch by its own, tighter distance, which a tracked
  hand may never reach.
- The pinch distances, a brightening of the pictures before the hand tracker sees them, and the
  library's quality filter can be set from `files/hand_options.txt` for A/B runs. Their defaults
  are unchanged until a replay or a session says which is better.

## Replaying a recording through the trackers

`am start -n dev.ultraxr/.ui.LauncherActivity --es replay NAME --es replay_tag TAG` (glasses
unplugged) feeds `files/recordings/NAME` through the same decoding, pairing and clock mapping as
live data, at the recorded speed, and writes every position state and every hand result to
`files/replay/TAG-vio.csv` and `TAG-hands.csv`. With `vio_options.txt` and `hand_options.txt`
this compares settings on identical real motion.

## Replay results on the Pixel (17 November 2026)

`tools/pixel/lab.py replay` ran the recording through the trackers on the phone four times, with
the screen off (a foreground service; the first attempt, from an activity, was frozen by Android
after 30 s on the locked phone). Every run consumed all 14,363 frames and 234,506 motion reports.
`tools/tracking/replay_report.py` splits each run into the part on the head (0 to 97 s) and the
part where the glasses lay on a table (126 to 240 s), where the true position does not change.

| Setting | On the head: points seen by both cameras (median) | On the head: position trusted | On the table: position trusted | On the table: furthest the reported position strayed |
|---|---|---|---|---|
| as shipped (epipolar limit 0.005) | 20 | 99 % | 58 % | 0.08 m |
| limit 0.01 | 36 | 99 % | 100 % | 0.38 m |
| limit 0.02 | 40 | 99 % | 98 % | 0.86 m |
| limit 0.01, 4 pyramid levels | 22 | 99 % | 90 % | 5.18 m |

What this says:

- The prediction held: a limit of 0.01 gives 80 % more points seen by both cameras while worn.
- It bought nothing that can be measured on the head in this recording: trusted time, path
  length (5.3 to 5.5 m) and distance from the start (0.36 to 0.41 m) are the same in all four
  runs. There is no ground truth for the worn part, so "the same" is all that can be said.
- It cost something where the truth is known. With the glasses on a table and a poor view, the
  shipped limit leaves so few points that position is (rightly) not trusted and is held; the
  looser limits let enough weak matches through to be trusted, and the position then walks off
  by 0.4 to 0.9 m in two minutes. A fourth pyramid level is worse still.

Decision: the shipped limit stays. Loosening it is not an improvement on the evidence there is;
it would need the "is position trustworthy" rule tightened at the same time, and a recording
with a known path (walk out, come back to the same spot) to show a benefit. No such recording
exists yet. Nothing about 6DoF was changed in this build.

Hands in the replays: none found in any of the four runs (28,465 hand results), with or
without the picture brightening and with the quality filter off. As noted above, this
recording has no hand held up to be tracked, so it cannot say anything about pinches.

Estimator time per frame, median, on the phone during replay: 36 to 40 ms for position, 25 to
26 ms for hands.

## Drift, measured two ways, and what was changed (17 November 2026, evening)

The on-table numbers above were first read as "the tracker drifts while stationary". Looking at
the frames explains them: with the glasses put down, half of each picture is a blank table top a
few centimetres from the lens, with specular highlights, and the other half a distant room with
lamps; at one point an arm passes. That is a view no estimator does well on, and it is not what
the cameras see during play. Two separate questions follow, each with its own benchmark.

### On the head: the revisit benchmark

There is no ground truth for the worn part, so `tools/tracking/revisit.py` makes some: it finds
pairs of moments, at least 15 s apart, at which the left camera's picture is the same up to a
pure rotation (what is left over after the best rotation is under 1.5 pixels, which is 3 mm of
head movement for things half a metre away and 15 mm for things two metres away). At such a
pair the head was in the same place, so the distance the tracker reports between them is drift.
The recording gives 9 pairs, 18 to 82 s apart (the owner sitting back down at his laptop).

| Run (shipped estimator settings unless said) | Revisit error, median | worst |
|---|---|---|
| baseline | 2.7 cm | 7.4 cm |
| baseline again | 2.8 cm | 7.6 cm |
| right camera turned by the measured 0.24, -0.17, -0.04 degrees | 2.6 cm | 11.6 cm |
| epipolar limit 0.01 | 3.0 cm | 10.3 cm |
| epipolar limit 0.02 | 1.9 cm | 6.9 cm |
| epipolar limit 0.01, 4 pyramid levels | 2.1 cm | 5.1 cm |

Two baseline runs agree to 0.2 cm, so the benchmark repeats. With the shipped settings the head
comes back to within about 3 cm, 7 to 8 cm at worst, after up to 82 s. None of the alternatives
is better by more than the spread between them, and the two that look slightly better here are
the ones that fail worst on the table. Nine pairs of a seated wearer is a small sample and says
nothing about walking. On this evidence the shipped estimator settings stay.

About the calibration: with 300 stereo pairs from the worn part (11,133 real matches) the
right camera's rotation is off by 0.24, -0.17, -0.04 degrees, and the four quarters of that
stretch agree with each other ((0.23, -0.21), (0.20, -0.24), (0.24, -0.10), (0.30, -0.11) in the
first two axes). The offset is real. Applying it raises the points seen by both cameras from 20
to 31 and does not improve the revisit error, so it is not applied. It remains available as
`files/stereo_rotation_fix.txt` for a recording that can show a difference.

### Put down: the estimator can run away, and now cannot reach a game

Replaying the same recording with the same shipped settings five times, the estimator's own
position during the 114 s on the table ended 0.08 m, 0.26 m, 0.9 m, 6.5 m and 158 m from where
it started. It is not repeatable and at its worst it is useless, and the app called that
position trusted. The first live session's record shows the same thing (65 m of "head travel").

The motion sensor separates the two situations cleanly. Lying still, the gyroscope's scatter
over half a second was 0.10 to 0.11 deg/s; on the owner's head it was never under 1.26 deg/s
(accelerometer: 0.03 to 0.065 against at least 0.097 m/s2). So:

- `core/Stillness.kt`: the glasses are "lying still" after two quiet half-seconds (limits 0.35
  deg/s and 0.08 m/s2, between the two populations) and not at once on any sample 2 deg/s off.
- While they lie still the position given to applications is held, as it already was for an
  untrusted position.
- If the estimator wanders more than 10 cm while they lie still, or has wandered more than 3 cm
  when they are picked up, it is restarted and aligned to the held position, so a runaway
  estimate never reaches a game and tracking starts clean on pick-up.

| Run | Position given to the app strayed, on the table | "Lying still" while on the table | while worn | Revisit error while worn, median / worst |
|---|---|---|---|---|
| hold off | 163 m | 0 % | 0 % | 3.0 / 7.5 cm |
| hold on | 3.9 cm | 98.5 % | 0 % | 3.0 / 7.8 cm |
| hold on, again | 55.7 cm | 98.4 % | 0 % | 2.2 / 8.1 cm |
| hold on, with restart | 0.36 cm | 99.3 % | 0 % | 2.7 / 6.0 cm |
| hold on, with restart, again | 0.15 cm | 99.3 % | 0 % | 2.2 / 8.1 cm |

(The 55.7 cm run is what the restart is for: someone brushed past, the hold let go for a
moment, and an estimate that had drifted 1.4 m leaked through.)

The restart version is what ships. It is a fix for the glasses being put down, picked up and
bumped. It changes nothing while they are worn: the rule did not fire once in the worn 97 s of
any run.

Not addressed, because nothing here can measure it: drift while walking. That needs a recording
with a walk that returns to its start.

## Hands, measured at last (recording of 17 November 2026, 22:29)

After the owner reported that hand gestures did not register at all, a 45 s recording was made
with the glasses on and his hands held up and pinching (`live-1794972570764.uxrec`, 854 MB, kept
outside the repository). It was replayed through the hand tracker on the phone four times.

**Detection.** With the shipped settings each hand was found in 12 to 15 % of all results, which
sounds bad and is not: the hand that was raised into the middle of the view was found in every
result for as long as it was there (right hand 6 to 18 s: 171 of 173; left hand 33 to 36 s: 59 of
59), in runs of 114 and 174 results without a break. Between 18 and 30 s both hands were low, at
the bottom corners of the cameras' view, and neither was found once. Brightening the pictures
and switching the quality filter off changed the totals by a few results either way, inside the
run-to-run spread; neither is adopted.

So the tracker sees a hand that is up in front of the face and not one held at chest height. That
is where the cameras look, and it is a limit the wearer has to know about.

**Pinch.** The right hand's five deliberate pinches brought thumb tip and index tip, as the
tracker measures them, to 23, 17, 24, 31 and 23 mm; an open hand measured 50 mm and more. The
shipped rule needed under 18 mm for two results in a row. Tested on the recorded distances:

| Rule | Right hand's 5 pinches caught | Left hand's 2 |
|---|---|---|
| under 18 mm, over 32 mm to end, two results in a row (shipped) | 0 | 0 |
| under 25 / over 40, two in a row | 2 | 0 |
| under 32 / over 45, single results | 5 | 1 |
| under 34 / over 46, single results | 4 | 2 |
| under 38 / over 50, single results | 5 | 4 (two of them not pinches) |

The rule is now under 33 mm to start and over 46 mm to end, on single results (0.46 and 0.65 of
the index finger's 71 mm when the finger's length is known). Two results in a row was the wrong
safeguard at 15 results a second: a quick pinch is only two or three results long. The recorded
distances are a unit test (`SessionToolsTest.theOwnersRecordedPinchesAreCaught`).

While a hand is being tracked the tracker gives 15 results a second, not 30; the phone was also
warm (thermal status 2), which halves the rate again when it applies.

Not yet tried: these pinches inside a game. The numbers say the tracker measures them and the
new rule catches them; whether the browser then clicks where the hand points has to be seen.

## The angle between the cameras is off by 0.4 degrees: the floor says so (18 November 2026, evening)

The owner's session of 19:57 (83 s, position trusted throughout, walked to 3.3 m from the start) was recorded
whole (`record_next.txt`) and replayed. His words: a post drawn in the room stayed put, the floor was right near
it and wrong three metres on, a wall was too near and moved as he walked away.

A flat floor is ground truth for the one calibration error that the sight lines' meeting cannot show: the angle
between the two cameras about the axis square to the baseline. `tools/tracking/floor_flat.py` places the points
of 230 stereo pairs in the room and sorts the ones at floor level by the distance they were measured from.

| Right camera turned by | Floor under the head | Floor's height at 1.5 / 2.1 / 2.7 / 3.5 / 4 m from the cameras | Slope |
|---|---|---|---|
| nothing (factory calibration) | 1.57 m | +25 / +47 / +122 / +236 / +259 mm | +94 mm per m |
| 0, -0.35, 0 degrees | 1.69 m | +53 / +14 / +36 / +52 / +77 mm | +14 mm per m |
| 0, -0.45, 0 degrees | 1.74 m | +75 / +30 / +29 / +27 / +33 mm | -11 mm per m |
| 0, -0.55, 0 degrees | 1.78 m | +79 / +21 / -13 / -5 / -5 mm | -26 mm per m |

With the factory angle every distance is measured short, by 6 % for each metre of it: the floor comes out 12 to
16 cm too high under the wearer and a further 10 cm higher with every metre, a wall 3 m away half a metre near.
The two halves of the session, fitted separately, give -0.5 and -0.4 degrees. The estimator works from the same
angle, so it under-reported the walk: replayed with the correction the path is 30.1 m instead of 28.0 (7.6 %
longer), twice the same to 0.01 m without it.

Adopted for this pair of glasses: `stereo_fix_unit.txt` in the app's folder on the phone holds `0,-0.4,0` and is
applied to the calibration at every start (the log says so). It is this unit's number; another pair needs its
own run of `floor_flat.py`. Replayed as the owner now runs it: floor 1.69 m under the head.

The picture's tilt was judged (10 degrees) against the floor drawn too high; with the floor 14 cm lower the same
judgement is about 7 degrees, which is the cameras' own pitch against the motion sensor (7.2). It is set to 7.2
once, unless the owner had changed it. Not yet seen in the glasses.

Not explained by this: a table top found where there is none.

## The camera angle now watches itself; walls and tables stay (19 November 2026)

First steps of the roadmap in `reports/XREAL Quest level spatial roadmap.md`.

**The floor as a calibration target, in the app.** `core/FloorCalibrator` sorts the floor points of every picture by
the distance they were measured from; the slope of floor height with distance, times baseline over camera height, is
the error in the angle between the cameras. Replayed on the owner's session of 18 November (83 s) on the Pixel:

| Calibration in use | What the app measured | What it did |
|---|---|---|
| factory (trial file 0,0,0) | floor rises 109 mm per m, 4 distance bands, 1,582 points | nothing: a trial calibration is never changed |
| the unit's -0.4 degrees | floor falls 37 mm per m (an error of -0.17 degrees), 6 bands, 1,669 points | proposed a trim |

The offline tool, which matches points another way, put the flat floor at -0.41 degrees; the app's own points put it
near -0.30. A tenth of a degree between two ways of matching the same recording is the uncertainty of this method on
one session, about 2.5 % of a distance of 3 m. So the app moves the correction by half the measured error and by
0.06 degrees at most per session, never further than 1 degree from none, and only from the next start (the
estimator reads its calibration once). A tilt of the whole rig against gravity gives the same straight line and
cannot be told apart by a floor; that is why the steps are small.

**A surface that was believed stays.** A wall or table top used to go when it had not been found for about four
seconds, whichever way the wearer looked. Now one that has been believed goes only when the cameras look at where it
should be, from within 3 m, and do not find it twelve times running. In the same replay: a wall vanished once
instead of twice, a table top never instead of three times, and two walls stood at the end instead of one.

Not built yet from the roadmap: dense depth, a room mesh, occlusion, a saved map. Not seen in the glasses.

## Dense depth, and walls from a floor plan (19 November 2026, night)

**Dense depth (`core/DenseStereo`).** Both pictures are redrawn as ordinary 240 x 320 views with their rows lined up,
matched by semi-global matching on a 5 x 5 census (64 disparities, four directions), checked left against right, and
each answer is then sharpened on the full-size pictures. On the owner's recorded session about half of all pixels
get a distance; 113 to 158 ms a pair on the laptop, 290 to 330 ms on the Pixel, run once a second off the tracking
thread (the estimator's own time per frame did not change: 26 ms median).

How good one distance is: floor points measured from 2 to 2.5 m scatter about 8 cm in height around the floor, which
is about 10 cm in distance. Part of that is the head's pose at the moment of the picture, not the matching:
sharpening the match on full-size pictures did not narrow it.

**A fused mesh (`core/RoomMesh`): built, measured, not used.** A 5 cm signed-distance grid in hashed blocks, as in
Chisel. It puts the floor at 1.70 to 1.71 m under the head, the same as every other measurement, but its surface is
rough at every setting tried (5 and 8 cm cells, three weights): in the floor's own height band there are more
upright faces than level ones. Used to hide virtual things it would hide them wrongly, so nothing draws it and
nothing is hidden by it. What smears it is several centimetres of disagreement between pictures (pose, and what is
left of the camera-angle error), which a finer match does not remove.

**Dense depth fed to the corner-based finder: worse.** It took a level 20 cm under the floor for the floor and lost
the wall. Not done.

**Walls from a floor plan (`core/WallMap`): adopted.** Every place between 0.4 and 1.9 m over the floor is counted on
a plan of 5 cm squares; a wall is a bright straight line there however rough one picture is, and lines are found by
Hough voting. On the same recording: the long wall (3.6 m), the far wall (2.1 m) and the front of the piano (1.6 m),
where the corner-based finder had the long wall only. In the app the plan's walls replace the corner-based ones once
there are any; they stand from the floor to 2.3 m. Replayed on the Pixel: three walls at the end. Switch:
`dense_walls=0` in `files/vio_options.txt`.

Not seen in the glasses. Not built: table tops from dense depth, a usable mesh, hiding virtual things behind real ones.

## A single-camera hand tracker, tried on the recordings (19 November 2026, evening)

The owner asked for hands to be read over more of what the cameras see. The tracker in use (Mercury) needs a hand
in both cameras. MediaPipe's Hand Landmarker (Apache 2.0, one picture at a time) was run on the laptop over the
two hand recordings, each camera's upright 480x640 picture by itself, detection threshold 0.3
(`tools/tracking/mono_hands_trial.py`; the library and its model are outside the repository).

| Recording | Mercury finds a hand (its results on the phone) | MediaPipe finds one in either camera | in both |
|---|---|---|---|
| 17 November, 44 s, hands raised and lowered | 23 % | 38 % of 78 pairs | 3 % |
| the same, pictures brightened (CLAHE) | | 10 % | 0 % |
| 19 November on the S21 Ultra, 70 s, hands held up | 84 % | 18 % of 349 pairs | 5 % |

In the ten seconds of the first recording where the hands hang low at the sides (Mercury: none), MediaPipe found
none either: there the hands are at the very edge of the fisheye pictures, cut off and stretched. Where a hand is
held up it mostly finds it in one camera and not the other, and on the second recording it finds far fewer than
Mercury does.

So as it comes, on these dim grey fisheye pictures, it is worse than what is in use and does not reach the hands
it was hoped for. Not adopted. What it would take to try in earnest: turning each fisheye picture into ordinary
views aimed down and to the sides before detection, and then measuring again on these same recordings.

