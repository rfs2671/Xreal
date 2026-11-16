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

## Completed hardware tests (owner, recorded; not to be repeated)
| Test | Result |
|---|---|
| Windows read-only diagnostic | **Passed.** PID 0x0426, interface 0, 512-byte reports, 7 queries, all CRCs valid, DP `1140`, SOC `…502` |
| Picture from the Windows laptop, direct USB-C | **Works** (desktop mirrored correctly) |
| Picture from the Pixel 10 Pro XL, same glasses | **Black** in every mode |

Works-on-laptop / black-on-Pixel with bridge firmware whose payload is byte-identical to the
Air 2 Pro's is the published fault pattern. The glasses, panels, cable and bridge hardware are
therefore good, and "the fix applies" moves from *unknown* to **likely** (still not proven: the
register-level cause was never read on an Ultra).

## Recovery procedure for the Ultra: what exists and what does not
**No recovery procedure for the Ultra can be proven offline.** XREAL's Ultra message table
(read from its updater) contains exactly: version reads, DP update 110–113, SOC update 62–65,
boot/app jumps and two progress events. There is **no** image read-back, no backup/restore
command and no update-status query for the Ultra (the Air family has `R_DP7911_FW_IS_UPDATE`
and `W_UPDATE_DP`; the Ultra table does not). So the only recovery mechanism available to
anyone outside XREAL is: **write the official Ultra image again through the same 110–113 path.**

Why that path should survive a bad bridge image (evidence, not proof):
- The control channel is served by the SOC over USB HID, a different chip from the DP bridge.
  On the Pixel today the bridge is in a failed video state and the control channel works fully.
- The patched image passes the same CRC-32 and bank-0 checks as the official one, so the
  "rejected image" paths are not expected to be exercised at all.
- The patched code is byte-identical to code already reported running on the same bridge
  family (Air 2 Pro), and your stock payload is byte-identical to that model's stock payload.

What is unknown and cannot be learned without a write: what the Ultra does at FINISH; what an
*interrupted* transfer leaves behind; whether the bridge has the internal `1109` fallback on the
Ultra; and whether the SOC's backup logic restores or reverts the bridge (undecoded, blocked).

## Staged installation strategy (DESIGN — nothing built, each stage needs your approval)
The idea: **prove the restore path before a single changed byte is written.**

- **Stage 0 — Preconditions (no writes).** Laptop on mains power, sleep/hibernate and USB
  selective suspend off, glasses' own cable direct to the port that already showed a picture,
  Nebula and any browser tab on XREAL's site closed, exactly one XREAL device. Tool re-runs the
  read-only diagnostic, verifies both image files by SHA-256 (stock `E88B8E64…974F95`,
  patched `644CD71F…39BDE9`), records DP and SOC versions.
- **Stage 1 — Write the official Ultra image.** It carries the same version (`1140`) the
  glasses report, and is the only image XREAL publishes for the Ultra. **Whether it is
  byte-identical to what is installed cannot be verified: the glasses offer no read-back.** If
  the factory image differs from the published one, Stage 1 changes it, and the original cannot
  be recovered. Stage 1 exercises PREPARE/START/TRANSMIT/FINISH, the FINISH status,
  re-enumeration and timing on *your* unit. Pass = FINISH status 0, device back, DP `1140`, SOC
  unchanged, **picture on the laptop still works**. A pass shows a complete write of the
  official image works; it does not show an interrupted write is recoverable. If Stage 1 fails
  in any way: stop; the patched image is never written.
- **Stage 2 — Write the patched image.** Only after Stage 1 passed in the same session setup.
  Pass = FINISH 0, device back, DP `1140`, SOC unchanged, picture on the laptop still works.
- **Stage 3 — Test on the Pixel.** Picture appears; Android's HDCP level reads none.
- **Rollback.** Stage 1 again (official image). Also the response to: laptop picture worse
  after Stage 2, or no improvement on the Pixel.
- **Rules in every stage.** No retries; any non-ack, timeout or USB error stops the tool and
  requires unplug/replug before anything else; only messages 110–113 and the version reads can
  be sent; SOC messages (62–68) do not exist in the tool; hash allowlist of exactly two files.
- **The transfer is small:** 50,632 bytes = 1 START + 104 TRANSMIT frames; seconds, not minutes,
  which keeps the interruption window short.

## Must be verified before installation
Already verified: model and transport on Windows (hardware); fault pattern (hardware); patched
file structure, header, CRC-32, bank-0 tag, payload identity with the Air 2 Pro fix (offline);
update protocol and frame layout from XREAL's own code (offline simulation).

Still required, in order:
1. **Your approval to build the writer**, then your review of its code. It does not exist.
2. **Offline proof of the writer before it touches hardware:** its frames must be byte-identical
   to the frames XREAL's own SDK emits for the same file in the simulation harness
   (`tools/fw-analysis`), and it must stop after every injected failure (bad status, timeout,
   USB error) with zero further frames.
3. **Stage 0 preconditions** met on the day.
4. **Stage 1 passing on your glasses** before Stage 2 is allowed.
5. **Your acceptance of the residual risks**, which no software evidence removes:
   - an interruption *during* a write (even Stage 1) on an Ultra has never been observed;
     the expected remedy (replug, rewrite) is inferred from the Air family;
   - if the SOC silently restores its backup, the fix simply will not stick (harmless, but then
     only XREAL can fix it);
   - HDCP is off while the patched image is installed;
   - worst case, the display path stays dead until XREAL services the glasses, and a
     third-party firmware write may affect warranty.
Optional but the only thing that removes risk 5a/5b: XREAL's answer to `docs/XREAL_ESCALATION.md`.

## Current verdict
**NO-GO for flashing today; conditional GO to *build and offline-test* the staged writer if you
approve that step.** Flashing itself needs a second, separate approval after items 1–4.

## Staged writer: built and offline-tested (NOT run on hardware, writes disabled)
`tools/ultra-dp/ultra_dp_writer.py` and `tools/ultra-dp/test_writer_sim.py`. Built with the
owner's approval for offline work only. **`HARDWARE_WRITES_ENABLED = False`**: in this build
Stage 1, Stage 2 and restore refuse to send anything to real glasses; only Stage 0 (read-only)
works on hardware. Enabling writes is a separate release after a separate approval.

### What it enforces
- Opens only PID 0x0426 interface 0, exactly one device; refuses bootloader PID 0x0425 and any other XREAL model.
- Can build only version reads (0x15, 0x16, 0x26) and DP messages 110–113. SOC/MCU/DSP update ids, boot jumps and mode writes raise an error; there is one hardware write call site.
- Images: exact SHA-256 allowlist (official `E88B8E64…`, corrected `644CD71F…`), size, project 0x1200, type, name, container CRC-32, bank-0 tag, **and** the generated packet stream must hash to XREAL's reference stream.
- Before writing: glasses id, DP and SOC versions are each read twice and must agree; SOC must be a `12.` version; DP must read `1140` (restore also accepts `1109`).
- After writing: waits for the glasses, then requires the same glasses id, **identical SOC version**, DP `1140`; otherwise it reports an aborted write.
- Stage 1 and Stage 2 are separate commands, each with its own typed confirmation containing the glasses' id. Stage 1 ends by stating Stage 2 was not started. Stage 2 additionally needs the official file present, a Stage 1 receipt for the same glasses and SOC version less than 24 h old (single use), and a typed YES that the laptop picture works after Stage 1.
- No retries. Any error stops immediately, writes a lock file and prints the recovery steps; while locked, only restore is accepted. A restore invalidates the Stage 1 receipt.

### Offline test results (20 tests, all pass)
| Area | Result |
|---|---|
| Packets vs XREAL's updater | Byte-identical, both images: 107 frames of 512 bytes (PREPARE, START with 64 bytes, 104 TRANSMIT of 490/98 bytes, FINISH), compared directly against streams captured from XREAL's own SDK in simulation and pinned by hash |
| Wrong firmware | Refused, nothing sent: flipped bit, Air 2 project code, truncated, empty, files swapped between stages, unknown hash |
| Successful Stage 1, Stage 2, rollback | Correct image arrives in the simulated glasses; Stage 1 never continues into Stage 2 |
| Stage 2 gating | Refused without receipt, with wrong/missing confirmations, for other glasses, other SOC version, old receipt, or reused receipt |
| Invalid acknowledgement at PREPARE, START, first/middle/last TRANSMIT, FINISH; status 250; corrupt reply | Stops at that packet, zero packets afterwards, lock set |
| Timeout mid-transfer; USB disconnect mid-transfer | Same |
| After FINISH: glasses gone, SOC version changed, DP `1109`, different glasses | Reported as aborted, lock set, no receipt |
| After an abort | Stage 1/2 refused; no automatic retry; restore is refused before any packet unless the tool itself watched the glasses leave USB for 5 s and return; after that it succeeds *on the simulated glasses* |
| Glasses never return | Tool reports it cannot reach them; sends nothing |
| This build on "real hardware" path | Stage 1 and restore refused, zero bytes sent; Stage 0 sends only reads |

**Limit of these tests:** the simulated glasses follow behaviour documented for the Air family.
The tests prove what the *writer* does. They prove nothing about what a real Ultra does after an
interrupted write. **A successful Stage 1 would show a complete write works; it would not show
that an interrupted write is recoverable.**

### Remaining risks (unchanged by this work)
1. Interrupted write on an Ultra: outcome and recoverability unknown.
2. What the Ultra checks at FINISH, and whether it accepts the official image: unknown until Stage 1. Whether the installed image is byte-identical to the official file: unknowable (no read-back).
3. The SOC may hold a backup and revert the corrected image (harmless, fix would not stick) — undecoded.
4. The correction is proven on an Air 2 Pro, likely but unproven on the Ultra.
5. HDCP is off while the corrected image is installed; no way to tell images apart by version.
6. Windows HID behaviour for 110–113 is untested (only reads have run on hardware).
7. Worst case: display path unusable until XREAL services the glasses; warranty may be affected.

### Exact Windows procedure (for when, and only if, you approve flashing)
Preparation (no writes; you can do these now):
1. Folder with: this repository; the official Ultra DP file; the corrected file built by `build_ultra_dp.py`.
2. `python tools\ultra-dp\test_writer_sim.py` with `ULTRA_STOCK` and `ULTRA_PATCHED` set to the two files: all tests must pass.
3. Laptop on mains power; Settings > Power: sleep "Never"; close Nebula and any browser tab on xreal.com; glasses on their own cable in the port that showed a picture; nothing else on USB-C.
4. `python tools\ultra-dp\ultra_dp_writer.py --stage0 --stock <official> --patched <corrected>` — must print both files verified, glasses `G428X00969`, DP `1140`, SOC `12.1.00.502_20250408`.

Writes (need a build with writes enabled, issued only after your approval):
5. **Stage 1:** `--stage1 --stock <official>`; type `STAGE1 G428X00969`. Do not touch anything for ~1 minute. Then check the laptop picture. Stop here and report.
6. **Stage 2 (separate decision):** `--stage2 --stock <official> --patched <corrected>`; type `YES`, then `STAGE2 G428X00969`. Check the laptop picture, then the Pixel.
7. **Rollback at any time:** `--restore --stock <official>`; type `RESTORE G428X00969`.
8. **If anything says WRITE ABORTED:** do nothing else; unplug, wait 10 s, replug; run `--stage0`; send me the output before any restore attempt.

## Safety revisions after owner review (still offline-only; hardware writes still disabled)
1. **Replug is enforced, not requested.** After an aborted write, restore first makes the tool
   watch USB: the glasses must be absent for 5 continuous seconds and then return, or restore is
   refused before any packet. The glasses have no battery, so leaving USB removes their power.
   Tested: never unplugged, unplugged too briefly, never returned, presence unobservable → all refused.
2. **Stage 1 wording corrected** (above, and in the tool's own prompt): same *version*, byte
   identity with the installed image unverifiable.
3. **Lock hardening.** The lock file is flushed to disk before the first update packet; if it
   cannot be created nothing is sent. Ctrl+C, unexpected errors, a failed post-write check and a
   failed restore all leave it set. While set, Stage 1 and Stage 2 are refused; nothing retries
   by itself. The lock location is fixed (the command-line option to move it was removed).
4. **One-command preparation:** `python tools\ultra-dp\prepare.py` downloads the official
   Ultra firmware from XREAL's update service, verifies its SHA-256, downloads the two patch
   source files from the public project at a pinned commit (SHA-256 checked), builds the corrected
   image locally and verifies its SHA-256, runs the 25 simulator tests, then runs Stage 0.
   Run end to end in the development environment: steps 1–4 OK; Stage 0 needs your glasses.
5. **Stage 0 on your laptop:** not yet run. It is the last step of `prepare.py`; send
   `ultra_prepare_report.txt`.

### Simplest way to run it (Windows)
1. Install Python 3.10+ from python.org (tick "Add python.exe to PATH").
2. Download this repository (Code > Download ZIP), unzip, open a terminal in that folder.
3. Plug the glasses directly into the laptop.
4. `python tools\ultra-dp\prepare.py`
5. Send me `ultra_prepare_report.txt`. Expected last lines: all five items OK, glasses
   `G428X00969`, DP `1140`, SOC `12.1.00.502_20250408`, safety lock not set, hardware writes DISABLED.
