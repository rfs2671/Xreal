# The next physical session

Everything that can be checked without a person is checked by scripts (see "What is automatic").
This is the part that needs your eyes and the glasses. It is written for the build after the
first live runs of 17 November 2026, which showed what works on the Pixel and what does not
(`docs/PIXEL_RESULTS.md`).

## Where version 1.0 stands (18 November 2026)

| Part | State | Seen where |
|---|---|---|
| A WebXR game played start to finish with the phone as the controller (Ball shooter) | works | Pixel and glasses, by the owner |
| The scene stays in place while walking | works, "a bit shaky from walking" | Pixel and glasses, by the owner |
| Position tracking comes back to within 3 cm (8 cm at worst) when worn | measured, repeatable | Pixel, replay of a recording |
| Put down or bumped, tracking holds and restarts instead of running away | measured, and seen once live | Pixel |
| A pinch selects | works; aiming was "off" | Pixel and glasses, by the owner |
| Aiming after the fix that gives the browser hand joints only | not yet seen | - |
| A steadier hand ray (turn filter, below) | measured on a recording only | Pixel, replay |
| Home view in the glasses, round icons, look and pinch / tap / dwell | drawn and operated | emulator only |
| Phone screen in the same style | drawn; installed on the Pixel | emulator screenshots |
| AR cones, Hand cubes, Hand tracking showcase, Moon Rider | start and run immersive | Pixel, without the owner looking |
| XR dragging | does not run: the browser's GPU process loses its context on this page | Pixel |
| A game opened from the home view in the glasses | works (froze in the morning of 18 November; fixed, and the next try owned the glasses) | Pixel and glasses, by the owner |
| The real floor under a game (cameras measure it; was the height setting) | found at first; went wrong after position was lost while walking ("floor higher than the table"); changed, not yet seen again | Pixel and glasses, by the owner |
| Table tops and walls | "really bad" when moving: outlines everywhere, walls made up; made local, slower to believe and quicker to forget; not yet seen again | Pixel and glasses, by the owner |
| Hit test for pages written for phones' AR | works with a made-up room | emulator only |
| The touchpad on the phone | covered the phone after leaving a game, with no way past it; it now leaves when another app is in front, and has hold-to-press buttons: leave, floor up, floor down | buttons: emulator only |
| The floor's height, second look | "too high still"; the owner can now move it 3 cm a press from the touchpad | not yet seen |
| One picture per eye (depth) | blocked: the glasses leave their 3D mode about two seconds after this phone starts sending a picture, and show black | Pixel and glasses |

What stands between this and calling it 1.0 is in the next session below: the home view and the
hand ray have to be seen through the glasses once. Depth is not part of 1.0 on this phone.

What the hands recording showed about aiming (`tools/tracking/hand_steady.py`): a hand held
still is reported turned 1.2 to 4.5 degrees about its average, which moves the far end of a two
metre ray by 4 to 15 cm; the wobble of the hand's place adds under 1 cm. The build therefore
smooths which way the hand is turned (`core/HandTurnSteady`), which cut that wobble in all four
measured hands (by 8 to 37 %), at the cost of trailing a fast turn by 3 to 4 degrees. The same
recording replayed three times found the right hand in 78, 105 and 358 results: the tracker
loses and refinds a hand differently on each run, so this recording cannot rank finer settings,
and the hand is in view of the tracker far less of the time than it looks.

## Once, before the first game (about two minutes)

1. **Switch the touchpad on.** Open Ultra XR. In the card "Phone as a touchpad" tap
   **Switch it on in Android settings**, choose **Ultra XR touchpad** and allow it. Android asks
   because the touchpad listens to the screen and the volume keys while a game runs; it reads
   nothing else.
2. **Accept the browser's terms.** Plug the glasses in, tap **XR dragging**. The browser's terms
   of service appear in the glasses. Drag a finger on the phone to move the pointer onto
   **AGREE**, tap the phone once. This is yours to accept; the app never does it for you.
3. **Let games start by themselves from now on.** Press Back, open Ultra XR's **Settings**, and
   switch on **Enter games automatically**.

## First, one minute for the 3D question

This part decides whether real stereo is possible on this phone at all, and it needs you to do
nothing but wear the glasses. Plug them into the phone, put them on, phone unlocked on Wi-Fi,
and say so. From the laptop:

    python tools/pixel/lab.py diag3d C:/uvio/diag3d

asks the glasses for their 3D mode once, watches what they report five times a second, asks once
more after their screen has come on, then returns them to 2D, and afterwards reads the phone's
own DisplayPort driver log for those seconds. The picture will blank and may stay black at the
end; if it does, unplug the glasses and plug them in again. **Question 0: at any point during
that minute, did you see a picture that was different in each eye?**

## The session (about four minutes): what decides the floor

The floor looked too high even drawn below the real one, and nothing measurable from a recording explains it
(`SPATIAL_RESEARCH.md`). Each step below has one answer, and each answer points at one cause.

**Before:** a lit room, two steps of clear floor, glasses plugged straight into the phone. Open Ultra XR, open the
home view in the glasses, look at **Room check** and tap the phone.

1. **Stand straight and look down until the grid is green. Read the phone:** "Cameras: floor N m under the glasses".
   **Answer 1: the number.** (Your eyes are about 1.74 m up. If it says about that, the measuring is right and the
   showing is wrong; if it says 1.4, the measuring is wrong.)
2. **Close one eye. Is the grid on the floor now?**
   **Answer 2: yes / still too high / too low.** (Both eyes get the same picture, which sits 3.9 m away for the
   eyes whatever is drawn. If one eye fixes it, the cause is the missing depth, not the floor.)
3. **Still one eye closed: walk two steps forward and back, watching one grid line against a mark on the floor.**
   **Answer 3: the line stays on the mark / slides with me / slides against me.** (A line glued to the floor is at
   floor height. One that moves along with you is above the floor; this does not depend on judging depth.)
4. **Hold a hand up at arm's length, one eye closed. A red ring is drawn on the index fingertip and the thumb tip.**
   **Answer 4: on the fingertips / off by about N finger widths, and which way.** (This is the display's own
   error, with the floor and walking out of it.)
5. **Hold "floor down" or "floor up" until the grid is where the floor is (it now goes 1.2 m either way).**
   **Answer 5: the "moved N cm" the phone shows.**

Then leave with **Hold to leave the game**.

Volume-up recentres the view and the pointer at any time.

Tell me the three answers. With the phone on Wi-Fi and wireless debugging on, the rest is read
from it:

    python tools/pixel/pixel_check.py journal

which shows for that session: one picture or one per eye, how much of the time position was
tracked, how far the head moved, how many frames each hand was seen, the smallest thumb-to-index
distance of each hand, pinches, touchpad selections, and recentres.

## What is automatic

| Check | Where | Script |
|---|---|---|
| Both apps on the phone are exactly the built files, the runtime broker hands out Ultra XR's runtime, screen timeout and stay-awake unchanged | Pixel, over adb | `tools/pixel/pixel_check.py install` or `verify` |
| The touchpad alone enters a game through the page's own start button, selects, and carries an object | emulator | `tools/ui-automation/pointer_test.py` |
| One tap on a game card ends in the game's session without pointing at anything | emulator | `tools/ui-automation/enter_test.py` |
| Frame pacing: when the compositor's frames really reached the display, for both pacing modes | emulator, and the Pixel with `-s` | `tools/ui-automation/pacing_test.py` |
| WebXR sessions: AR and VR offered, two eye views, additive blending, 6DoF pose, hands with joints, selects (20 checks) | emulator | `tools/ui-automation/webxr_test.py` |
| Third-party pages start sessions and receive head motion and hands | emulator | `tools/ui-automation/webxr_pages.py` |
| A simulated hand picks up and moves an object in a third-party page | emulator | `tools/ui-automation/webxr_interact.py` |
| The OpenXR sample app renders through the runtime (11 checks) | emulator | `tools/ui-automation/openxr_test.py` |
| What a tracking recording contains, and whether the calibration agrees with the two cameras | this machine | `tools/tracking/analyse_recording.py`, `stereo_check.py` |
| Desktop, stereo geometry, 3D switch rules, recentre gesture, touchpad pointer rules, game library (unit tests) | this machine | `gradlew testDebugUnitTest` |

The emulator checks use a simulated head and no real hands. They show that the software path
works, not how it feels in the glasses.

## Not possible to check without you

- The volume keys. Android keeps key presses made by a test away from accessibility services, so
  only the real button shows that part. If volume-down does nothing, a second finger on the
  screen does the same.
- Whether the automatic start finds the page's button on the phone. It was found on the emulator
  at one place; the app tries three places, three times. Pointing and tapping always works.
- Anything about how steady, sharp or comfortable the picture is.
