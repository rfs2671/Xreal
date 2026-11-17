# The five-minute physical test

Everything that can be checked without a person is checked by scripts (see "What is automatic"
below). This is the part that needs your eyes and hands. One game, five steps, about five
minutes. You answer three yes-or-no questions; the phone records the rest.

**Before:** phone unlocked and on Wi-Fi, glasses plugged straight into the phone, a lit room with
things in it (tracking fails in the dark and facing a blank wall), two steps of clear floor.

1. **Start.** Open Ultra XR and tap **XR dragging** under WebXR games. A banner says it is
   starting 3D; the picture blanks for a few seconds once or more; then the browser's window
   appears in the glasses.
2. **Enter.** Hold a hand up so a thin line shows where it points. Point at **START XR** and
   touch thumb and index finger together briefly. If a card says "press any button to continue",
   pinch once more. Coloured shapes now float in your room.
   **Question 1: close one eye, then the other. Do the shapes shift against the room?** (yes = real 3D)
3. **Move.** Take two steps to one side, then back.
   **Question 2: do the shapes stay where they are in the room while you move?**
4. **Grab.** Point at a shape, pinch and hold, move your hand, let go. Try the other hand.
   **Question 3: did a shape come with your hand and stay where you let go?**
5. **Recentre and leave.** Pinch with both hands and hold for two seconds: the scene jumps back in
   front of you. Press Back on the phone, then unplug the glasses.

That is all. Tell me the three answers (and anything that looked wrong). If the phone stays on
Wi-Fi with wireless debugging on, the details are read from it directly:

    python tools/pixel/pixel_check.py journal

which shows, for that session: whether the glasses got one view per eye or fell back to one
picture, how much of the time position was tracked, how far the head moved, how many frames each
hand was seen, how many pinches each hand made, and whether the recentre gesture fired.

## What is automatic

| Check | Where | Script |
|---|---|---|
| Both apps on the phone are exactly the built files (SHA-256), the phone's runtime broker hands out Ultra XR's runtime, screen timeout and stay-awake unchanged | Pixel, over adb | `tools/pixel/pixel_check.py install` or `verify` |
| WebXR sessions: AR and VR offered, two eye views, additive blending, 6DoF pose, hands with joints, selects (20 checks) | emulator | `tools/ui-automation/webxr_test.py` |
| Existing third-party pages start sessions and receive stereo, head motion and hands | emulator | `tools/ui-automation/webxr_pages.py` |
| A simulated hand picks up and moves an object in a third-party page | emulator | `tools/ui-automation/webxr_interact.py` |
| The OpenXR sample app renders one view per eye through the runtime (11 checks) | emulator | `tools/ui-automation/openxr_test.py` |
| Desktop, stereo geometry, 3D switch rules, recentre gesture, game library (unit tests) | this machine | `gradlew testDebugUnitTest` |

The emulator checks use simulated tracking. They show that the software path works, not that real
tracking is accurate; only the five steps above can show that.

## If something fails

- **Stays in 2D** (the banner says 3D could not be started): the game still runs, as one picture
  without depth. Note it; question 1 is then "no".
- **No pointing line from the hands in the browser window:** hand tracking is not reaching the
  browser. Note whether your hands were in view of the glasses' cameras and well lit.
- **Shapes swim or jump when you move:** position tracking is not good enough yet. Run
  **Settings, Record 60 seconds** once (look around, walk four steps and back): that recording is
  what tracking is tuned on afterwards.
