# Request to XREAL: Air 2 Ultra shows no picture from a Pixel 10 Pro XL over DisplayPort

For XREAL support / firmware engineering. Everything here was obtained without writing to or
flashing the glasses. Hardware facts come from the owner's device; firmware facts come from
files XREAL publishes through its own update service.

## Symptom (hardware-observed, Pixel 10 Pro XL, Air 2 Ultra, direct USB-C)
- Android lists the glasses as an external display at 1920x1080@60. EDID is read, frames are
  presented, link is up. Both panels stay black in every mode (2D and side-by-side, mirror and
  extended). Android never offers 3840x1080.
- The control channel works: HID interface 0 (VID 0x3318, PID 0x0426) answers; the IMU and both
  tracking cameras stream; the controller accepts mode commands.
- DP bridge firmware reports `1140`; SOC `12.1.00.502_20250408`.
- Changing mode does not cause a hot-plug, unlike on working hosts.

## Technical hypothesis (documented publicly for the Air 2 Pro, same bridge code in the Ultra)
Source: github.com/mightatnight/xreal-pixel-patches (`docs/root-cause.md`).
1. With this host's stream the bridge's pixel-clock measurement returns 0. The firmware does not
   check it, programs the PLL with 0, re-measures 0 and never recovers; the MCU sees no video
   and keeps the OLEDs off.
2. The Pixel starts HDCP about 1 s after video starts. With HDCP advertised the bridge stalls
   earlier (link state 8) and never reaches the clock-recovery stage. Requesting HDCP within
   ~4 s of video start keeps the glasses dark even after HDCP is turned off.
3. The public workaround (a pixel-clock fallback and disabling the HDCP advertisement) produced
   a picture on an Air 2 Pro. It removes HDCP on all hosts, so it is not a fix XREAL should ship.

## Our firmware comparison (read-only, official packages)
- Ultra DP image `1140` (project 0x1200) has a payload byte-identical to the Air 2 / Air 2 Pro
  `1140` (SHA-256 of the 8051 payload equal; only 5 container-header bytes differ).
- The two code sites involved (pixel-clock fallback near 0x472F; HDCP init branch near 0x9219)
  are present in the Ultra image with identical bytes. So the Ultra contains the same defect.
- Not confirmed by us on the Ultra: the register-level failure itself (needs a debug MCU build).

## Requests
1. An official Ultra DP firmware (newer than `1140`) that (a) validates the measured pixel clock
   and derives it from the link's M/N values when the measurement is 0, and (b) keeps HDCP,
   by deferring the HDCP advertisement until the PCR is locked, or tolerating an early start.
2. If that is not possible soon: confirmation whether the failure is known on Pixel 10 hosts.
3. Documentation of the Ultra's DP update recovery mechanism, specifically:
   - What the device validates at `FINISH` (msgid 113) and what happens to a partially written
     image after an interrupted transfer (cable pulled, host reset).
   - Whether PREPARE (110) resets the write position, so a transfer can safely be restarted.
   - Whether the bridge has a bank-0/fallback image on the Ultra (the Air/Air 2 fall back to
     an internal `1109`).
   - The role of the SOC's DP7911 factory/user backup slots (our reading of the SOC image's
     symbol names, undecoded) and whether the SOC can restore or overwrite the bridge image.
   - A supported way to confirm which DP image is installed beyond the version string, since
     differing builds can share `1140` (e.g. a checksum/readback message).
   - Whether the web updater's SOC step can be skipped when the SOC is already newer.

## Evidence available on request
Hardware reports (0.3.1 to 0.5.0), the firmware hashes, and the offline protocol simulation
in `tools/fw-analysis/`. Contact: the repository owner.
