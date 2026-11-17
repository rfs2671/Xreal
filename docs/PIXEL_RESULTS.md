# Pixel 10 Pro XL + Air 2 Ultra: measured results (2026-11-16)

Hardware: glasses `G428X00969` (DP `1140`, SOC `12.1.00.502_20250408`), Pixel 10 Pro XL
(Android 17, build CP3A.261005.005), Windows laptop (ASUS GA402RJ, AMD graphics).
Everything below was measured on this hardware on this date. Where something was not
measured, it says so.

## Firmware

- Stage 1 (official image) and Stage 2 (corrected image, mkcalc + nohdcp) were written with
  `tools/ultra-dp/ultra_dp_writer.py`. Both ended with FINISH status 0 and the same glasses id,
  DP `1140` and SOC version afterwards. The SOC was not touched. Restore was never run.
- The corrected image is running: the Pixel kernel logs `HDCP22 is not supported.
  rxcaps(0x000000)` and `HDCP13 is not supported` one second after video start. Before the
  write, Android flagged the same display `FLAG_SECURE` and the kernel log held an HDCP
  handshake for each connect.
- Windows still shows a picture with the corrected image (1920x1080, total 2200x1125,
  148.5 MHz, RGB 8 bpc).

## 2D on the Pixel: works

- Four consecutive physical plugs gave a picture in the glasses. Kernel log for a working
  connect: DPCD caps `12 0a 84` (DP 1.2, HBR, 4 lanes), link trained `rate=1 lanes=4`, no FEC,
  no DSC, mode `1920x1080 60 148500 1920 2008 2052 2200 1080 1084 1089 1125`, link BPC 8.
  That is the same timing Windows sends.
- On a working connect the glasses' controller sends `0x6C04` (display toggled) and logs
  `Open OLED 2D` when worn and `Close OLED` when taken off. On the pre-fix black screen it
  sent neither.
- The very first Pixel session after the write (19:26 to 19:36) stayed black although Android
  showed the display ON at 1080p60. No kernel log exists for that connect, so the cause is
  unknown.
- Android asks "Connect to external display" (Desktop / Mirror / Cancel) and for USB access
  again after the glasses re-enumerate. The display stays disabled until that is answered.

## Stereo on the Pixel: does not work in any glasses mode

| Glasses mode | EDID preferred mode | What the Pixel kernel did |
|---|---|---|
| 2D 60 (1) | 1920x1080@60, 148.5 MHz | PICK, picture |
| 2D 90 / 120 | 222.75 / 297 MHz (extra DTDs in the 2D EDID) | DROP |
| SBS 60 (3) | 3840x1080@60, 297 MHz, 4400x1125 total | DROP, falls back to 640x480 |
| half-SBS (8) | 1920x1080@60, 148.5 MHz | see below |

Half-SBS: in this mode the glasses' DPCD caps read `12 06 84` (maximum link rate RBR).
The Pixel tried RBR on 4 lanes, clock recovery failed four times, it reduced to 2 lanes,
trained `rate=0 lanes=2`, then dropped 1920x1080@60 and picked 640x480. Seen on two plugs.
On one earlier soft switch Android reported 1920x1080 ON in half-SBS and the panels were
still black; there is no kernel log for that one.

The kernel prints `DROP` without a reason. Why the 222.75 and 297 MHz modes are dropped on an
HBR x 4 link is not established here.

Side effects seen while testing: after a failed mode switch the phone holds the 640x480
fallback with the display OFF, and a further mode write does not always re-plug the link.
A physical replug with the glasses back in mode 1 recovers 2D.

A half-SBS renderer was built and tested in this session and then removed again, because the
mode cannot be shown on this phone.

### Why 3840x1080@60 is rejected: what is and is not known

- Known from the kernel log: on an HBR x 4-lane link (8.64 Gbit/s of payload, enough for
  360 MHz at 24 bit per pixel) the driver picks 148.5 MHz and drops 222.75 MHz and 297 MHz.
  So the limit is not the link's raw capacity at 8 bpc. On RBR x 2 lanes it drops 148.5 MHz
  and picks 25.175 MHz.
- Not known: the rule the driver applies. The Pixel 10 DisplayPort driver (`dwc_dptx`) was
  not found in the public Android kernel repositories searched on 2026-11-16
  (`kernel/google-modules/display` has only gs101-family branches), so the reason behind
  `DROP` could not be read from source.
- Consequence for workarounds: any 3840x1080 timing the glasses accept (their side-by-side
  EDID advertises 50 Hz as the lowest vertical rate) needs well over 148.5 MHz; a
  reduced-blanking 3840x1080@60 is still about 267 MHz. No timing between the picked and the
  dropped values has been tried, an ordinary app cannot add display modes, and the glasses'
  EDID cannot be changed without firmware. No legitimate app-side workaround is known.
- Untested ideas, listed only so they are not lost: a different phone-side driver build;
  a USB graphics (non-DisplayPort) adapter. Neither has any evidence behind it here.

## 6DoF

- With the display working, the hardware check passed position tracking once (5 s, 0.81 m).
- The estimator is reset repeatedly by the app with `speed 6.0 m/s`, and in one run after a
  reset it produced no state for the rest of the check. See the tracking notes for the
  investigation; nothing in this section is a fix.

## Tools added

- `tools/ultra-diag/ultra_listen.py`: listens to the glasses' controller on Windows
  (wear events, firmware text log). Can write the current display mode back once; cannot build
  any firmware-update message.
- `tools/ultra-diag/win_display_timing.ps1`: prints the video timing Windows sends (read-only).
- Kernel DisplayPort lines on the Pixel are only readable through `adb bugreport`
  (section `KERNEL LOG (dmesg)`, driver `dwc_dptx`); the ring buffer holds about five minutes.

## Why 3D modes are dropped, from the driver source (2026-11-17)

Read from the Pixel 10 display driver (`dwc_dptx`, Android 16 source; the phone's Android 17
kernel log shows the same decisions). Details and an untested patch: tools/kernel-experiments/README.md.

- The driver keeps a mode only if it fits the link, is exactly 60 Hz, and is one of eleven
  listed sizes and clocks. 3840x1080 (full 3D) is not listed; 1920x1080 at 90 and 120 Hz fail the
  60 Hz rule.
- Separately, the clock table has no 148.5 MHz entry, the PLL rate a 297 MHz pixel clock needs.
  Lifting the list alone would not give a picture.
- Half-width 3D (mode 8) is an ordinary 1920x1080@60 picture and passes. In that mode the
  glasses advertise only the lowest link rate (DPCD `12 06`); four lanes then carry it, but in the
  saved log four-lane training succeeded twice and failed twice (falling to two lanes, where
  1920x1080 no longer fits and the phone shows 640x480). When it succeeded the picture ran for
  about 90 and 140 seconds until the mode was changed by hand.
- The app's old guard put the glasses back in 2D after 8 s whenever no 3840-wide display
  appeared, which also ended half-width 3D. The desktop now runs half-width 3D through
  `StereoSwitch` instead (three attempts, then 2D).

## First live WebXR run on the glasses, 17 November 2026

One short run by the owner: Ultra XR, XR dragging, Wolvic. He reported "glasses go black". From
the phone's log, a capture of what the phone sent to the glasses, and the session record:

- The 3D command was accepted on the first attempt. The link dropped and came back at
  1920x1080@60 after 3.4 s (no narrow link this time). The glasses reported half-width 3D for
  about 3 s, then logged `Open OLED 2D` and reported mode 1 again, with no link restart. The
  app sent no mode command at that moment. So on this run the glasses did not keep 3D once
  their screen came on. Whether they ever show half-width 3D from this phone is not established.
- The app had already started the browser with one view per eye. With the glasses in 2D that
  put both eye pictures in front of both eyes, squashed: the state that must never happen.
  The picture was also Wolvic's dark start-up world, which is close to invisible on see-through
  glasses in a lit room. The browser window was not in the one capture taken (taken after the
  glasses were put down, so that says nothing about where it was while worn).
- The runtime, compositor and browser worked on the phone: Monado on the phone's GPU, Wolvic's
  OpenXR session reached FOCUSED, the phone composited two eye views to the glasses display.
- 6DoF while worn (first 100 s): position trusted, 0.13 m from the start point, 20 to 90
  landmarks. The session record's 65 m "maximum travel" comes from after the glasses were put
  down and is not a wearing result.
- Hands: 0 frames in which a hand was tracked in 240 s. Not known whether hands were held up.
- Frame pacing: about 1000 "missed frame" warnings in the first minute, median 1.5 ms late.
- The phone locked once during the run (16 s in), which switched the glasses' screen off for 3 s.
- A 240 s raw tracking recording (2.7 GB) is on the phone.

Changed because of this run:

- `StereoSwitch`: 3D only counts after the glasses have held it for 6 s with a full picture.
  Falling back to 2D during that time is a failed attempt (retried, three in all, then 2D).
- `XrHost`: if the glasses leave 3D during a session the runtime switches to one view at once.
- The glasses' mode changes are written to the log with their numbers.

None of these changes has been run on the glasses.

### Second and third look, same day

- With the stricter check the app made its three attempts. Each time: mode read 1 -> 8, link back
  at 1920x1080@60, and 2.3 s later mode read 8 -> 1 with no link restart. Four attempts, four
  identical results. Half-width 3D does not hold on this phone.
- After that fallback the glasses stay black although they report 2D, log `Open OLED 2D`, and
  Android composites a correct picture to them (captured over adb three times: Wolvic's licence
  panel, its start-up world). They showed a picture before the first attempt. So the fallback
  leaves the video link without a picture; writing mode 1 again does not restart it. Unplugging
  and plugging in again is expected to restore 2D; not yet confirmed.
- Single-view rendering works on the phone: one picture, not two halves.
- Change: half-width 3D is no longer attempted unless "Try half-width 3D (experimental)" is
  switched on in Settings (off by default; covers games and the desktop). A game now starts
  directly in 2D.

### First picture seen in the glasses from a WebXR browser session

After unplugging and plugging the glasses in again, with 3D attempts off, the owner confirmed he
saw the picture: Wolvic's terms of service panel, in 2D (one picture for both eyes). From the
session record and log (about 85 s worn):

- 6DoF: position trusted while worn; a capture taken after he had moved shows the panel off to
  one side, i.e. it stayed in the room rather than following the head. Not confirmed by him.
- Hands: the right hand was tracked for 269 frames (about 9 s); the left for none. No pinch was
  registered. Whether he pinched is not known.
- Frame pacing: 3172 of roughly 5040 compositor frames were flagged late, median 3.9 ms,
  95th percentile 7 ms, worst 16 ms.
- Not reached: accepting Wolvic's terms (shown inside the glasses, needs a pinch on AGREE),
  entering XR dragging, any interaction.
