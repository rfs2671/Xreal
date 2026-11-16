# Permanent display fix via a Windows laptop: status, steps, safety assessment

**Nothing in this document, or in the tools it describes, writes to the glasses.** There is
no flasher in this repository. A write-capable updater will only be built after your separate
explicit approval of the safety assessment at the end.

## What you can do today (all read-only)

### Step 1 — Diagnose the glasses from Windows (`tools/ultra-diag/ultra_diag.py`)
1. Install Python 3.10+ from python.org (tick "Add to PATH"), then in a terminal: `pip install hidapi`.
2. Download this repository (Code > Download ZIP) and unzip it.
3. Plug the glasses **directly** into the laptop's USB-C port (the one that does DisplayPort).
4. In the unzipped folder: `python tools\ultra-diag\ultra_diag.py --self-test` (no hardware; must say OK),
   then `python tools\ultra-diag\ultra_diag.py --list` (lists interfaces; sends nothing),
   then `python tools\ultra-diag\ultra_diag.py > ultra_diag_report.txt` and send me the file.

What it does: opens only PID 0x0426 interface 0, sends only version/status queries from a
fixed allowlist (it throws if asked for any firmware-update message), tries report sizes
512 then 64, and prints which size the glasses answered, the DP-bridge version, the SOC
version, and whether every response CRC is valid. It stops without sending anything if it
sees the bootloader PID 0x0425 or more than one Ultra. The *project code 0x1200 is inferred
from the PID* (XREAL's own updater table); no read message for it is known.

**If no device is found** (`HID interfaces found: 0`): the tool now also asks Windows whether
any USB device with XREAL's vendor id is present. If Windows sees none, the glasses are not on
USB at all: (1) use the glasses' own cable, plugged straight into the laptop, no hub, dock or
adapter; (2) try every USB-C port; the port needs USB data, and for a picture, DisplayPort (often
marked with a D-shaped or Thunderbolt icon); (3) check the glasses power on (a brief panel flash
or the laptop's "device connected" sound); (4) flip the USB-C plug and re-seat it at the glasses'
temple end. If Windows does see them but the tool does not, send the output.

### Step 2 — One free experiment: does the picture work on the laptop?
With the stock firmware, tell me whether the laptop shows a picture at 1920x1080@60.
Working on the laptop and black on the Pixel is what the Air 2 Pro fault looks like, and it
would be the strongest evidence yet that the fix applies. Black on both means the fix is
probably not for you.

### Step 3 — Build and verify the patched image offline (`tools/ultra-dp/build_ultra_dp.py`)
Needs the official Ultra DP file (see `docs/FIRMWARE.md` for how XREAL's update service lists it;
it must have SHA-256 `E88B8E64…974F95`) and a checkout of mightatnight/xreal-pixel-patches.
```
python tools\ultra-dp\build_ultra_dp.py --src ultra_dp_1140.bin --upstream xreal-pixel-patches ^
  --air2-src air2_dp_1140.bin --expect-src-sha256 E88B8E64957473281FE96DCF4B7C3390AB1DB6A0002321E7B87C5C8FF2974F95 --check-only
python tools\ultra-diag\ultra_diag.py --check-image <file>
```
The builder is not a flasher and has no USB code.

## Offline verification results (this session)
| Check | Result |
|---|---|
| Builder reproduces the upstream project's pinned output for the official Air 2 `1140` | **Exact match** `82F168B4…09E2A` (validates my CRC-32, bank-0 tag and patch application independently) |
| Official Ultra DP file: header, container CRC-32, bank-0 tag | Valid; project 0x1200, type 2, name `1140` |
| Patched Ultra image | 50,632 B, SHA-256 `644CD71F365D98F9D79B7E96A00C0A4B5BD3DE1E79BE2F8A9F62CD932739BDE9`; header unchanged (0x1200); only the CRC-32 (0x12824C3E→0xC4F2C6A5), bank-0 tag (0x0D→0x88) and 167 patch bytes differ; rebuild is deterministic |
| Patched Ultra payload vs patched Air 2 / Air 2 Pro payload | Identical, i.e. exactly the code reported working on an Air 2 Pro |
| Guard bytes at 0x472F / 0x9219 / cave 0x9166–0x91FD in the Ultra file | All match |
| Factory image for restoration | The official Ultra file above (the only restore image that exists; hash-pinned) |

These prove the *file* is structurally correct. They do **not** prove the glasses will accept
it, boot it, or display correctly with it.

## Official updater: failure handling, backup slots, recovery
- **Vendor web updater (read from its code):** no pre-checks, no retries, no abort command, no
  backup/restore UI. Any error returns `false`; its only advice is "refresh the page, reconnect
  the device, try again". The DP update runs entirely in normal mode (PID 0x0426). The SOC
  update uses the bootloader (PID 0x0425) and a device re-selection step. The two are independent
  at the SDK level (verified), but the page runs the SOC step too, and with an older SOC image
  than yours (498 vs your 502): **do not use XREAL's page for this**.
- **Air / Air 2 documentation (nyan kit, from vendor behaviour on the Air MCU firmware):** FINISH
  verifies the container CRC (status 5 on mismatch); a wrong bank-0 tag makes the bridge fall back
  to an internal image (version `1109`, "nreal air", 60 Hz only), fixed by writing a good image
  again; after any failed or interrupted write, PREPARE does not reset the write offset, so
  unplug/replug before retrying.
- **Ultra:** none of this is documented for the Ultra. Its SOC image contains DP7911
  factory/user backup handler names; decoding it was blocked and I have not bypassed that, so
  **I cannot say whether the SOC keeps a backup of the bridge image, restores it after a failed
  write, or could overwrite a patched one.**
- **Can a failed DP update be recovered without touching the SOC?** Probably, by rewriting the
  official image over the same control channel, because that channel (HID interface 0) is served
  by the MCU/SOC, not by the bridge being rewritten. That is an architectural inference,
  **not verified on an Ultra**, and only a failed write on real hardware can confirm it.

## Can HDCP be kept?
Not with anything that exists. Public analysis shows (a) HDCP is fine once a picture is running,
(b) HDCP requested within ~4 s of video start stalls the bridge before pixel-clock recovery, even
if turned off again. The shipped fix forces the bridge's "HDCP invalid" branch for the whole
session. Keeping HDCP needs new code: report no HDCP at link-up, then advertise it after the
clock locks and make the Pixel re-read it. That cannot be designed or tested offline, needs
register-level observation on the glasses (which needs a debug MCU build, i.e. another write),
and is therefore **not recommended**. Practical effect of the existing fix: protected streaming
(e.g. Netflix) will not play on the glasses on any host; games, XR and normal video are
unaffected. Restoring the official image restores HDCP.

## Telling patched from stock
Both report `1140`; no readback message is known. Reliable only **before** writing, by SHA-256.
After writing, evidence is behavioural: panels light up, and Android's DRM HDCP level (shown in
this app's display report) should drop to none. A proper check needs XREAL (checksum message or a
distinct version string).

## Proposed install / rollback procedure (DESIGN ONLY — not built, not approved)
Hard gates, all of which a future Ultra updater must enforce and refuse to bypass:
1. **Model:** PID 0x0426, interface 0, exactly one device; 512-byte read-only version read succeeds first.
2. **Image allowlist:** only the two SHA-256 values above (stock `E88B8E64…`, patched `644CD71F…`); container, project 0x1200, CRC-32 and bank-0 tag re-verified in the tool.
3. **Restore image required:** the stock Ultra file must be present and hash-verified before anything is written. There is no readback, so "backup" means this official file, valid only if the glasses are currently stock.
4. **SOC protection:** the updater contains only DP messages 110–113 and the version reads; it never emits 62–68 and never uses XREAL's page. The SOC version is read before and after and must be identical, or the tool stops.
5. **State gate:** writes only if DP version is `1140` (restore also allowed from `1109`); refuses if the glasses already look patched/unknown.
6. **No retries.** Any non-ack, timeout or USB error: stop, tell you to replug, then (only on your choice) write the **stock** image.
7. **Verify after write:** wait for re-enumeration, read DP version and SOC version, run the picture test on the laptop; restore if the picture is worse.
8. **Rollback:** the same tool writes the stock file (restores HDCP).
Each of those gates is a design requirement. None is implemented because no writer exists.

## GO / NO-GO
| Item | Verdict |
|---|---|
| Windows read-only diagnostic | **GO** (built, self-tested; not yet run on your glasses) |
| Offline patched Ultra image | **Prepared and structurally verified**; GO as a file, no hardware claim |
| Fix applies to your glasses (laptop picture test, Step 2) | **Unknown** until you run Step 2 |
| Ultra transport (110–113, 512-byte) | **Statically verified**; not yet confirmed on hardware (Step 1) |
| Recovery from a failed or interrupted Ultra write | **Unverified; cannot be verified without risking the unit** |
| SOC may revert or restore the bridge image | **Unknown** (analysis blocked) |
| Write-capable updater / flashing | **NO-GO** today |

**What flips it to GO:** (1) Step 1 shows the 512-byte read works from Windows; (2) Step 2 shows
laptop-works/Pixel-black; (3) you approve a specific, hash-pinned, gate-enforcing updater after
reading its code; (4) you accept that interrupted-write recovery on an Ultra is unproven and that
a failed write could leave the display path unusable until XREAL support intervenes. The remaining
risk is real and cannot be reduced to zero by software evidence: only XREAL's confirmation of the
Ultra's recovery behaviour (see `docs/XREAL_ESCALATION.md`) removes it.

## Hardware result: Windows diagnostic (owner's glasses)
Run on the owner's laptop, read-only. PID 0x0426 exposes HID interfaces 0, 1, 2 and 8;
interface 0 answered at the **512-byte** report size on the first try. All seven queries
returned status 0 with valid CRCs: DP bridge `1140`, SOC `12.1.00.502_20250408`, DSP
`09.A.00.001_20230615`, display mode 1 (2D 60 Hz), brightness 6. Nothing was written.

This moves "Ultra transport (512-byte frames on interface 0)" from *statically verified* to
**hardware-verified for read-only queries on Windows**. It says nothing about the update
messages (never sent), recovery, or whether the fix applies; Step 2 (laptop picture test) is
still open.
