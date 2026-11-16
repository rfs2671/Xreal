# Black display on Pixel 10: what is known, and what an app can and cannot do

Status on the owner's hardware (Pixel 10 Pro XL, Android 17, XREAL Air 2 Ultra, direct cable):
Android detects the glasses as an external display and presents frames at 1920x1080@60, USB
data (cameras, IMU, controller) works, and the panels stay black, including for plain Android
mirroring. The same glasses work on a Beam Pro.

## Evidence

**1. A documented fault with the same symptoms, for a different model.**
[mightatnight/xreal-pixel-patches](https://github.com/mightatnight/xreal-pixel-patches)
analyses an Air 2 Pro on a Pixel 10 Pro (Android 17 beta): alt mode up, link training
successful, EDID read, frames presented, USB working, panels black. Its findings:

- The glasses' DisplayPort bridge measures the incoming pixel clock as zero on the Pixel's
  stream, programs its PLL with that without a check, and never recovers. The glasses'
  controller only switches the panels on after the bridge reports valid video.
- The Pixel starts HDCP about a second after video starts. With HDCP advertised, the bridge
  stalls before it reaches the clock measurement. The author reproduced this on a laptop by
  requesting HDCP within about four seconds of video start.
- The demonstrated fix is two patches to the **bridge firmware of the Air 2 / Air 2 Pro**
  (seed the PLL from the link's own clock values; stop advertising HDCP). The author lists
  no phone-side or app-side workaround, and tested 1080p60 only.

**1b. What the owner's own report adds (Ultra XR 0.4.0, Pixel 10 Pro XL, build CP3A.261005.005).**

- The Ultra's DisplayPort bridge reports firmware **`1140`**: the same bridge image version as
  the Air 2 Pro in the analysis above. Its controller firmware is different
  (`12.1.00.502_20250408`), as expected for another model.
- Android has the external display enabled and mirroring (Settings screenshots), state ON,
  one mode offered: 1920x1080@60. So the documented phone-side cause, a dismissed
  "use this display" prompt, does not apply.
- The glasses' own log shows "Before change display mode,close oled" when the mode was
  written, and no screen-on or panel-on message was received afterwards.
- The app's window on the glasses presented over 5,000 frames at 60 per second with no
  graphics error while the panels were black.

Taken together: the phone sends video, the glasses do not light the panels, and the bridge
image carries the version for which exactly this behaviour is documented. That is strong
circumstantial evidence for the same bridge fault. It is not proof: an equal version string
does not show the Ultra's bridge image is byte-identical to the Air 2 Pro's.

**2. This has not been shown for the Air 2 Ultra.** The Ultra has a different USB layout
(controller on interface 0, IMU on interface 2), its own firmware set ("SOC" and "DP"
firmware), and no public analysis of its bridge. The Air 2 Pro patch must not be applied to
it: nothing shows the images are compatible, and a wrong bridge image can leave the glasses
without a picture on every device.

**3. 3840x1080 is never offered.** Two candidate causes, not yet separated: the stalled
bridge never presents the side-by-side EDID after the mode switch, or Android drops the mode
(a third-party description of an Android rule says external modes with more pixels than the
phone's own panel are hidden; 3840x1080 is slightly more than a 1344x2992 panel). The report
now logs whether a hot-plug followed the mode switch, which tells these apart.

## What an ordinary app can do

It cannot change how the phone starts the DisplayPort link, whether or when it starts HDCP,
or the glasses' bridge firmware. It can:

- keep the glasses in plain 2D 1920x1080@60 (now the default; 3D is opt-in);
- send the documented controller commands for display mode and brightness, and read versions;
- ask Android for another display mode that the glasses advertise;
- observe: controller answers, unsolicited controller messages (screen on/off, panel power,
  firmware log lines), and every Android display hot-plug and mode change.

The **Display test** screen (Check, then Display test) does exactly this: a bright test
pattern drawn with an ordinary view, then one-step experiments, each a single button press
that is written to the report. Nothing toggles by itself. In order of how much evidence there
is that they could help (none has a documented success):

1. **Read status**: bridge firmware version and controller versions (read-only).
2. Check Settings, Connected devices, External displays: the display must be enabled. A
   dismissed "use this display" prompt is the one documented phone-side cause of a black screen.
3. **Restart video link**: re-writes the current mode, which makes the glasses re-plug the link.
4. **2D 72 / 90 / 120 Hz**: a different pixel clock; untested on any Pixel.
5. **Brightness max**, and double-clicking the glasses' display button, to rule out a blanked panel.
6. **Next Android mode** and **Protected on/off**: phone-side variations.

After each, wait five seconds and answer "Yes, I see it" or "Still black".

## The specific change that would be needed

A bridge image for the Air 2 Ultra with the two documented corrections (clock seeding when the
measurement is zero; no HDCP advertisement, or a tolerance for HDCP starting early). To know
whether the published Air 2 Pro patch even applies, the Ultra's official DisplayPort firmware
file would have to be compared byte for byte with the `1140` image that patch targets. Until
someone has done that and tested it on an Ultra, flashing it is a gamble with the glasses'
only video path, and this project does not do it.

## What would actually fix it, if the Ultra has the Air 2 Pro's fault

- **XREAL:** bridge firmware for the Air 2 Ultra that tolerates a zero clock measurement and
  an early HDCP start. Evidence to cite: the root-cause document above, plus this app's
  report (bridge firmware version, absence of a panel-on message after link-up).
- **Google:** a Pixel DisplayPort change so the stream start gives the bridge a valid clock
  measurement, or HDCP that starts later. No public Google bug was found.
- **A workaround outside the phone and glasses:** a device that terminates DisplayPort and
  regenerates it (a dock or adapter chain) might avoid both faults, since ordinary computers
  work. Unconfirmed.

Sources: the repository above; [nrealAirLinuxDriver](https://gitlab.com/TheJackiMonster/nrealAirLinuxDriver)
for controller message ids; [nyan-real-firmware-kit](https://github.com/8796n/nyan-real-firmware-kit)
for Air 2 display modes and hot-plug behaviour. XREAL's forum, Reddit and Google's issue
tracker could not be searched from the build environment, so the absence of an Ultra report
there is weak evidence.
