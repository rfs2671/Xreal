# Investigation notes (2026-11-15)

Sources were cloned and read directly. "Verified" below means verified by that project's
authors on their hardware, not by this project.

## XR Gate (vladoshub/xr-gate, MIT)

The only open project that drives the Air 2 Ultra's cameras end to end. Linux, plus Windows through WSL2.

- **Cameras:** a standard UVC device (`/dev/video0` on Linux), opened raw at 640x241, 60 fps.
  Each frame is 308480 bytes: one eye as 640x480 8-bit grey, shuffled in 128 chunks of 2400
  bytes, then a 1280-byte footer. Footer: LE64 timestamp at 0, LE16 pair counter at 18,
  eye flag at 59, LE64 timestamp (ns) at 62. Left and right eyes arrive as alternating frames.
  (`capture_service_cpp/src/vendor/xreal_camera_decoder.cpp`, `xreal_camera_metadata.cpp`)
- **IMU:** HID interface 2. Start command `aa c5 d1 21 42 04 00 19 01`. Reports start `01 02`,
  LE64 ns timestamp at 4, 24-bit gyro at 18/21/24 (2000 dps full scale), 24-bit accel at
  33/36/39 (16 g full scale). (`xreal_imu_codec.cpp`)
- **Display mode:** HID interface 0, 64-byte `0xFD` packets with CRC-32; command 0x07 reads
  and 0x08 sets the mode. 3 = side-by-side 60 Hz, 9 = side-by-side 90 Hz, 1 = 2D.
  (`tools/xreal_ultra/xreal_display_helper`)
- **Tracking:** Basalt VIO with a calibration for one specific unit, Mercury hand tracking
  via ONNX, a Monado driver and an OpenVR driver, gesture-to-controller mapping.
- **Not there:** any Android build. Its capture goes through V4L2 and hidapi, neither of
  which an unrooted Android app can use; this repo replaces that layer with Android USB host
  calls and usbdevfs.

DeskUnreal/xreal-vio-vr independently uses the same frame size, eye flag offset and
timestamp offsets, which is some corroboration of the layout.

## Pocket XR (pocket-xr/pocket-xr, GPL-3, v1.4.6)

- Lists the Air 2 Ultra (`3318:0426`) in its USB filter and has a pure-Kotlin Air IMU reader
  and display-mode sender, both marked untested on Air glasses ("No tester yet").
- Its docs state the Ultra's tracking cameras are reachable only through XREAL's software.
  XR Gate shows otherwise on Linux; whether Android allows it is what the diagnostic tests.
- Its user-space UVC reader handles bulk endpoints and MJPEG/YUY2/NV12/H.264/HEVC only,
  and refuses isochronous endpoints.
- "6DoF on Aura" is the Aura glasses' own anchor feature switched on over the network; the
  phone does no tracking.
- OpenXR: Monado at upstream commit `ee03722885e7` plus `monado-pocketxr.patch`: an in-process
  HMD driver fed poses over JNI, a compositor window on the glasses' display, 16 KB page
  support, a vblank-locked pacer. Tested only with PC-streamed games on a Galaxy Z Flip 7.
  Listed problems include thermal status 3 within two minutes and no stereo switching.
- No Pixel in its tested-phone table.

## godot-xreal (shiena/godot-xreal, Apache-2.0 OR MIT)

- Loads XREAL SDK 3.1.0's closed libraries (user-supplied, not in the repo) and calls them
  through a reverse-engineered ABI, with runtime patches at fixed offsets.
- 6DoF, hand tracking, planes and anchors are reported verified on the Air 2 Ultra, but only
  on a Beam Pro, and the libraries appear to depend on XREAL's Nebula service.
- Exposes no grey camera frames. Not a route to an open stack.

## OVRPort (Android-XR-Bridge/OVRPort, GPL-3) and openxr-dispatcher-open (MIT)

- OVRPort rewrites a Quest APK's manifest, patches engine plugins and swaps libraries. The
  swapped-in libraries are closed and downloaded at patch time; its config marks store assets
  as entitled.
- Its open VrApi-to-OpenXR adapter needs `XR_KHR_android_create_instance` and
  `XR_KHR_vulkan_enable`, is Vulkan and arm64 only, and binds only Touch controllers.
- openxr-dispatcher-open is an MIT replacement for the closed dispatcher, tested only on the
  Steam Frame. It is a useful checklist of what Quest titles expect from a runtime.
- Relevant only after native OpenXR games work, and only for legally obtained games.
