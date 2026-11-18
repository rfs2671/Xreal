# Spatial awareness: what was tested, what was ruled out, what is not known (18 November 2026)

The owner's verdict after three looks through the glasses: the floor shows too high, even after lowering it as
far as the control allowed; table tops drift as he walks; walls are slow to appear; "nowhere near ready". This
page is the investigation that followed. Every number here was measured on this machine from the owner's
recording of 17 November (`live-1794950506753.uxrec`, 240 s, standing at a kitchen counter) or read from the
phone's log. Nothing here was seen through the glasses by the author.

## What the owner's last test established

From the phone's log of that session (10:20 to 10:23):

- the cameras put the floor 1.27 to 1.55 m below themselves while he stood and walked (1.50 to 1.58 m below the
  world's origin);
- he pressed "floor down" to the limit, 40 cm, so the floor was drawn about 1.9 to 1.95 m below the cameras;
- it still looked too high. His eyes are about 1.74 m above the floor (he is 6 ft 1 in).

A floor drawn 15 to 20 cm *below* the real one cannot look too high because of where it was measured. So the
height of the floor is not what makes it look high. Something between "where the app says the floor is" and "what
he sees" is off, and that part cannot be observed from a recording.

## Ruled out, with the measurement

| Suspect | Test | Result |
|---|---|---|
| The glasses' display is tilted against their motion sensor and the app ignores it | The factory calibration's display section; two independent open-source drivers | Factory: displays turned 0.7 and 1.0 degrees about the vertical, 0.16 degrees in pitch. `ar-drivers-rs` (nreal_air.rs): "Apparently there is no noticable tilt". `XRLinuxDriver` (xreal.c): pitch adjustment 0.0 for the Air 2 Ultra (35 for the One Pro only). No tilt to apply. |
| The cameras are not turned against the motion sensor the way the calibration says | `tools/tracking/gyro_camera_check.py`: 526 and 545 head turns, each measured by the gyroscope and by the picture's motion in each camera | Agrees with the calibration within 1.5 and 0.6 degrees, and the thirds of the recording scatter by about a degree, so no difference is shown. Clock delay within a few milliseconds of the calibration's. |
| The simplified lens model (kb4, fitted to the factory's fisheye624, 0.7 px off on average) shortens distances | `tools/tracking/exact_model_check.py`: the owner's phone in his hand (76.6 mm wide), placed with both models | Same answer from both: top edge 75.9 mm. The fit is not the cause. |
| The cameras' distance scale is wrong | The same phone, and the counter | Phone's top edge 0.99 of real at 0.4 m. Counter top 0.60 m under the cameras, floor 1.46 to 1.50 m under them: 0.88 m apart, against 0.91 m for a standard counter. Scale is right within a few percent. |
| The floor estimate is far from the real floor | A second method that uses every pixel instead of corners (`tools/tracking/floor_sweep.py`: each height from 0.5 to 2.3 m is tried against both pictures) | Its strongest answers, in the pictures where he looks down 36 to 41 degrees, are 1.44 to 1.54 m. The corner method gave 1.37 to 1.41 m on the laptop and 1.44 to 1.52 m on the Pixel. So the corner method reads the floor about 5 to 10 cm high (it finds things lying on a floor more readily than a plain tiled floor), not 40 cm. |

A calibration effect that is real but small: turning the right camera by 0.17 degrees (the size an earlier
fit from real stereo pairs suggested, which a glasses frame flexing on a head could cause) moves the measured
floor by 8 cm. It was not applied: the recording cannot confirm the angle.

## Not ruled out, because it cannot be seen from here

1. **The picture is not where the app thinks it is, on this pair of devices.** The display's field of view and
   centre come from the factory file (42.4 x 24.7 degrees); XREAL's published figure is 52 degrees across the
   diagonal (about 46 x 27). The browser's own handling of the single 2D view on the phone was only ever checked
   on an emulator.
2. **There is no depth.** The glasses stay in 2D from this phone, so both eyes get one picture. By the factory
   file's display angles the two eyes' pictures meet 3.9 m away, whatever is drawn. A floor two metres from the
   eyes is then seen double or at the wrong distance when both eyes are open. What that does to "where the floor
   looks" is a matter of perception that has to be asked, not computed.
3. **How the owner judges "too high"**: against the floor near his feet, far away, or while walking.

These are separated by the test below, in a minute, without relying on impressions.

## What changed on the evidence

- **The touchpad shows the measurement**: "Cameras: floor 1.52 m under the glasses". Standing straight, that
  number should be the wearer's eye height. It splits the question in two at a glance.
- **Floor buttons reach 1.2 m** either way (were 0.4 m).
- **Room check draws a ring on each tracked fingertip.** The cameras that track the hand sit beside the eyes, so
  a ring that is not on its fingertip (one eye closed) is the display's error, directly, in finger widths.
- **The page lines the room up once and holds it** (`ultraxr-room.js`): the middle of the last 21 readings taken
  while the head was calm, instead of nudging with every reading. A head turning at 100 degrees a second made each
  reading 5 to 10 degrees wrong, and those nudges moved table tops and walls about. Not yet seen.
- **The room is looked over every 0.7 s instead of every second**, so a wall is believed in about 2 s.

What was tried and not shipped: the every-pixel floor finder. On this dim, tiled floor its right answer stands
only 7 % above the wrong ones when pictures are added up, which is not enough to trust it alone.

## What is still true and not fixed

- Position tracking loses the wearer on some walks (twice in one session on 18 November, jumps of 1 to 3 m).
  Surfaces are dropped and the floor carried over when that happens; the tracking itself was not improved.
- Table tops and walls are placed by the position estimate, which drifts over a walk. They are right near where
  the wearer stands and looks.
- A plain floor gives the cameras little to hold on to; the floor is found sooner on a patterned one.
- No depth (2D only) on this phone: see `PIXEL_RESULTS.md`.

## The one test that decides

In `ACCEPTANCE.md`. It asks five things, each with a number or a yes/no, and each answer points at one cause.
