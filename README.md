# Pixel + XREAL Air 2 Ultra: open XR stack

Goal: a standalone Android APK that turns a Pixel 10 Pro XL and XREAL Air 2 Ultra into a
6DoF, hand-tracked, stereoscopic OpenXR device, without XREAL's SDK, Nebula or a Beam Pro.

**Where this is today:** milestone 0, a hardware diagnostic APK. It builds and its protocol
code passes unit tests. **Nothing in this repository has been run on a phone or on the
glasses yet.** Every hardware claim below is "expected from other projects' Linux results"
until a report from the real devices says otherwise.

## Milestone 0: the diagnostic APK

`dist/xreal-ultra-diag-0.1.0-debug.apk` (also built by the `build` GitHub Actions workflow).

It answers one question: can the Pixel read the Ultra's IMU and both SLAM cameras over USB
*while* driving the glasses' display in side-by-side 3D? If not, nothing else in the plan
is possible on this phone.

### Run it

1. Sideload the APK (allow installs from your browser or file manager).
2. Plug the glasses straight into the phone. If Android asks "mirror or desktop", either is fine.
3. Open **XREAL Ultra Diag**, press **START**, allow the camera permission and the USB prompt.
4. Look through the glasses. Each eye should see one corner marker (left red, right blue)
   and squares at different depths that move when you turn your head. Press **Yes, 3D** or **No**.
5. Leave it running for at least 2 minutes (10 or more for useful battery and heat numbers),
   moving your head and pointing the glasses at a textured scene.
6. Press **Save report** (writes a zip to Downloads) and send that zip back. **Share report**
   sends just the text.

If the glasses reconnect when the display mode changes and the streams stop, press **Stop**
then **START** again; the glasses stay in 3D mode.

### What it tests

| Step | What it does | Counts as a pass when |
|---|---|---|
| `usb.device`, `usb.open` | Finds `3318:0426`, gets permission, dumps every interface, endpoint and the raw descriptors | Android lets the app open the device |
| `mcu`, `mcu.set_sbs` | Reads the display mode from the glasses' controller (HID interface 0) and asks for 3840x1080 side-by-side | The glasses answer and accept the mode |
| `imu.open`, `imu.stream` | Starts the IMU (HID interface 2), decodes gyro and accelerometer | Over 50 samples/s arrive (about 1000 expected) |
| `camera.open`, `camera.frames` | Standard UVC negotiation for 640x241, then isochronous or bulk streaming, whichever the device has | Complete frames arrive for both eyes |
| `camera.decode` | Unshuffles frames to 640x480 grey and scores the result at the shuffle seams | Seam score near 1 on a textured scene; you also see both live images on the phone |
| `display.*` | Lists displays and modes, opens a stereo GL window on the glasses, measures frame rate | The surface is 3840x1080 and you confirm 3D by eye |
| `simultaneous` | Counts seconds in which IMU, camera and rendering were all live | 10 or more such seconds |

Every second it also logs IMU rate, camera rate and bitrate, render fps and late frames,
battery percent, current, voltage, battery temperature, thermal status and thermal headroom.
The zip contains the report, the first 8 raw camera frames and the first 4000 raw IMU
reports, so the decoders can be checked against real data offline.

### Build

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Needs JDK 17+, Android SDK platform 35, NDK 27.2.12479018 and CMake 3.22.1.

## What was reused, and what could not be

The four named projects were cloned and read. Details in [docs/INVESTIGATION.md](docs/INVESTIGATION.md).

| Project | What it really is | Used here |
|---|---|---|
| **XR Gate** (MIT) | Linux/Windows stack for exactly this headset: camera + IMU capture, Basalt 6DoF, Mercury hand tracking, Monado and SteamVR drivers, per-device calibration | The Ultra's camera unshuffle table, frame footer layout, IMU report layout and display-mode protocol are taken from it. Its Basalt and Mercury setup is the plan for 6DoF and hands. It has no Android build. |
| **Pocket XR** (GPL-3) | Android spatial desktop that also ships a patched Monado as an Android OpenXR runtime | Confirms the Android USB approach (pure user-space HID, CAMERA permission for USB video). Its Monado patch, a pose-fed HMD driver that presents to the glasses' display, is the planned OpenXR base. It has never been run on an Air 2 Ultra and cannot read its cameras. |
| **godot-xreal** (Apache/MIT) | Godot wrapper around XREAL's closed SDK 3.1.0 libraries, patched at fixed offsets, tested only on Beam Pro | Not usable: it is the vendor SDK this project is meant to replace, and it exposes no camera images. |
| **OVRPort** (GPL-3) + openxr-dispatcher-open (MIT) | Patches Quest APKs to run on other OpenXR headsets | Deferred to the Quest milestone. Its core libraries are closed and downloaded at patch time, and patched games expect Touch controllers. |

## Blockers and open questions

Hardware, answered only by a diagnostic report:

1. Does the Pixel put the display in 3840x1080, or only mirror at 1920x1080? Without the wide mode there is no full-resolution stereo.
2. Does Android let an app claim the Ultra's video interface, and is the stream isochronous or bulk? Both paths are implemented; neither is tested.
3. Does the camera stream without vendor-specific start commands? On Linux it does.
4. Can the USB 2.0 link carry about 148 Mbit/s of camera data next to the IMU without drops?
5. Do camera and IMU timestamps share a clock on this path? Visual-inertial tracking needs that.
6. How much battery and heat does capture plus rendering cost before any tracking runs?

Software, not started:

7. Basalt and its dependencies have to be built for Android arm64 and run in real time on Tensor G5.
8. XR Gate's calibration files are for one specific pair of glasses; each unit needs its own calibration (XR Gate has the tooling, on Linux).
9. Mercury hand tracking needs an ONNX runtime on Android, sharing the camera frames with Basalt.
10. Pocket XR's Monado patch is GPL-3 and feeds 3DoF poses; it needs a 6DoF and hand-joint feed, and has only been tested with PC streaming on a Samsung phone.
11. There are no controllers. Games that need them require a gesture-to-controller layer (XR Gate has one for SteamVR) or Bluetooth controllers.
12. Quest APKs: separate, experimental, after native OpenXR games work.

## Roadmap

1. **M0 diagnostic** (this): built, untested on hardware.
2. **M1 capture service:** turn the proven capture path into a background service with recorded datasets.
3. **M2 6DoF:** Basalt on Android against recorded data, then live; measure drift and latency.
4. **M3 OpenXR:** Monado Android runtime fed by M2, presenting side-by-side; run a native OpenXR sample.
5. **M4 hands:** Mercury on the same frames, exposed through `XR_EXT_hand_tracking`.
6. **M5 benchmarks:** accuracy, frame rate, motion-to-photon latency, battery and thermals on the real devices.
7. **M6 Quest compatibility:** experimental.

Each milestone is only marked done after it has been measured on the phone and glasses.

## Licence

MIT. Protocol facts and the camera chunk table come from XR Gate and ar-drivers-rs (both MIT).
This project is not affiliated with XREAL, Google or Meta.
