# The interface: what it is to be

Asked for by the owner on 19 November 2026: a finished interface, usable without hands, windows that stay where they
are put, free resizing, one set-up and no questions afterwards, and a look of its own ("currently looks like sad AI
slop"). This file is the design. What is built and what was seen on the glasses is recorded at the end.

## The look: glass (20 November 2026; this replaces the line look below)

The owner saw the line look ("light is the ink": marks in line, corner brackets, nothing filled) and rejected it:
"not anywhere near". He gave a picture of what he wants: a home of frosted glass panes with a sidebar, a large
"continue" card, cards with pictures, a room card, shortcuts and a dock. That is the direction now. Where this
section and the older ones below disagree, this one holds; the focus table, the keys, the sizes of text as angles,
the phone as a controller and the set-up are unchanged.

What the glasses can and cannot do with it: they add light and cannot darken the room, so a dark frosted pane and
a blur of the room behind it cannot be drawn. A pane here is a faint, even veil of cool light with a brighter rim
and light caught along its upper edge. In a dim room it looks like the picture; in a bright one the veil is
fainter and the cards carry it.

- **Colour.** The veil `#8C96C8` at about a third strength; text `#F4F5FA`, second lines `#B9BED0`; the accent,
  for what is chosen and what is in focus, violet `#9B8CFF` to `#6B57F5` (it was amber); green `#4ADE80` for
  running, connected, ready; Ember for what is wrong (`Glass` in `desktop/HomePanel.kt`).
- **The home panel** (`Home`): in view whenever no window is open. One picture, 42 by 19 degrees, 1.8 m away,
  drawn about a pixel to a pixel of the glasses.
  - Head: the name, a pill with the glasses (connected or not), the phone's battery and the tracking, the time.
  - Sidebar: Home, Games, Apps, Windows, Settings. The section shown is a violet pill.
  - Home section: a large card to continue with what was opened last (or to start with YouTube); quick play
    (three games and "More"); the room card with the marked room's own outline and size; the library (the web
    apps, then games); shortcuts (Recentre, Gather, Close all).
  - Games, Apps (the phone's apps with their own icons, nineteen to a page) and Windows (every window, open or
    put away; Select brings one back in front of the wearer) are grids of cards.
  - Nothing on it is made up: no placeholder titles, no devices that are not there. A thing without a picture
    of its own gets one drawn from its colour: a sign for an app, a low sun over a floor of lines for a game.
  - The focus is a rim of light with a glow that glides from card to card. The four directions lead to the card
    that lies that way (`Home.neighbour`); Left from a section leads to its entry in the sidebar; the sidebar is
    walked up and down within itself. A slide on the touchpad steps the same way; a ray or a look finds the card
    it is on (`Home.hit`). A green dot on a card says it has a window (hollow: put away).
- **The dock**: the row of marks on a glass pill, shown instead of the panel while a window is open (a new window is 0.80 m wide at 2 m, 23 degrees, so that title, window, handle and dock all fit under the status line). Its first
  mark is Home: it puts the windows away and shows the panel.
- **Windows**: a page with rounded corners and a thin rim (faint at rest, white with the focus on the window,
  violet with a soft halo when the focus is on the window itself); its title and the handle's four words stand on
  glass pills. Corner brackets are drawn only while a window is being sized. A window that was put away comes
  back in front of the wearer, not where it last stood.

## The home is "Aurora" (20 November 2026; this replaces the glass panel described below)

Of the three directions in `docs/design/` the owner chose the second. What is built of it (`desktop/HomePanel.kt`):

- **A wall of covers that curves round the wearer.** Each cover is a thing of its own in the room, not part of one
  flat picture: the one in the middle is a quarter larger and 14 cm nearer, its neighbours stand back (22 cm for
  the outer ones) and are turned inwards by about 29 and 34 degrees, so the wall is seen to curve and shifts in
  depth as the head moves (`Home.coverX`, `coverScale`, `coverTurn`, `coverDepth`, drawn as `RenderPiece`s). Five
  are in view. With five covers or more the wall goes round: after the last comes the first.
- **Movement.** Left and Right turn the wall to the next cover in a quarter of a second. Coming into view, the
  wall comes forward out of the room, the outer covers last. The cover in focus has a rim of light, violet round
  white, a glow of violet into cyan behind it that breathes slowly, and floats by a few millimetres. With "reduce
  motion" nothing moves by itself.
- **A ray or a look** takes the focus to a cover without turning the wall (it comes forward a little where it
  stands): if the wall turned to whatever was pointed at, it would run away under the pointer.
- **Three rows.** The sections above (Home, Games, Apps, Windows, Settings; the one shown has a line of light under
  it), the covers, the actions under them (the room's state, Recentre, Gather, Close all). Up and Down go between
  the rows, Left and Right along them (`Home.neighbour`).
- **Only real things.** The covers are the web apps and games there are, the phone's apps with their own icons,
  and the windows that are open. A thing with no picture of its own gets one drawn in its colour. The one used
  last is in the middle when the home opens and says "Resume".
- **Checked** in eye pictures and by keys on the S21 Ultra without the glasses. **Not seen** through the glasses.
  **Not yet in this look:** the dock under open windows, a window's handle, the settings window, the phone's own
  screens.

## Working first (20 November 2026, after the owner wore the glass home)

The owner's words: "hand tracking not working... not 6dof and no way to move windows or resize them... Fix everything
to be working functionality first. Then I want 3 visual example to see which direction to go with." What was wrong,
and what was changed:

- **Not 6DoF.** The desktop used the head's position only if a setting was switched on, and the setting was off. It
  is on now, and used whenever there is a head (the tracker holds its last place while it has no fresh one). Every
  window has its own place in the room (`DesktopWindow.ax`, `Desktop.anchorFor`, `reanchor`): opened, brought back
  or taken up, it is arranged around where the wearer is then, and stays there when they walk. The home and the
  dock keep their place while the wearer moves within 45 cm of it and glide along when they walk off. Places are not
  kept from one session to the next (a new session has a new world).
- **Hands.** The desktop pointed from the wrist to the knuckles (the ray the owner called "really bad" in games) and
  took a pinch by distance alone (1 of 11 of his pinches in his own recording). It now uses the games' arm ray
  (`core/ArmRay`, shared with `XrHost`) and measured pinch rule (`PinchState`) through `desktop/HandPointer`. And the
  wearer is shown the hand: a dot on the thumb's and the index finger's tip, a short fading ray, and a ring where it
  lands that fills while pinching. A hand seen is logged every five seconds ("desktop: a hand in ...").
- **Moving and sizing.** By hand: pinch a window's handle and carry it (it follows while the fingers hold); pinch a
  corner and drag. By phone: Move and Size stand beside the d-pad (they were behind "more"). The dock stands as far
  away as a new window (2 m; it was 1.6 m), and of a mark and a window on one ray the nearer is meant: before, a ray
  from a hand held low passed through the dock on its way to a window's handle.
- **Checked** on the S21 Ultra without the glasses, with a made-up hand (debug builds: `files/hand_sim.txt`, "yaw
  pitch gap"): pointing puts the ring on the window, a pinch on the handle carried the window 9 degrees and it
  stayed, a pinch on a corner dragged it from 0.80 to 1.07 m wide, a pinch on the dock's Home showed the home and one
  on its large card brought the window back; the phone's Move and Size with the d-pad. Unit tests: 201, none
  failing (`RoomPlaceTest`). **Not checked:** a real hand, and walking, in the glasses.
- **The look** is open again: three directions are drawn in `docs/design/` (`direction1.png` Clear glass,
  `direction2.png` Aurora, `direction3.png` Orbit; `directions.html` draws them, added to a dim room as the glasses
  add light). The owner chooses; nothing of them is built yet.

## The material

The glasses add light to the room and cannot darken it. Black is not a colour here: it is the room. A filled panel
is a bright blot that hides what is behind it and glares in a dim room. The picture an eye gets is 46 by 27 degrees.

## Principles

1. **Light is the ink.** Everything is drawn in line and text. Nothing is a filled panel, a card or a gradient wash.
   The only filled things are the pages inside windows, which are content.
2. **One thing glows.** Exactly one item is in focus at any moment: amber, a thicker line, a soft halo. Everything
   else is chalk white or dim. A press always acts on the thing that glows.
3. **Things stay where they are put.** A window is pinned to the room by default and shows a small teal pin. A
   window that follows the head shows none.
4. **No questions after the first day.** Set-up is one sequence, once. Nothing asks again unless something broke,
   and then it says what broke and what to do, in one line.
5. **Every action is reachable four ways:** a hand's ray and pinch, the phone's touchpad, a d-pad (on the phone, or
   any Bluetooth gamepad or keyboard), and looking (dwell), with the same focus and the same result.

## Colour (all on nothing)

| Name | Value | For |
|---|---|---|
| Chalk | `#F2F4F0` | text, resting lines |
| Lamp | `#FFB454` | the one thing in focus |
| Tide | `#6FD6D0` | pinned to the room; things that are set and steady |
| Ember | `#FF6B5E` | close, delete, something wrong |
| Dusk | `#8A949E` | labels of things not in focus, hints |
| Slate | `#5C6670` | idle lines far from focus |

The names `Ink.WHITE`, `Ink.AMBER`, `Ink.SLATE` and `Ink.CORAL` in `DesktopRenderer.kt` are Chalk, Lamp, Slate and
Ember; Tide and Dusk are added there. No other colours, except an app's own colour on its mark's ring.

## Type

One family, Atkinson Hyperlegible (SIL Open Font License), regular and bold, bundled in the app. It was drawn for
readers with low vision: open shapes that stay apart when blurred, which is what thin bright text over a real room
needs. Sizes in the glasses are angles, not pixels: body text 0.55 degrees of x-height at least; a window's title
the same; the name under the focused mark 0.7. Sentence case everywhere. No all-capitals labels.

## In the glasses

```
              12:40    82 %    room found                       one line, Dusk, top of the view

      ( )       ( )      (( ))       ( )       ( )             the shelf: marks in a row,
                         Browser                                8 degrees under the line of sight;
                                                                only the focused one has its name

   +--                              --+
            page of the window                                  a window has no frame: four corner
   +--                              --+                         brackets, Slate at rest, Lamp in focus
            move   size   pin   close                           the handle: only under the focused window
```

- **Shelf.** Home is a single row of marks on an arc 1.6 m away. At most seven are in view; the row slides when
  focus passes its end. The focused mark is larger by a quarter. No grid, no dock of coloured circles. What is
  used most comes first: YouTube, Xbox Cloud, Browser, 3D web, All apps, Settings, then the games, then System.
  Inside a mark's ring is a small drawing (a screen, a game pad, a globe, a cube, dots, sliders) or the name's
  initials, so that no two marks look alike; a game has a play mark at the foot of its ring. A Tide dot under a
  mark says that it has a window; the dot is hollow while that window is put away, and Select brings it back.
- **Status line.** Time, the phone's battery, and one word for tracking ("room found", "finding the room",
  "no position"). Dusk. It is not focusable.
- **Windows.** Up to six. Each is a page (YouTube, Xbox cloud gaming, a web address, another Android app) on a
  rectangle in the room. A new one opens straight ahead of where the wearer looks, or, when a window stands there,
  in the nearest free place beside it (28 degrees apart). The next time the desktop is opened they are where they
  were left. A window that is put away never counts against a new one: the one put away longest makes room. Corner brackets only; the title appears above the top-left bracket when the window is in
  focus. A pinned window has a Tide pin by its title.
- **Handle.** Under the focused window: move, size, pin, close. Words, not icons. Close is Ember when focused.
- **Moving.** With "move" chosen the window follows the pointer, or steps with the d-pad (left and right round
  the wearer, up and down, and nearer or further with the shoulder keys or a two-finger slide). Select puts it down.
- **Sizing.** Free, not steps: with "size" chosen, the lower right corner follows the keys: left and right make
  it narrower and wider, up and down shorter and taller, 4 % a press, or a drag of a corner bracket with a pinch or the touchpad. Between 0.4 and 3.2 m
  wide. "Min", "Max" and "Full" remain as quick sizes on the phone.
- **Opening and closing.** A window grows from its mark in 180 ms and shrinks back into it in 140 ms; the brackets
  draw themselves last. Focus moves in 90 ms. Nothing else moves by itself. With "reduce motion" on in Android,
  nothing moves at all.

## Focus and the d-pad

One focus, four places it can be: the shelf, a window, a window's handle, a page (keys go to the page itself).

| Where | Left / right | Up | Down | Select | Back |
|---|---|---|---|---|---|
| Shelf | previous / next mark | to the nearest window | nothing | open the app | nothing |
| Window | previous / next window by direction | nothing | to its handle | use the page: keys go to it | to the shelf |
| Handle | previous / next word | to the window | to the shelf | do it (move, size, pin, close) | to the window |
| Page | to the page (arrow keys) | to the page | to the page | Enter to the page | leave the page: focus the window |
| Moving / sizing | as above | as above | as above | put it down | put it back as it was |

Keys taken, from the phone's own d-pad or from a Bluetooth gamepad or keyboard: the four directions, Select (d-pad
centre, Enter, gamepad A), Back (Back, Escape, gamepad B), and the shoulder keys (L1, R1) for nearer and further.
While a page of a game (Xbox cloud) is in use, all gamepad keys go to the page; Back held for a second leaves it.

A hand's pinch, a touchpad tap and a dwell act as Select on whatever the pointer is over, and moving the pointer
moves the focus to what it is over. So the same thing always glows whichever way it was reached.

## On the phone

The phone is a controller that is used without looking at it.

```
+------------------------------+
| Ultra XR        room found   |   one line: state, in words
|                              |
|                              |
|          touchpad            |   upper two thirds: slide to point, tap to select,
|                              |   two fingers to scroll, double-tap and hold to drag
|                              |
+------------------------------+
|   back      ^       home     |   lower third: a d-pad with Select in its middle,
|         <   o   >            |   Back and Home either side; the pad's middle
|   keys      v       more     |   buzzes under the thumb
+------------------------------+
```

- **One press each, in a row above the d-pad (21 November 2026, after the owner wore it: "no way to close certain
  windows ... make them bigger or full screen"):** "close" closes the window in focus (or the one nearest to where
  the wearer looks), "full" makes it fill the view and again puts it back, "next" moves the focus to the next window,
  "here" brings every window, put away or left anywhere in the room, in front of the wearer. Each says in the status
  line what it did. A seventh window no longer refuses to open: the window furthest from where the wearer looks is
  closed to make room, and it is said which. Checked on the S21 Ultra in the phone's preview (here, full, full again,
  next, close twice); not in the glasses, and the seventh window not at all.
- "move" and "size" never do nothing: with no window in focus they take the one nearest to where the wearer looks,
  a window filling the view is put back first, and with no window open it is said. While one is going on its word is
  lit, the touchpad says what a slide and a tap do now, and the status line in the glasses names the window. A slide
  across the whole pad carries a window 40 degrees (it was 69, out of a view of 46). Checked in the phone's preview.
- "keys" opens Android's keyboard for the page in focus. "more" opens a short list: quick sizes, reload, address,
  pocket mode, settings.
- Line on a dark ground (the phone's screen is an ordinary one: it may be dark, not see-through). The same six
  colours and the same type. No pill-shaped buttons in rows.
- The main screen of the app (before the glasses are on) is the same shelf, upright, with one large action:
  "Open in the glasses".

## Settings

In the glasses, as a window like any other, opened from the shelf's last mark. One column of rows; a row is a name,
its value in Chalk, and one line of plain words in Dusk saying what it does. Left and right change a value. Rows:
eye distance, world scale, picture brightness, window distance, hand tracking on or off, pointer by looking on or
off, reduce motion, mark the room, forget the room, about. The same list is on the phone.

## Set-up, once

A sequence of at most five screens on the phone, each one thing to do and why, and a tick when it is done:
1. Switch on the accessibility service (the pointer), which Android asks the owner to do himself.
2. Plug in the glasses and choose "always open Ultra XR for this device" in Android's own question.
3. Eye distance.
4. Mark the room (optional; can be done later from settings).
5. Done: "Open in the glasses".
Afterwards the app never shows these again; if one of them is undone (the service switched off, say), the status
line says so in one line and the phone's screen offers the one step that puts it right. The app never answers
Android's questions for the owner.

## When something cannot be done

The status line gives its place, for five seconds, to one line in Ember that says what did not happen and what to
do ("Six windows are open: close one first"). Nothing else in the glasses interrupts, and nothing asks a question.

## What is built, and what was seen

(Kept up to date as the work goes.)

- 19 November 2026: the marks are drawn in line (`desktop/HomeIcon.kt`), seen on the phone's screen only.
- 19 November 2026, night: all of the above is built.
  - Behaviour: `desktop/Focus.kt` (the table as a pure model), free sizing, moving, pin and close from the handle,
    d-pad and gamepad keys, keys into a page in use.
  - In the glasses: `desktop/Shelf.kt`, `Type.kt`, `SettingsPage.kt`, and `DesktopRenderer`/`DesktopSession`
    redrawn: shelf, status line, corner brackets, title and pin, handle words, opening and closing motion,
    settings as a window.
  - On the phone: `ui/Widgets.kt`, the controller (`DesktopActivity`), the main screen, settings, and
    `SetupActivity`.
  - Unit tests: 192, none failing (focus table, shelf layout, settings list among them).
  - Seen on the phone (S21 Ultra, no glasses): the main screen, the controller with its d-pad, and the shelf with
    its status line in the phone's small preview, drawn at 120 pictures a second with no drawing error; the focus
    walks along the shelf on d-pad keys and Select starts a game.
  - **Not yet seen:** anything in the glasses; a window open with its brackets, title and handle; sizing and
    moving; the settings window; the set-up sequence; the motion.
  - Where it differs from the design above: a new window is 0.84 m wide at 2 m (24 degrees) so that status line,
    title, window, handle and shelf all fit in 27 degrees; the status line stands in the room above the shelf, not
    fixed to the head; the handle is about 16 degrees wide whatever the window's width; settings show four rows at
    a time; "mark the room" in the glasses only says that it is started from the phone; minimise and maximise are
    on the phone only; a "System" mark is kept beside Settings; other apps' own icons are not shown (a ring in
    the icon's colour and a letter); the status line does not yet report an undone set-up step.
  - Known to be broken by this: `tools/ui-automation/desktop_test.py` (it taps buttons that are now behind "more").
  - The app's own helper for other apps' windows asks Android for USB debugging ("Allow USB debugging?"); that
    question came up on the phone during this check, and the owner answered it.
- 20 November 2026: seen at full size, and put right.
  - How it is seen without the glasses: a debug build writes, on request, the picture one eye of the glasses would
    get (1920 x 1080, the glasses' own field of view) to a file (`DesktopRenderer.eyeCapture`,
    `tools/ui-automation/eye.sh`). It is the rendered picture, not what the optics make of it.
  - What the first pictures showed, and what was done: seven game marks all alike, ahead of YouTube and Xbox (the
    order and the drawings above); the lit bracket's halo a hard box and the row's end dot a square (the line
    shader worked in half precision: now full); a window not opening at all with nothing said, because six
    windows of earlier days were put away out of sight (the rules above, and the line in Ember); the first press
    of a d-pad after touching the phone lost, because Android gave it to one of the phone screen's buttons (the
    screen's views now take no key focus unless its list or its line to type on is open); every window put away
    at each start (now they are as they were left); the settings opening 28 degrees to the side (now straight
    ahead and nearer); the second window 40 degrees to the side, out of sight (now 28).
  - `tools/ui-automation/desktop_test.py` is written anew: it drives the desktop with d-pad keys on the phone and
    checks the focus and each window's place and size, and saves an eye's picture at each step.
  - The status line now says "pointer off: see the phone" when the accessibility service is switched off.
  - Later the same day the owner gave the glass direction (the section near the top). Built: `desktop/HomePanel.kt`
    (layout, drawing, the directions, the ray), the dock's pill, rounded pages with rim and halo, glass under
    titles, handle and status line, violet for amber. Unit tests: 197, none failing (`HomeTest`: every tile in
    the picture and none overlapping, every tile reached by the directions, a ray at a tile finds it).
    On the S21 Ultra `desktop_test.py` passes 45 of its 46 checks; the one that did not was the test's own search
    of the log finding itself (put right, not run again). Its eye pictures are what was looked at.
  - **Still not seen:** anything through the glasses themselves (sharpness at the edges, brightness in a lit
    room, whether 0.55 degrees of x-height reads); a hand's pinch on the handle and the corners; a real
    gamepad; the set-up sequence from a fresh install; whether Android or Samsung shows any message of its own
    when the glasses are plugged in (the USB default for the glasses is set on the S21 Ultra, so the app's own
    question should not come).
  - Known limits: the helper that puts other Android apps in windows is started over the phone's own debugging
    port, which a restart of the phone closes until it is opened again from the laptop; "mark the room" is still
    started from the phone.

## Height of windows and the dock (21 November 2026)

Worn: "bottom nav menu is too high overlaping with other screens", then "everything is still extremely high".
Windows were raised until their handle stood clear of the dock (up to 8 degrees: the middle 28 cm above the
eyes at 2 m, the top out of the picture). A window's middle is now at eye level when it is opened or brought
back ("here"), saved windows were lowered once, and the dock is not drawn while a window comes down over it
(it returns when the focus or the hand is on it, or when it is looked down at by 7 degrees). The factory
calibration puts the displays within a degree of the motion sensor's axes, so there is no tilt of the picture
to correct. Not yet confirmed by the wearer.

### The picture is turned down for the eye (21 November 2026, later)

With windows at eye level it was "still 20 inches too high". The whole picture is now turned down by the
wearer's correction, 12 degrees to begin with (20 inches at about 2 m), in the desktop as in applications:
`HeadTracker.sightPitchRad` / `predictSight`, the setting `picture_tilt`, `XrHost.setPictureTilt`. From adb,
while it is worn: `am start -n dev.ultraxr/.ui.LauncherActivity --ef picture_tilt 10`. The value is the
wearer's judgement, not a measurement; the note above that there is "no tilt of the picture to correct"
was wrong about what he sees.

## Floor buttons, and the picture not turned (22 November 2026)

With the picture turned 12 degrees down the owner said: "still too high. add a button that lets me lower the floor
(only the floor. everything else should stay at eyes height) and judge based on that". So:

- The picture is not turned any more (once, `tilt_eye_1122`); windows stand at eye height again.
- The game touchpad's two buttons are now "Hold: floor up" and "Hold: floor down". A finger held on one moves the
  application's floor 3 cm after half a second and 3 cm more every third of a second, with a tick each step; the
  amount is kept (`floor_trim`) and shown on the phone ("Floor moved N cm down by you").
- The old buttons turned the whole picture and needed two holds within 0.6 s of each other, which cannot be done:
  they never worked.
- A tool can set the amount outright: `am start -n dev.ultraxr/.ui.LauncherActivity --ef floor_trim 0.30`.

Not yet tried on the phone or worn. What the owner settles on is the measure of how far the cameras' floor is off.
