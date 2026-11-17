# The one acceptance session

Everything below has only run on an emulator with simulated tracking, or not at all. This session
is where it meets the Pixel and the glasses. About 25 minutes. Note the step numbers that fail and
what you saw; nothing else is needed.

Already on the phone: Ultra XR, the Ultra XR copy of the Wolvic browser, and the Khronos sample
"hello_xr". The glasses' firmware and the phone's system are untouched.

**Before starting:** phone charged and unlocked, glasses plugged straight into the phone, a lit room
with things to look at (tracking fails in the dark and on blank walls), about 2 m of clear floor.

## A. Display and 3D (5 minutes)

1. **2D still works.** Plug in the glasses. *Pass:* the phone's picture appears in the glasses as before.
2. **Open Ultra XR.** *Pass:* the home scene appears in the glasses.
3. **3D.** Tap **Spatial desktop**. Within half a minute the picture blanks once or more and the
   phone's status line ends in "3D" or in "2D (3D failed: ...)". *Note which, and the reason if 2D.*
   If 3D: close one eye, then the other. *Pass:* the clock shifts against the room between the eyes.
   If depth looks inside out, tick "Swap left and right eye" in More and say so.
4. **Never doubled.** At no point in this whole session should you see the picture as two copies
   side by side for longer than the few seconds of a switch. *If you do, note where.*

## B. The runtime with the sample app (3 minutes)

5. Back in Ultra XR's home, under **Your XR apps**, tap **hello_xr**. The banner says it is starting
   3D; then the app opens. *Pass:* small cubes float in the room around you and stay put as you
   turn your head. *Note* whether it started in 3D or said it was showing 2D.
6. Hold a hand up in front of the glasses. *Pass:* a cube follows each hand. (If none appears, note
   it and go on.)
7. Press the phone's Home button, return to Ultra XR. *Pass:* the home scene is back in the glasses.

## C. WebXR games in the browser (10 minutes)

8. In Ultra XR's home, under **WebXR games**, tap **XR dragging**. The browser opens on that page.
   *Pass:* in the glasses you see the browser's window with the three.js page.
9. Point a hand at the page's **START XR** button (a thin line shows where you point) and pinch
   thumb and index finger once, briefly. *Pass:* the window disappears and coloured shapes float
   in your room, which you still see around them.
10. Look around and take a step sideways. *Pass:* the shapes stay where they are in the room.
    *Note* any swimming, jumping, or drift.
11. Point at a shape, pinch and hold, move your hand, release. *Pass:* the shape comes with your
    hand and stays where you let go. Try both hands.
12. Go back (the phone's Back button) and open **Ball shooter** the same way; pinch to shoot.
    *Pass:* balls fly from your hand and bounce. Then **AR cones**: each pinch places a cone.
13. If a page shows no hands or does not react to pinches at all, note which, and whether the thin
    pointing lines were visible in the browser's own window before entering.

## D. Tracking data for later work (3 minutes)

14. In Ultra XR: **Settings**, **Record 60 seconds**. Then: look around slowly for 15 seconds, walk
    four steps forward, turn round, walk back, and stand exactly where you started until a minute
    has passed. This recording is what lets tracking be improved afterwards without the glasses.

## E. Recovery (4 minutes)

15. During a WebXR session, unplug the glasses, wait five seconds, plug them back in. *Note* what
    happens: whether the picture returns, and whether the game is still there.
16. Close everything from the phone's recents and unplug. *Pass:* next time the glasses are plugged
    in, the plain 2D picture works as it always did.

## What to report

Failed step numbers and what you saw, plus the two notes from steps 3 and 5 (3D or 2D). If the
phone is still on the same Wi-Fi with wireless debugging on afterwards, the logs can be read from
it directly.
