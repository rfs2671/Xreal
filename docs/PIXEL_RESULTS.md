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

## Half-width 3D on this phone: the evidence, suspect by suspect

Four things could end half-width 3D (glasses mode 8) after two seconds. What the logs say about
each:

**1. The Pixel's link training: not the cause.** The phone's DisplayPort driver log exists for
two earlier entries into mode 8 (bug report of 16 November, `ultra-dp-logs/pixel-dmesg-halfsbs.txt`).
Both times:

- the glasses advertised the low link rate (`RECEIVER CAPS: 12 06 84`, against `12 0a 84` in 2D)
  and a plain 1920x1080@60 picture (EDID detailed timing 148.5 MHz, the same as in 2D);
- training finished in one pass: clock recovery after two voltage steps on one lane, channel
  equalisation at once, `LINK TRAINING DONE: rate=0 lanes=4`;
- the driver enabled `1920x1080 60 148500 ...` with `tu = 44` (3.56 of 5.18 Gbit/s used);
- and the link then stayed up for 90 s and for 137 s, with no error, no interrupt from the
  glasses and no retraining, until the mode was changed by hand.

So the phone can train and hold the link the glasses ask for in this mode. (An earlier section
of this document describes two other plugs where four-lane training failed and the phone fell
to two lanes and 640x480. That failure exists too, but it is a different one: it shows as a
narrow display, which the app detects and gives up on. It did not occur on 17 November.)

**2. The app restoring 2D: not the cause.** On 17 November every mode command the app sent is in
its log. In the four failed attempts it sent the 3D command and nothing else until the glasses
were already reporting 2D; its own return-to-2D command, where it sent one, came afterwards.
The glasses' mode was read back as 8, then as 1, with no command in between.

**3. The phone's link dropping when the glasses change their mind: not seen.** On 17 November
the phone's display for the glasses stayed present at 1920x1080 through the change back to 2D.
(There is no driver log for those attempts: the kernel's log had rolled over before it was read.)

**4. The glasses themselves: what is left.** The timing of all five attempts on 17 November,
in seconds after the 3D command (from the app's log; the mode is read every two seconds, so
"reported 2D" is the first reading that showed it):

| | 16:22:21 | 16:33:05 | 16:33:26 | 16:33:35 | 16:33:44 |
|---|---|---|---|---|---|
| phone's display for the glasses gone | 0.3 | 0.4 | 0.3 | 0.4 | 0.3 |
| glasses log "Close OLED" | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 |
| glasses report mode 8 | (not logged) | 1.1 | 2.3 | 2.3 | 2.3 |
| display back at 1920x1080 | 3.7 | 3.5 | 2.0 | 2.1 | 2.1 |
| glasses report mode 1 again | by 6.8 | 5.7 | 4.5 | 4.6 | 4.5 |
| glasses log "Open OLED 2D" | 6.7 | 6.7 | 5.5 | 4.5 | 5.5 |

Every time, the glasses took the mode, the picture came back, and 2.2 to 3.1 s after that they
reported 2D again; their screen then came on with their own log line saying "2D". They were
being worn. On 16 November, when the mode held for 90 and 137 s, nobody was wearing them and
their screen did not come on at all.

What this does and does not establish: the mode is abandoned by the glasses, two to three
seconds after the picture returns and just before they switch their screen on, not by the
phone's link and not by the app. Whether being worn is what makes the difference from
16 November is likely but not shown (it is the only difference recorded). Why their firmware
goes back to 2D is not known. Reading that out of the firmware was started and stopped
(this environment's safety check ended it); no firmware was modified at any point.

Whether a real picture has ever been shown in mode 8 from this phone is also not known: on
16 November nobody was looking.

### What can still be tried without touching firmware or kernel

All reversible, each a single command sequence, none tried yet because each needs the glasses
connected and worn:

- **Ask again once the screen is on.** If the glasses drop the mode only on the way to
  switching their screen on, a second request made once it is on may hold. `GlassesLink.diag3d`
  does exactly this, once, with everything logged five times a second, and
  `tools/pixel/lab.py diag3d` runs it and then pulls the phone's DisplayPort driver log for the
  same seconds out of a bug report.
- **The same sequence from the laptop.** If the glasses hold mode 8 with their screen on when a
  PC is the source, the difference is in what the Pixel sends; if they drop it there too, it is
  how this firmware treats mode 8, and no phone-side change will help.

Until one of these says otherwise, the product stays as it is: 2D by default, the 3D attempt a
switch in Settings, bounded, and a session that loses 3D drops to one picture at once.

## 17 November 2026, evening: 3D experiment, first game session, and the browser's graphics crash

### The 3D experiment (worn, one minute)

`tools/pixel/lab.py diag3d`, with the phone's DisplayPort driver log for the same seconds:

- 3D asked for at 0 s: accepted; the glasses reported mode 8 at 0.19 s; link down at 0.4 s and back
  at 2.0 s. Driver: `RECEIVER CAPS 12 06 84`, `LINK TRAINING DONE: rate=0 lanes=4` in one pass,
  `ENABLE MODE 1920x1080 60 148500`, `tu = 44`. No error, no interrupt from the glasses.
- The glasses reported mode 1 again at 4.08 s, 2.1 s after the picture was enabled.
- Asked again at 13.75 s with their screen already on: the same, mode 1 again 2.1 s after enable.
- The owner saw black only, never a picture.

So asking again once the screen is on does not help, the phone's side is clean both times, and
no picture has been seen in mode 8 from this phone. With firmware and kernel off the table,
stereo is blocked on this pair of devices. The one thing left that could add information is
the same sequence with a PC as the source, which would say whether these glasses ever hold
mode 8 while worn; it would not change what this phone can do.

### First game session on the hardware

- Browser window, page and touchpad pointer all visible and working; the owner accepted the
  browser's terms with the touchpad.
- His tap on the page's START XR did not start the game. The pointer was attached to the head,
  so it moved with every small head movement and a tap became a drag. Changed: the pointer now
  stays where it is in the room unless the finger is moving it.
- Started from the laptop instead (developer tools, a real user-gesture click on the page's own
  button), the page entered `immersive-ar` with additive blending, two views, and the touchpad
  pointer as its right hand. That is the first WebXR session on the Pixel and the glasses.
- Six seconds later the picture froze as a half-drawn frame that moved with the head.

### Why it froze: the browser's graphics process loses its context

Wolvic's Chromium GPU process reports its graphics context lost and restarts. In the logs of
every session that day this happened about two seconds after the browser started on the XR
dragging page, and again a few seconds into the immersive session, which is what froze it.

What was tried, all on the phone without the glasses, 20 s per start:

| Change | Context lost |
|---|---|
| as it was | 5 of 6 starts |
| compositor's GPU queue priority lowered to medium (confirmed in the log) | 6 of 6 |
| Chromium's Vulkan path off (confirmed: GPU process in GL mode) | 4 of 5; the driver now names Chromium's own context guilty (`GL_GUILTY_CONTEXT_RESET_KHR`) |

So neither the runtime's priority nor Vulkan is the cause. It depends on the page:

| Page (browser with Vulkan off) | Context lost at start |
|---|---|
| example.com (no WebGL) | 0 of 4 |
| three.js cube (plain WebGL) | 0 of 2 |
| three.js shadow map | 0 of 2 |
| three.js AR cones | 0 of 2 |
| three.js ball shooter | 0 of 1 |
| three.js hand input cubes | 0 of 1 |
| three.js XR dragging | 2 of 3 |

XR dragging is the only page that does it. What in that page the PowerVR driver objects to is
not known (it is not shadows alone, and not WebXR alone).

Then the pages that start cleanly, in immersive sessions on the phone (started from the laptop,
glasses not attached, 40 s each, frame counts read from the page every 10 s):

| Page | Session | Frames at 10 / 20 / 30 / 40 s |
|---|---|---|
| AR cones | immersive-ar, additive | 805 / 1642 / 2553 / 3398 |
| Ball shooter | immersive-ar, additive | 726 / 1470 / 2228 / 2975 |
| Hand input cubes (requires hand tracking) | immersive-vr | 803 / 1633 / 2585 / 3414 |

Frames kept arriving for the whole 40 s in all three. One context loss was logged in each of
those three runs and none in a fourth (ball shooter, 32 s), so the loss is not gone, but these
pages keep running through it. What the picture looks like in these sessions has not been
seen: the phone had locked its screen, and nobody was wearing the glasses.

Changes: Vulkan stays off in the phone's browser (the browser's own source does the same for
its x86 builds, and it turned an anonymous context loss into one the driver attributes); the
launcher now leads with ball shooter and AR cones and marks XR dragging as not running on the
Pixel yet; the touchpad reads upright while the browser holds the screen in landscape.

### First game played on the hardware: Ball shooter, 17 November 2026, 22:18

The owner, wearing the glasses, with the build of commit 82606d0 and the phone as the touchpad:

- tapped the Ball shooter card, moved the pointer onto the page's START XR with the touchpad
  and tapped: the page entered its immersive see-through session (its button read STOP XR when
  checked from the laptop; the picture sent to the glasses showed the game's balls on its floor
  grid, black elsewhere);
- a tap on the phone shoots a ball;
- walking two steps and back, the balls and floor stayed in place, "a bit shaky from walking
  but similar quality to Beam Pro";
- the picture was smooth;
- no graphics context loss in the log in more than two minutes of play.

His one complaint: the direction of the shot is hard to see, because the page draws its
pointing line thin and white like its grid lines. That line is the page's own.

2D, one picture for both eyes: no depth. Hands were not tried.

Also seen in that session, twice over: after the glasses were put down, the estimator wandered
0.5 to 1.5 m every five seconds and was restarted each time, with the position given to the
game held. That is the stillness rule doing on the real glasses what it did in the replays.

One thing went wrong first and was my doing: the touchpad did not appear, because Android
switches an app's accessibility service off when the app is force-stopped, and the crash tests
had force-stopped Ultra XR repeatedly. He switched it on again. `tools/pixel/lab.py` no longer
force-stops the app.

## Half-width 3D from a Windows laptop (18 November 2026)

`tools/ultra-diag/ultra_mode_test.py`, glasses plugged into the laptop alone. Windows had the Air 2 Ultra as an
active DisplayPort monitor at 1920x1080@60, mirroring the laptop screen.

- 0.0 s: "set display mode 8" accepted (status 0)
- 0.3 s: the glasses report mode 8
- 3.8 s: the glasses report mode 1 again, without being asked
- the owner saw black

So the glasses leave half-width 3D by themselves with a PC as the source too. The Pixel's signal is not what they
reject; this firmware does not keep mode 8. Not established: whether the stock (unpatched) firmware keeps it, and
whether the full-width mode (3840x1080), which the Pixel cannot send, is kept from a PC.

## 18 November 2026, 20:40: full-width 3D from the laptop works

`tools/ultra-diag/ultra_mode_test.py --full --yes`, glasses worn, plugged into the Windows laptop:

- 0.0 s: "set display mode 3" accepted (status 0)
- 0.26 s: the glasses report mode 3 (full-width 3D, 3840x1080@60)
- 29 s: still mode 3; no change back at any point
- the owner: "each eye had half the screen (wrong halves) but separate pictures on each lens"

So the glasses do show a different picture to each eye, and keep the mode, when the source sends 3840x1080.
Half-width 3D (mode 8) is the only stereo mode this firmware abandons. What stands between this and stereo on
the Pixel is the Pixel's DisplayPort driver alone, which drops 3840x1080@60 (297 MHz) on a link that has room
for it.

Not established: which eye gets which half ("wrong halves" may be the desktop simply split down the middle, or
the eyes swapped; a labelled test picture would say), and what Windows was sending during the test (the tool's
display listing showed only the laptop's own panel).
