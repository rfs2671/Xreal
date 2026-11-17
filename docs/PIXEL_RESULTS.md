# Pixel 10 Pro XL + Air 2 Ultra: measured results (2026-11-16)

Hardware: glasses `G428X00969` (DP `1140`, SOC `12.1.00.502_20250408`), Pixel 10 Pro XL
(Android 17, build CP3A.261005.005), Windows laptop (ASUS GA402RJ, AMD graphics).
Everything below was measured on this hardware on this date. Where something was not
measured, it says so.

## Firmware

- Stage 1 (official image) and Stage 2 (corrected image, mkcalc + nohdcp) were written with
  `tools/ultra-dp/ultra_dp_writer.py`. Both ended with FINISH status 0 and the same glasses id,
  DP `1140` and SOC version afterwards. The SOC was not touched. Restore was never run.
- The corrected image is running: the Pixel kernel logs `HDCP22 is not supported.
  rxcaps(0x000000)` and `HDCP13 is not supported` one second after video start. Before the
  write, Android flagged the same display `FLAG_SECURE` and the kernel log held an HDCP
  handshake for each connect.
- Windows still shows a picture with the corrected image (1920x1080, total 2200x1125,
  148.5 MHz, RGB 8 bpc).

## 2D on the Pixel: works

- Four consecutive physical plugs gave a picture in the glasses. Kernel log for a working
  connect: DPCD caps `12 0a 84` (DP 1.2, HBR, 4 lanes), link trained `rate=1 lanes=4`, no FEC,
  no DSC, mode `1920x1080 60 148500 1920 2008 2052 2200 1080 1084 1089 1125`, link BPC 8.
  That is the same timing Windows sends.
- On a working connect the glasses' controller sends `0x6C04` (display toggled) and logs
  `Open OLED 2D` when worn and `Close OLED` when taken off. On the pre-fix black screen it
  sent neither.
- The very first Pixel session after the write (19:26 to 19:36) stayed black although Android
  showed the display ON at 1080p60. No kernel log exists for that connect, so the cause is
  unknown.
- Android asks "Connect to external display" (Desktop / Mirror / Cancel) and for USB access
  again after the glasses re-enumerate. The display stays disabled until that is answered.

## Stereo on the Pixel: does not work in any glasses mode

| Glasses mode | EDID preferred mode | What the Pixel kernel did |
|---|---|---|
| 2D 60 (1) | 1920x1080@60, 148.5 MHz | PICK, picture |
| 2D 90 / 120 | 222.75 / 297 MHz (extra DTDs in the 2D EDID) | DROP |
| SBS 60 (3) | 3840x1080@60, 297 MHz, 4400x1125 total | DROP, falls back to 640x480 |
| half-SBS (8) | 1920x1080@60, 148.5 MHz | see below |

Half-SBS: in this mode the glasses' DPCD caps read `12 06 84` (maximum link rate RBR).
The Pixel tried RBR on 4 lanes, clock recovery failed four times, it reduced to 2 lanes,
trained `rate=0 lanes=2`, then dropped 1920x1080@60 and picked 640x480. Seen on two plugs.
On one earlier soft switch Android reported 1920x1080 ON in half-SBS and the panels were
still black; there is no kernel log for that one.

The kernel prints `DROP` without a reason. Why the 222.75 and 297 MHz modes are dropped on an
HBR x 4 link is not established here.

Side effects seen while testing: after a failed mode switch the phone holds the 640x480
fallback with the display OFF, and a further mode write does not always re-plug the link.
A physical replug with the glasses back in mode 1 recovers 2D.

A half-SBS renderer was built and tested in this session and then removed again, because the
mode cannot be shown on this phone.

### Why 3840x1080@60 is rejected: what is and is not known

- Known from the kernel log: on an HBR x 4-lane link (8.64 Gbit/s of payload, enough for
  360 MHz at 24 bit per pixel) the driver picks 148.5 MHz and drops 222.75 MHz and 297 MHz.
  So the limit is not the link's raw capacity at 8 bpc. On RBR x 2 lanes it drops 148.5 MHz
  and picks 25.175 MHz.
- Not known: the rule the driver applies. The Pixel 10 DisplayPort driver (`dwc_dptx`) was
  not found in the public Android kernel repositories searched on 2026-11-16
  (`kernel/google-modules/display` has only gs101-family branches), so the reason behind
  `DROP` could not be read from source.
- Consequence for workarounds: any 3840x1080 timing the glasses accept (their side-by-side
  EDID advertises 50 Hz as the lowest vertical rate) needs well over 148.5 MHz; a
  reduced-blanking 3840x1080@60 is still about 267 MHz. No timing between the picked and the
  dropped values has been tried, an ordinary app cannot add display modes, and the glasses'
  EDID cannot be changed without firmware. No legitimate app-side workaround is known.
- Untested ideas, listed only so they are not lost: a different phone-side driver build;
  a USB graphics (non-DisplayPort) adapter. Neither has any evidence behind it here.

## 6DoF

- With the display working, the hardware check passed position tracking once (5 s, 0.81 m).
- The estimator is reset repeatedly by the app with `speed 6.0 m/s`, and in one run after a
  reset it produced no state for the rest of the check. See the tracking notes for the
  investigation; nothing in this section is a fix.

## Tools added

- `tools/ultra-diag/ultra_listen.py`: listens to the glasses' controller on Windows
  (wear events, firmware text log). Can write the current display mode back once; cannot build
  any firmware-update message.
- `tools/ultra-diag/win_display_timing.ps1`: prints the video timing Windows sends (read-only).
- Kernel DisplayPort lines on the Pixel are only readable through `adb bugreport`
  (section `KERNEL LOG (dmesg)`, driver `dwc_dptx`); the ring buffer holds about five minutes.
