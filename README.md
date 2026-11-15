# Ultra XR

A standalone XR launcher, tracker and OpenXR runtime for a **Pixel phone and XREAL Air 2 Ultra
glasses**, connected by one USB-C cable. No Nebula, no XREAL SDK, no Beam Pro, no PC, no root.

It reads the glasses' two tracking cameras and motion sensor directly over USB, computes 6DoF
head tracking and hand tracking on the phone, renders side-by-side 3D on the glasses, and
hosts an OpenXR runtime so Android OpenXR games can use all of it.

> **Read this first.** This app has been built and tested automatically, including against
> simulated sensor streams and on an Android emulator. **It has never been run with real
> glasses.** The first thing it asks you to do is a one-minute hardware check, which tells you
> (and, if you share the report, the developers) exactly which parts work on your hardware.
> The table under [What is verified](#what-is-verified) is precise about this.

## Install

1. On the phone, download `ultra-xr-<version>.apk` from the newest
   [release](../../releases) and open it. Allow your browser or file app to install apps
   when Android asks.
2. Plug the glasses straight into the phone and open **Ultra XR**. Allow the camera
   permission (Android requires it for USB devices with cameras; the phone's own cameras are
   never used) and the USB prompt.
3. Tap **Run check** on the banner and follow the prompts in the glasses.

If you have the Khronos "OpenXR Runtime Broker" app installed, remove it first: Android lets
only one app answer the OpenXR runtime lookup, and Ultra XR does that itself.

Do not grant Ultra XR "Display over other apps". It never asks for it, and with it granted
OpenXR games would draw on the phone instead of the glasses.

## Using it

- **Home screen:** two status chips (glasses, tracking), the featured game, your installed
  OpenXR apps, and three buttons: Recenter, Check, Settings.
- **Lumen** (built in): orbs hang in your room. Touch one to burst it, pinch one to pick it up
  and throw it through a ring, step aside when a comet comes at you. With no hand in view,
  look at an orb to burst it. If position tracking is not available, everything stays within
  reach and the game still plays.
- **OpenXR apps:** any installed app that declares itself an OpenXR immersive app is listed
  and starts with one tap. Pinch is the trigger, a fist is the grip.
- **Recovery is automatic:** the app reconnects if the USB streams stall, restarts position
  tracking if it diverges while keeping the world where it was, and falls back to
  rotation-only tracking in between.
- With no glasses attached, Lumen runs as a preview on the phone screen.

## What is verified

| Area | Status | How |
|---|---|---|
| App builds, installs as a signed arm64 APK, 16 KB page aligned | **Verified in CI** | GitHub Actions build |
| Launcher, settings, check screen and game start and render | **Emulator-tested** | instrumented tests on an Android 14 emulator, no glasses |
| Native 6DoF and hand libraries load and run inside the app | **Emulator-tested** | same tests, on empty images |
| USB protocol code: display mode packets, IMU reports, camera unshuffle, UVC parsing | **Unit-tested** against the formats documented by XR Gate and ar-drivers-rs | 7 tests |
| Clock sync, stereo pairing, image orientation, sensor fusion, recovery, recentering, gestures | **Simulation-tested**, including a byte-exact simulated glasses stream | 13 tests |
| Game rules | **Unit-tested** with scripted head and hands | 7 tests |
| 6DoF estimator (Basalt) through this app's API | **Simulation-tested** on a rendered stereo + IMU sequence: see `native/vio/NOTES.md` | host test |
| Hand tracker (Mercury) through this app's API | **Host-tested**: no false detections on empty images; detects a photographed hand at 16-20 cm | host test |
| OpenXR runtime driver (head, controllers, hand joints, recentre) | **Host-tested** through Monado's own tracker code | 30 checks |
| Pixel 10 Pro XL opens the glasses over USB, claims the camera interface | **Unverified** | needs your hardware |
| Camera and IMU streams arrive on Android as they do on Linux | **Unverified** | needs your hardware |
| Pixel outputs 3840x1080 to the glasses | **Unverified** | needs your hardware |
| 6DoF accuracy, drift, latency on the phone | **Unverified** | needs your hardware |
| Hand tracking range and accuracy with the real cameras | **Unverified** | needs your hardware |
| An OpenXR game presenting on the glasses through the runtime | **Unverified** | needs your hardware |
| Battery life and heat | **Unverified** (measured and logged by the hardware check) | needs your hardware |
| Meta Quest games | **Not supported**, see [docs/QUEST.md](docs/QUEST.md) | |

Nothing in the "Unverified" rows is simulated or faked in the app: each either works on the
device or is reported as failing by the hardware check.

## Known limits you should expect

1. **Calibration is from another unit.** Camera and IMU calibration come from XR Gate's
   measurements of one specific pair of Air 2 Ultra glasses. Lenses vary between units, so
   position tracking and hand positions may be less accurate on yours until a per-unit
   calibration exists. The app reads your glasses' factory configuration and includes it in
   the check report, which is what is needed to add this.
2. **Field of view is a best estimate** (about 43 x 25 degrees per eye). If virtual objects
   swim against the room when you turn your head, adjust **World scale** in Settings.
3. **The phone screen stays on (dimmed) while an OpenXR game runs.** Android stops the game
   and the glasses' display if the phone locks.
4. **No controllers.** Hands give a trigger and a grip only. Games that need thumbsticks or
   buttons are not playable yet.
5. **OpenXR apps are told the phone's refresh rate**, and the runtime's frame pacing is not
   locked to the glasses' display, so expect an occasional dropped frame.
6. **Hand tracking needs about a third of a second** of continuous view before a hand appears.

## How it is built

| Part | Reused from | Notes |
|---|---|---|
| Camera unshuffle, frame footer, IMU and display-mode protocol, clock sync, image orientation, calibration | [XR Gate](https://github.com/vladoshub/xr-gate) (MIT) | ported from its Linux capture service to Android USB host |
| 6DoF | [Basalt](https://gitlab.com/VladyslavUsenko/basalt) (BSD-3) | same commit and configuration XR Gate uses |
| Hand tracking | [Monado](https://monado.freedesktop.org) Mercury (BSL-1.0) with XR Gate's patches and C API | models from Monado's hand-tracking-models |
| OpenXR runtime | Monado with [Pocket XR](https://github.com/pocket-xr/pocket-xr)'s Android driver patch (GPL-3) | plus a patch adding hand joints, aim pose and recentring |
| IMU command set | [ar-drivers-rs](https://github.com/badicsalex/ar-drivers-rs) (MIT) | |

New in this project: the Android USB layer (including isochronous video over usbdevfs), the
pose fusion and recovery logic, the launcher, the game, the hardware check, and the glue
between the pieces. See [native/README.md](native/README.md) for rebuilding the native
libraries and [docs/INVESTIGATION.md](docs/INVESTIGATION.md) for the survey of existing
projects.

## Build

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Needs JDK 17+, Android SDK platform 35, NDK 27.2.12479018 and CMake 3.22.1. The APK is
signed with the fixed key in `app/debug.keystore` so builds install over each other. The
`build` workflow runs the unit tests, an emulator smoke test, and publishes a release.

## If something fails

Open **Check**, run it, and tap **Share report** (text) or **Save to Downloads** (a zip that
also holds the first raw camera frames and IMU packets). The report lists each step with what
was measured, the USB descriptors, the display modes Android offered, and the app log.

## Licence

GPL-3.0-or-later (the embedded Pocket XR Monado driver is GPL-3). Third-party licence texts
are under `native/*/LICENSES`. Not affiliated with XREAL, Google, Meta, Khronos or Collabora.
