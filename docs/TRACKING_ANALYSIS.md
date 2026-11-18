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
