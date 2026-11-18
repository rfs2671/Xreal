Ultra XR for Pixel + XREAL Air 2 Ultra.

**Install:** download `ultra-xr-*.apk` on the phone, open it and allow the install. Plug the glasses in, open Ultra XR, and run the one-minute hardware check it offers.

**Status:** run on a Pixel 10 Pro XL with the glasses: a WebXR game (Ball shooter) was played with the phone as a touchpad, the scene staying in place while walking, as one flat picture for both eyes. The glasses do not hold their 3D mode from this phone, so there is no depth. The home view in the glasses and the steadier hand ray have been checked on the emulator and from a recording, not yet through the glasses. New on 18 November: the cameras measure where the floor is (and find table tops and walls) so a game's floor is the real one, games can place things on surfaces, and opening a game from the home view in the glasses no longer freezes; none of the three has been seen through the glasses yet. The owner's verdict on the room features the same day: not ready. The floor looks too high even when drawn below the real one, and table tops drift on a walk; `docs/SPATIAL_RESEARCH.md` has what was ruled out and what the next test decides. `docs/ACCEPTANCE.md` has the full table of what was seen where.

`hello_xr-*.apk` is the Khronos OpenXR sample app, included as a known-good third-party app to try the runtime with.

## Picture correction (18 November 2026)

- Everything an XR application draws can be turned up or down by the owner, one degree a hold on the touchpad
  ("Hold: picture up / picture down"), remembered. This replaces the floor up/down buttons: it moves the floor,
  table tops, walls, hands and every game together.
- The touchpad shows "Picture turned N degrees" and "Cameras: floor N m under the glasses" in white.
- An earlier floor offset of more than 30 cm is turned into 10 degrees once, and cleared.
- Not yet seen through the glasses.

## Leaving a game frees the glasses (18 November 2026)

- After "Hold to leave the game" the game kept its session and its window on the glasses, and Android refused
  every other window there: the home view and the next game opened on the phone's screen and the touchpad did
  nothing (seen by the owner at 15:11). Android does not let one app end another, and the runtime cannot move a
  running session to a new window. Leave now ends Ultra XR's own process half a second after the phone has gone
  home; Android starts it again at once for the touchpad service, and the next game starts a fresh session.
- Emulator: enter a game from its card, hold Leave, enter again: 3 of 3 checks both times, the touchpad service
  bound again after the restart. Not yet done on the phone.
- The home view and the launcher's glasses picture try again for a few seconds when their window is refused.

## Home view: the cursor reaches the title bar, the main menu comes first, arrow keys (18 November 2026)

- The touchpad's cursor could not leave a window's page, so the close, maximise and minimise buttons above it
  could only be pressed by looking at them. The cursor now moves up onto the title bar and a tap presses the
  button under it.
- The home view opens on its main menu (the icons: games, Room check, web apps). Windows from last time are put
  away, not closed; the phone lists them. A "Main menu" button on the phone, and a remote's Menu key, go back to it.
- A remote's arrows step across the main menu's icons and OK opens the chosen one; in a window they move the
  cursor and OK taps. A full keyboard's arrows and a game controller still reach the page.
- An icon opens by looking only while the head is tracked (the phone preview no longer opens the middle icon by
  itself), and not while it is chosen with the arrow keys.
- The dock under a window still holds the four web shortcuts only. Android apps are not shown in the glasses:
  the home view draws web pages and starts WebXR games; it does not host other apps' windows.
