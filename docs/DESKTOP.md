# Spatial desktop

Floating web windows in the glasses, controlled from the phone. Open it from the launcher
("Spatial desktop").

## 3D depth

The desktop asks the glasses for their half-width 3D mode: the phone still sends a 1920x1080
picture, the glasses show its left half to the left eye and its right half to the right eye, and
the app draws the scene once for each eye. Windows then have real depth. The cost is sharpness:
each eye gets 960 pixels across instead of 1920, so small text is softer than in 2D.

The glasses' full-resolution 3D mode (3840x1080) is not available on the Pixel 10 Pro XL: its
display driver drops that mode (see PIXEL_RESULTS.md and tools/kernel-experiments/README.md).

The link to the glasses does not always come up in half-width 3D (measured on this phone: two of
four attempts fell back to 640x480). So the switch is bounded:

- it starts only from a 2D picture that is showing, and only while the desktop is open
- at most three attempts; after each failure the glasses go back to 2D first
- after the third failure they stay in 2D, and the status line says why; nothing is retried until
  you untick and tick "3D depth" in More, or unplug the glasses and plug them in again
- leaving the desktop, or closing the app, returns the glasses to 2D

Each attempt blanks the picture for a few seconds. Turn "3D depth" off in More to stay in 2D.
"Swap left and right eye" is there in case depth looks inside out. Eye distance is the one set in
the app's Settings (default 64 mm).

How the two views are made: each eye is half the eye distance to the side of the head's centre,
with its full field of view rendered into its half of the picture (the glasses stretch it back).
The glasses' optics focus at about 4 m, so the views are converged there: a window 2 m away is
seen 19 pixels apart between the halves at 64 mm (`Stereo` in DesktopModel.kt).

## How it is designed, and how it is used

The design is in [INTERFACE.md](INTERFACE.md) (20 November 2026); this replaces the rail, the dock and the
title-bar buttons described here before. In short:

- **Light on nothing.** Black is the room. Nothing is a filled panel except a window's page.
- **The shelf:** one row of marks 8 degrees under the line of sight: YouTube, Xbox Cloud, Browser, 3D web, All
  apps, Settings, then the games. A Tide dot under a mark says it has a window (hollow: put away).
- **Windows** (up to six) have a bracket at each corner, a title when in focus, and a handle of four words under
  them: move, size, pin, close. A new one opens straight ahead, or in the nearest free place beside what stands
  there. They are where they were left the next time.
- **One focus**, lit in amber, moved the same way by a hand's ray, the phone's touchpad, a look, or a d-pad (the
  phone's, a Bluetooth gamepad's, a keyboard's arrows): see the table in INTERFACE.md. Select opens or does; Back
  goes page -> window -> shelf.
- **Sizing:** on "size", the lower right corner follows the keys (right wider, down taller, 4 % a press); Select
  keeps it, Back puts it back. **Moving:** on "move", the directions carry the window round the wearer and up
  and down, the shoulder keys nearer and further.
- **The phone** is a touchpad over a d-pad, with back, home, keys and more. "home" puts every window away;
  "more" lists the windows, quick sizes, reload, the address line, pocket mode and settings.
- **Pocket mode:** the screen goes black and is one touchpad that works by feel. The phone must stay on: it is
  the computer.
- **Settings** are a window like any other, opened from the shelf, straight ahead and nearer than the others.
- When something asked for cannot be done, the status line says why for five seconds, in Ember ("Six windows are
  open: close one first"). Nothing else interrupts.

## Tracking

Rotation only by default: windows hold their direction as the head turns. "Use position
tracking" in More is off by default because position was not reliable when measured
(native/vio/NOTES.md). Wherever position is used, it is only trusted while the estimator has
enough landmarks confirmed by both cameras; otherwise the app holds the last position and keeps
rotating from the IMU (`PositionConfidence`).

When the app leaves the foreground while the glasses are streaming, tracking moves into a
foreground service (a notification says so) and stops when the glasses are unplugged.

## What has been verified, and how

**20 November 2026:** the paragraphs below describe the interface before its redesign; their checks of the rail,
the dock and the title-bar buttons no longer apply. What holds now is in INTERFACE.md, "What is built, and what
was seen": `tools/ui-automation/desktop_test.py` drives the new interface with d-pad keys on the S21 Ultra and
saves the picture one eye would get at each step.

Automated, on an Android 14 emulator (`tools/ui-automation/desktop_test.py`, 39 checks, and
`stereo_test.py`, 10 checks) and by unit tests (83, among them `DesktopTest`, `RailTest`,
`PositionConfidenceTest`, `StereoTest`):

- opening pages, clicking through the touchpad, scrolling, typing, video playback in a window
- three windows side by side, focus by chip, minimize/restore, maximize, fullscreen,
  follow/lock without a jump, move mode, gather, close, six-window limit
- the layout restored after the app is killed and reopened, pages reloaded
- an attached 1920x1080 display taking over from the phone preview, the picture on it, detach,
  re-attach, windows surviving all three
- 60 frames a second with two windows in the emulator's preview
- pocket mode (black screen, tap clicks, Back twice leaves), keys typed into a window,
  a white page shown dark on the attached display, the rail and dock geometry (unit tests)
- Xbox Cloud Gaming's catalogue and YouTube load in a window (not signed in, nothing streamed)
- the two-eye picture, drawn on the emulator's stand-in display with a test switch: both halves
  drawn, each a whole view centred in its half and squeezed to half width, the clock, a window and
  a fullscreen window offset between the halves by 19 pixels (19.3 predicted), 60 frames a second
- the rules of the 3D switch (unit tests): nothing sent until a 2D picture shows, success only
  after the link restarted, three attempts at most, back to 2D on every failure, no retry after
  giving up, back to 2D when the desktop is left

Not verified, because it needs the glasses, a head, hands or an account:

- **3D on the glasses at all with this build.** The saved kernel logs show the half-width mode
  displayed a 1920x1080 picture twice on this phone, and that the picture was split per eye was
  seen once by eye; the new switch and the two-eye rendering have never run on the glasses.
  Unknown: how often the link comes up, whether the left half really is the left eye, whether
  depth is comfortable, how readable text is at half width
- what the windows look like in the glasses, their size and sharpness, readability of text
- head tracking of the windows, gaze focus, follow-head behaviour while actually turning
- hand pointing and pinching; look-to-press with a real head (its timing is unit-tested)
- pocket-mode gestures with more than one finger, haptics, and whether it works in a pocket
- a real Bluetooth keyboard and mouse
- two-finger scroll and pinch-resize on a real touchscreen (the emulator test uses volume keys)
- game controllers reaching a page, Xbox Cloud sign-in and streaming, YouTube playback of real
  videos, audio routing
- the foreground service keeping tracking alive behind another app (it needs the glasses' USB
  permission to start), and behaviour after the glasses are unplugged mid-use
- battery use and heat

Known limits: Android's WebView has no WebXR, so immersive WebXR pages do not run; ordinary
WebGL pages do. Pages cannot open the phone's keyboard; use the Type box. DRM-protected video
may refuse to play or play at low quality.

## The acceptance test (about fifteen minutes, glasses plugged into the Pixel)

1. **Open.** Start Ultra XR, tap Spatial desktop. In the glasses you should see a clock and a
   thin line below it, nothing else. Within about half a minute the picture blanks once or more
   and the phone's status line ends in "3D" or in "2D (3D failed: ...)". Tap YouTube, then Browser
   on the phone. *Pass:* two windows, the second to the right of the first, text readable; and
   either real depth (close one eye, then the other: the windows shift against the room) or a
   steady 2D picture with the reason shown. *Note which.* If depth looks inside out or
   uncomfortable, tick "Swap left and right eye" in More and say so. If the picture is double or
   stretched and stays that way, untick "3D depth" and report it.
2. **Head.** Turn your head left and right. *Pass:* the windows stay where they are in the room
   and do not swim or jump. Look at each for a moment: its outline lights up.
3. **Touchpad.** With YouTube focused, slide to move the pointer, tap a video. Scroll with two
   fingers and with the volume keys. *Pass:* the pointer follows, the video plays with sound,
   the page scrolls both ways.
4. **Windows.** Press Max, then Max again; Full, then Full again; Min, then tap the chip.
   Turn on Move and slide: the window should travel around you. Pinch on the pad to resize.
   *Pass:* each does what it says and nothing disappears for good.
5. **Follow.** Press Locked so it reads Following, then turn your body 90 degrees.
   *Pass:* that window comes with you after a moment; the locked one stays behind.
6. **No phone.** Tip your head down to the dock and rest your gaze on Browser until the ring
   closes. Then press Pocket mode, put the phone face-down, and use it by feel: slide, tap,
   two-finger scroll, two-finger tap, three-finger tap. *Pass:* the app opens from your gaze
   alone; each pocket gesture does its job with a tick you can feel; three-finger long press
   leaves.
7. **Hands.** Point at a window and pinch on a link; pinch a title bar and move your hand.
   *Pass:* the click lands where you point; the window follows your hand. (If hands are not
   picked up at all, note that and carry on.)
8. **Game.** Tap Xbox Cloud, sign in, start any game with a controller connected to the phone.
   *Pass:* the stream plays in the window and the controller works.
9. **Reconnect.** Unplug the glasses, wait five seconds, plug them back in, choose Mirror if
   Android asks. *Pass:* the same windows come back without reopening anything.
10. **Background.** Switch to another app for half a minute and come back.
   *Pass:* the windows are still there and still follow your head.
11. **Restart.** Close Ultra XR from recents and open the desktop again.
    *Pass:* the same windows return in the same places.

Report the step numbers that failed and what you saw.

## Other apps in windows (18 November 2026)

A window can hold another installed app instead of a page. It is a virtual display of this app with nothing of ours
on it; a helper process (`dev.ultraxr.shell.ShellMain`) running as the phone's "shell" user starts the app's activity
on that display and passes it touches, keys and typed text. Android allows only that user both. The helper listens
on 127.0.0.1 and obeys only a caller with the token in the app's own folder.

How the helper gets started:

- from a computer: `adb push tools/pixel/uxr_helper.sh /data/local/tmp/ && adb shell sh /data/local/tmp/uxr_helper.sh`;
- by the app itself (`AdbStarter`), when the owner has debugging over the network on (`adb tcpip 5555`, which lasts
  until the phone restarts): it speaks adb to 127.0.0.1:5555. The first time Android asks on the screen whether this
  app's key may debug the phone. The app changes no setting to get there.

After an update the app ends a helper that runs from the old install and starts the new one.

Seen on a Galaxy S21 Ultra (Android 13), on the phone's preview and on the glasses' display: the YouTube app in a
window; Settings in a window, a tap opening one of its pages (even one from another package), Back returning;
the window turned upright (720 x 1280) with the app laid out as on a phone. Not tried: typed text, video that
insists on a protected screen, a game controller. Not built: a window of any other size than those two.

In the glasses: the home view has "All apps" (every app with a launcher icon, twelve to a page) and "System"
(hands on or off, recentre, gather windows, close all); the dock under the windows has "Menu", back to the home view.
