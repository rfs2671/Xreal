# The next physical session

Everything that can be checked without a person is checked by scripts (see "What is automatic").
This is the part that needs your eyes and the glasses. It is written for the build after the
first live runs of 17 November 2026, which showed what works on the Pixel and what does not
(`docs/PIXEL_RESULTS.md`).

What that build is, plainly:

- **One picture, no depth.** The glasses do not hold their 3D mode from this phone, and trying it
  left them black. It is no longer tried unless you switch it on in Settings.
- **The phone is the controller.** Its screen becomes a touchpad in a game, so nothing depends on
  hand tracking. Hands still work when they are tracked.
- **Position tracking** while worn is the same as in the live runs (it came back to within 3 cm,
  8 cm at worst, in the recording). What is new: put down or bumped, it can no longer run away
  (it reached 158 m in replays; now it holds and restarts).
- **Hands** have a more forgiving pinch rule and a steadier pointer. Untested on a real hand.

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

## The session (about three minutes)

**Before:** a lit room with things in it, two steps of clear floor, glasses plugged straight
into the phone.

1. **Start.** Tap **XR dragging**. Look straight ahead and hold still for about fifteen seconds
   while the page loads; Ultra XR presses the page's start button for you. Coloured shapes
   appear in your room. If they do not, point at **START XR** with the touchpad and tap.
2. **Move.** Take two steps to one side, then back.
   **Question 1: do the shapes stay where they are in the room while you move?**
3. **Grab.** Drag on the phone until the pointer line is on a shape. Hold volume-down (or put a
   second finger on the screen), drag to carry the shape, let go.
   **Question 2: did the shape come along and stay where you let go?**
4. **Hands, if you like.** Hold a hand up for ten seconds, pinch a few times at a shape.
   **Question 3: did a pinch pick a shape up?** (A "no" is useful: the phone records how close
   your fingertips came, which is what the pinch rule is tuned on.)
5. **Leave.** Press Back on the phone, unplug the glasses.

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
