# Full-resolution 3D on the Pixel 10: what blocks it, and an untested patch

**Nothing here has been installed, flashed or run on a phone.** This is a reading of the display
driver's source and of the phone's own kernel log, plus one experimental patch. The app does not
depend on any of it: it uses the half-width 3D mode, which needs no change to the phone.

## What the glasses ask for

In their full 3D mode the glasses' EDID offers one timing: 3840x1080 at 60 Hz, pixel clock
297 MHz (4400 x 1125 total). It needs 7.13 Gbit/s; the link the phone and glasses negotiate
(HBR, four lanes) carries 8.64 Gbit/s. Bandwidth is not the problem.

## Why the Pixel 10 drops it: two separate restrictions

Source read: `private/google-modules/display/vsi-drm/dwc_dptx` and
`private/devices/google/lga/dts`, branch `android-gs-laguna-6.6-android16`, from a public mirror
(github.com/azagramac/pixel10-kernel-aosp, snapshot of November 2025).

1. **A mode allowlist** (`dptx_bridge_mode_valid` in `dptx.c`). A mode is kept only if it fits the
   link, is exactly 60 Hz, and its width, height and clock match one of eleven entries
   (3840x2160 at 594 and 533.25 MHz, 2560x1600, 2560x1440, 1920x1200, 1920x1080, 1600x900,
   1280x720, 1024x768, 800x600, 640x480). 3840x1080 is not in it.
2. **A clock table** (`gpcm_hsion_pll_dp_clk` in `lga-cpm-clk.dtsi`). The driver runs the
   DisplayPort PLL at half the pixel clock, and its comment says that rate "must be present in CPM
   and DT". The rates offered are 12.5875, 20, 32.5, 37.125, 38.4, 54, 74.25, 96.625, 120.75,
   174.25, 266.625 and 297 MHz: exactly half of each allowlisted mode's clock. **148.5 MHz, half of
   297 MHz, is not offered.** CPM is separate firmware that programs the PLL; its own table is not
   in the kernel source.

This matches the reports of custom kernels that exposed more resolutions and then showed black:
removing the allowlist alone lets a mode through whose clock the PLL is never set to.

The video timing itself is not restricted: `dptx_dtd_fill_from_mode` takes it from the mode as
given, so 3840 x 1080 with 4400 x 1125 totals would be generated as asked. 3840 pixels of width
are already driven for 4K.

## Can the hardware generate 3840x1080@60? Probably, not proven

The driver sets the PLL to half the pixel clock and then both pixel-clock dividers to 2, so the
display logic receives a quarter of the pixel clock. The dividers accept 1, 2 and 4. For a
297 MHz pixel clock the logic needs 74.25 MHz, which can be reached without the missing PLL rate:

| | PLL_DP | divider | clock at the display logic |
|---|---|---|---|
| what the driver would do | 148.5 MHz (not offered) | 2 | 74.25 MHz |
| the patch | 297 MHz (offered, used for 4K) | 4 | 74.25 MHz |

Both the PLL rate and the divider value are ones the phone already supports. What cannot be
confirmed from source: whether anything uses the PLL's output before the dividers (if so the patch
is wrong and the picture stays black), and whether Android's display service on the phone (closed
source) accepts a 3840x1080 mode once the kernel offers it.

## How this relates to the phone's Android 17 kernel

The phone runs `6.6.127-android15-8-g91d825611104` (build CP3A.261005.005). Source for that exact
build was not found: the laguna repositories are not listed on android.googlesource.com under the
names in the manifest, and the phone does not let an unrooted shell read its driver module or
device tree. The running driver is newer than the source read here (it has extra parameters such
as `dp_allow_30hz_120hz`, `dp_max_rate`, `dp_max_lanes`). Its behaviour in the phone's kernel log
is nevertheless the same on every point that could be observed:

- `DROP: "3840x1080": 60 297000` and `DROP` for 1920x1080 at 90 and 120 Hz with an HBR four-lane link
- `PICK: "1920x1080": 60 148500`, then `gpcm_pll_dp_clk = 74250000`, `hsion_pix_clk0 = 2`, `dpu_pix_clk0 = 2`

So the patch is written against the Android 16 source and **must be re-made against the exact
source of whatever kernel is built**. It is checked only this far: it applies cleanly to that
snapshot (`git apply --check`). It has not been compiled (the Pixel kernel builds on Linux with
Bazel; this machine is Windows) and has never run.

## The patch

`0001-dwc_dptx-allow-3840x1080-60.patch` (apply in the `vsi-drm` repository): adds
`{3840, 1080, 297000}` to the allowlist and, for that clock only, runs the PLL at 297 MHz with
both dividers at 4. Every other mode is untouched.

## If it is ever tried: order of work and the way back

Trying it means unlocking the bootloader, which **erases the phone** and can stop wallet and
banking apps from working. Nothing below should be done on a phone whose data matters.

1. Back up the phone. Download the factory image for the exact build it runs and keep it on the
   computer: that image is the way back.
2. `fastboot flashing unlock` (erases everything). Note the build number first.
3. Build the kernel for `mustang` from the source matching that build, with the patch applied,
   on Linux. Only `dwc_dptx.ko` changes; it lives in `vendor_dlkm`.
4. Flash only the rebuilt `vendor_dlkm` (and `vbmeta` with verification disabled if the build
   needs it). Do not touch the bootloader, radio or `boot`.
5. Plug in the glasses in 2D first. If 2D no longer shows, stop and go to step 7.
6. Switch the glasses to full 3D and read the kernel log. Success is `PICK: "3840x1080"`,
   `ENABLE MODE: "3840x1080"`, `gpcm_pll_dp_clk = 297000000` and a picture. A black picture with
   those lines means the PLL assumption is wrong: go to step 7.
7. **Way back:** flash the stock `vendor_dlkm` and `vbmeta` from the factory image, or run the
   factory image's `flash-all` (erases the phone again). The phone's display, modem and bootloader
   are not involved in the change, so a wrong driver module costs external display output until
   the stock one is flashed back, not the phone. Re-locking the bootloader (`fastboot flashing
   lock`, erases again) is only safe with a completely stock image flashed.

A DisplayPort driver that fails to load does not stop the phone from starting; if a modified
`vendor_dlkm` does stop it, the phone falls back to its other slot after a few failed boots, or
is recovered from fastboot with the factory image.
