# The interface: what it is to be

Asked for by the owner on 19 November 2026: a finished interface, usable without hands, windows that stay where they
are put, free resizing, one set-up and no questions afterwards, and a look of its own ("currently looks like sad AI
slop"). This file is the design. What is built and what was seen on the glasses is recorded at the end.

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
  focus passes its end. The focused mark is larger by a quarter. No grid, no dock of coloured circles.
- **Status line.** Time, the phone's battery, and one word for tracking ("room found", "finding the room",
  "no position"). Dusk. It is not focusable.
- **Windows.** Up to six. Each is a page (YouTube, Xbox cloud gaming, a web address, another Android app) on a
  rectangle in the room. Corner brackets only; the title appears above the top-left bracket when the window is in
  focus. A pinned window has a Tide pin by its title.
- **Handle.** Under the focused window: move, size, pin, close. Words, not icons. Close is Ember when focused.
- **Moving.** With "move" chosen the window follows the pointer, or steps with the d-pad (left and right round
  the wearer, up and down, and nearer or further with the shoulder keys or a two-finger slide). Select puts it down.
- **Sizing.** Free, not steps: with "size" chosen, left and right make it narrower and wider and up and down
  shorter and taller, 4 % a press, or a drag of a corner bracket with a pinch or the touchpad. Between 0.4 and 3.2 m
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
    question came up on the phone during this check. It is the owner's to answer, and it is one of the questions
    the set-up must bring down to once.
