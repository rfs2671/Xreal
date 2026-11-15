# Meta Quest games: what is and is not supported

**Unmodified Quest APKs do not run, and this app does not try to make them.** A Quest game
loads Meta's own OpenXR loader and expects Meta's runtime, platform services and entitlement
check, none of which exist on a Pixel.

## What was investigated

OVRPort (GPL-3) converts a Quest APK for other OpenXR headsets. Reading its source:

- It rewrites the manifest, patches engine plugins, and swaps in replacement libraries.
- Those replacement libraries (the OpenXR dispatcher and vendor loaders) are closed binaries
  downloaded at patch time. They cannot be shipped or audited here.
- Its generated configuration marks a game's store assets as "entitled". That is a licence
  check being answered on the store's behalf, which this project will not do.
- Converted games expect Touch controllers.

`openxr-dispatcher-open` (MIT) is an open replacement for the closed dispatcher, tested by its
author on one headset only.

## What Ultra XR does provide

Everything on the runtime side that a legitimately converted game would need, with no
licensing involvement:

- A standard Android OpenXR runtime found through the standard Khronos broker interface. On a
  Pixel, OVRPort's dispatcher falls back to the generic Khronos loader, which asks exactly
  this interface.
- `XR_KHR_android_create_instance` and Vulkan/GLES graphics bindings (Monado).
- An Oculus Touch controller profile driven by hand gestures: pinch is the trigger, a fist is
  the grip, with grip and aim poses from the tracked hand. There are no thumbsticks or face
  buttons, so games that need them are not playable by hand alone.
- `XR_EXT_hand_tracking` joints.

So a game that you own, converted by you with such a tool, will find this runtime the same
way any Android OpenXR app does. Whether a given title then works is untested: no Quest title
has been run against this runtime, and none is claimed to work.

## What blocks more

1. Entitlement and platform calls (leaderboards, IAP, user identity) have no legitimate
   stand-in.
2. Many titles need thumbsticks and buttons. Bluetooth controller support is the natural next
   step; it is not implemented.
3. Quest-specific extensions (passthrough, scene, Meta-specific spaces) are not implemented by
   Monado on this device.
