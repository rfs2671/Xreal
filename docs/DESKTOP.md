# Spatial desktop

Floating web windows in the glasses, controlled from the phone. Open it from the launcher
("Spatial desktop").

**What it is not:** it is not stereoscopic. On the Pixel 10 Pro XL the glasses only accept a
1920x1080@60 picture shown to both eyes (see PIXEL_RESULTS.md), so windows are placed in space
around you and stay there as you turn your head, but there is no binocular depth.

## Using it

- **Open:** YouTube, Xbox Cloud, Browser, 3D web, or type an address or a search and press Go.
  Up to six windows.
- **Touchpad (phone):** slide to move the pointer in the focused window, tap to click,
  double-tap and hold to drag, two fingers to scroll, pinch to resize the window.
  The **volume keys** scroll too, so it can be done by feel.
- **Focus:** look at a window for half a second, or tap its chip on the phone.
- **Window buttons:** Back, Reload, Locked/Following, Min, Max, Full, Close. A minimized window
  keeps its chip; tap the chip to bring it back.
- **Move:** turn on Move, then slide on the touchpad to carry the focused window around you;
  two fingers up/down push it away or pull it closer. **Gather** brings everything back in front.
- **Follow head / Lock in space:** a locked window stays where it is in the room. A following
  window stays in front of you: it lets you look across it, and catches up once you have turned
  more than about 30 degrees.
- **Typing:** type in the box on the phone and press Type; it is entered into the page's focused
  field followed by Enter.
- **Hands:** point at a window and pinch to click; pinch a title bar and move to carry the
  window; pinch a title-bar button (follow, minimize, maximize, close).
- **More:** gaze focus, hand gestures, dimming, position tracking, recenter, close all.
- The layout (windows, sizes, places, pages) is saved and comes back next time.

## Tracking

Rotation only by default: windows hold their direction as the head turns. "Use position
tracking" in More is off by default because position was not reliable when measured
(native/vio/NOTES.md). Wherever position is used, it is only trusted while the estimator has
enough landmarks confirmed by both cameras; otherwise the app holds the last position and keeps
rotating from the IMU (`PositionConfidence`).

When the app leaves the foreground while the glasses are streaming, tracking moves into a
foreground service (a notification says so) and stops when the glasses are unplugged.

## What has been verified, and how

Automated, on an Android 14 emulator (`tools/ui-automation/desktop_test.py`, 32 checks) and by
unit tests (`DesktopTest`, `PositionConfidenceTest`):

- opening pages, clicking through the touchpad, scrolling, typing, video playback in a window
- three windows side by side, focus by chip, minimize/restore, maximize, fullscreen,
  follow/lock without a jump, move mode, gather, close, six-window limit
- the layout restored after the app is killed and reopened, pages reloaded
- an attached 1920x1080 display taking over from the phone preview, the picture on it, detach,
  re-attach, windows surviving all three
- 60 frames a second with two windows in the emulator's preview
- Xbox Cloud Gaming's catalogue and YouTube load in a window (not signed in, nothing streamed)

Not verified, because it needs the glasses, a head, hands or an account:

- what the windows look like in the glasses, their size and sharpness, readability of text
- head tracking of the windows, gaze focus, follow-head behaviour while actually turning
- hand pointing and pinching
- two-finger scroll and pinch-resize on a real touchscreen (the emulator test uses volume keys)
- game controllers reaching a page, Xbox Cloud sign-in and streaming, YouTube playback of real
  videos, audio routing
- the foreground service keeping tracking alive behind another app (it needs the glasses' USB
  permission to start), and behaviour after the glasses are unplugged mid-use
- battery use and heat

Known limits: Android's WebView has no WebXR, so immersive WebXR pages do not run; ordinary
WebGL pages do. Pages cannot open the phone's keyboard; use the Type box. DRM-protected video
may refuse to play or play at low quality.

## The acceptance test (about ten minutes, glasses plugged into the Pixel)

1. **Open.** Start Ultra XR, tap Spatial desktop. In the glasses you should see a dark panel
   with a clock. Tap YouTube, then Browser on the phone. *Pass:* two windows, the second to the
   right of the first, text readable.
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
6. **Hands.** Point at a window and pinch on a link; pinch a title bar and move your hand.
   *Pass:* the click lands where you point; the window follows your hand. (If hands are not
   picked up at all, note that and carry on.)
7. **Game.** Tap Xbox Cloud, sign in, start any game with a controller connected to the phone.
   *Pass:* the stream plays in the window and the controller works.
8. **Reconnect.** Unplug the glasses, wait five seconds, plug them back in, choose Mirror if
   Android asks. *Pass:* the same windows come back without reopening anything.
9. **Background.** Switch to another app for half a minute and come back.
   *Pass:* the windows are still there and still follow your head.
10. **Restart.** Close Ultra XR from recents and open the desktop again.
    *Pass:* the same windows return in the same places.

Report the step numbers that failed and what you saw.
