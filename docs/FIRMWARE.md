# Air 2 Ultra DisplayPort firmware: analysis for the Pixel 10 black-screen fault

Read-only analysis, done at the owner's request. **Nothing was flashed, downgraded or
modified.** No firmware binary is stored in this repository; only hashes and findings are.

## What was obtained, and how

XREAL's own update service lists each product's official firmware. The web updater calls
`https://app-api.xreal.com/api/nebula/v1/isc/device/package?packageName=ai.nreal.web&hardwareCode=<N>&versionCode=1`
(hardwareCode 5 = Air 2 Ultra, 3 and 4 = Air 2 / Air 2 Pro). Each entry gives three files
(`mcu`, `dsp`, `dp`) with download URLs on `media.xreal.com`. The files were downloaded and
hashed. They are XREAL's firmware; they were only inspected.

### Official firmware inventory (2026‑11‑16)

| Device (hardwareCode) | file | name | size | SHA‑256 |
|---|---|---|---|---|
| Air 2 Ultra (5) | dp | `1140` | 50632 | `e88b8e64957473281fe96dcf4b7c3390ab1db6a0002321e7b87c5c8ff2974f95` |
| Air 2 Ultra (5) | mcu | `12.1.00.498_20241115.bin` | 29848610 | `ce416ac05d06c5206ae207a3a01800628f983e324a2a84b77de3b6d881b5938e` |
| Air 2 Ultra (5) | dsp | `09.A.00.001_20230615.bin` | 443080 | `510bc923c05b4912bbcdf35d1eddaff064f92f9c4aab781304b3273b27cbb6af` |
| Air 2 Pro (3) | dp | `1140` | 50632 | `350bace369a83823d8ef867ae04ad07cf64d724c10ec7ceecfb83861ac9672f3` |
| Air 2 (4) | dp | `1140` | 50632 | `350bace369a83823d8ef867ae04ad07cf64d724c10ec7ceecfb83861ac9672f3` |

The owner's glasses report DP version `1140`, DSP `09.A.00.001_20230615`, MCU
`12.1.00.502_20250408`. (The MCU version on the glasses, `…502`, is newer than the `…498`
the server currently offers; immaterial to the DP‑bridge analysis.)

## Finding 1: the Air 2 Pro DP file is exactly the image the public patch targets

The Air 2 / Air 2 Pro DP `1140` SHA‑256 `350bace3…ac9672f3` **equals**, byte for byte, the
input the [xreal-pixel-patches](https://github.com/mightatnight/xreal-pixel-patches) builder
pins (`350BACE3…AC9672F3`). So that analysis was done against this exact binary.

## Finding 2: the Ultra's DP `1140` is a *different file* but the *same code*

The Ultra's DP `1140` has a different SHA‑256 (`e88b8e64…`). But the two 50632‑byte files
differ in only **5 bytes, all in the 0x40‑byte container header**:

| offset | Air 2 / Air 2 Pro | Air 2 Ultra | what it is |
|---|---|---|---|
| 0x00–0x03 | `f3 7f a8 0a` | `3e 4c 82 12` | container CRC‑32 (computed over the rest; follows from 0x09) |
| 0x09 | `09` | `12` | a device/variant tag in the header |

**Everything from offset 0x40 onward — the entire 8051 firmware payload — is byte‑identical.**
Payload SHA‑256 is `173c4cfd…3d4a8415` for both. In other words the Air 2, Air 2 Pro and
Air 2 Ultra all run the **same DP‑bridge firmware image**, wrapped in a container whose only
meaningful difference is one device‑tag byte (and the CRC that depends on it).

## Finding 3: the two defective routines are present, identical, in the Ultra

The public patch changes three payload regions. Checked against the Ultra image:

| patch | code address | guard bytes expected | present in Ultra? |
|---|---|---|---|
| mkcalc hook (pixel‑clock recovery) | `0x472F` | `7b ff 7a 9f 79 8a 90 04 48 12 1a fe 90 05 e8 12 1b 16 12 3f c1` | **yes, identical** |
| nohdcp (HDCP‑init branch) | `0x9219` | `70 6f 7b ff 7a c3` | **yes, identical** |
| fallback routine "cave" | `0x9166–0x91FD` | 151 bytes of zero padding | **yes, all zero** |

So the exact pixel‑clock‑recovery bug (the PCR state machine programming the PLL with a
measured clock of 0 and never recovering) and the HDCP‑bring‑up stall described for the
Air 2 Pro exist, byte for byte, in the Air 2 Ultra's bridge firmware. The documented fix
logic applies to the Ultra's code without change.

## Feasibility of an Ultra‑specific fix

**Firmware content: feasible, high confidence.** A corrected Ultra DP image would be: the
Ultra's official `1140`, plus the same three payload edits (guards already verified to
match), plus a recomputed container CRC (0x00–0x03) and bank0 tag (0x38), **keeping the
Ultra's device‑tag byte 0x09**. This is a small, well‑understood adaptation of the existing
builder. The published builder cannot do it as‑is: it refuses any input whose SHA‑256 is not
the Air 2 file, and its output SHA is pinned to the Air 2 result.

**Flashing: not established, and this is where the real risk is.** The published flasher
(`tools/flash_dp.py` over the nyan kit's `dp_flash.py`) allowlists **only the Air 2 PIDs
`0x0428` and `0x0432`**. The Ultra is `0x0426`, so it is refused. The actual flash command
set, acknowledgement handling and the recovery path (the claim that a bad write falls back to
the bridge's internal image, recoverable by re‑flashing) live in the nyan‑real‑firmware‑kit,
which was **not examined here**, and the Ultra's USB/HID layout differs from the Air 2's
(controller on interface 0 vs 4). Whether the Ultra's DP‑flash protocol and recovery behave
the same is **unverified**. This is the part that could brick the glasses.

**One residual firmware assumption.** The fix makes the *bridge* reach "video‑OK = 1"; the
glasses' *MCU* is what then opens the OLED panels, and the Ultra's MCU firmware is a
different, newer file than the Air 2's. The Ultra's panels do light up on a Beam Pro, so its
MCU does open them once the bridge reports valid video — the mechanism the fix restores — but
that this holds for this exact failure path is an assumption, not a tested result.

**Unchanged costs.** The `nohdcp` edit stops the glasses advertising HDCP, so protected video
(Netflix and similar) would no longer play on the glasses on **any** device. Flashing needs a
host with a USB‑C data connection and the glasses' official recovery image to hand; there is
no phone‑only flashing path today, and the owner has only the phone and the glasses.

## Conclusion

- The Air 2 Ultra runs the **same DP‑bridge firmware** as the Air 2 Pro, and carries the
  **same two defects** the Pixel‑10 fix addresses. An Ultra‑specific patched image is
  technically feasible and small.
- Making and flashing it safely is **not** something this project will do without (a) reading
  and verifying the Ultra's actual flash and recovery protocol, and (b) the owner's separate,
  explicit approval. Neither has happened.
- This is firmware on the glasses, not the phone. An Android app cannot apply it.

This evidence (identical bridge code, identical defect, identical fix logic) is strong
material to give XREAL in a request for an official Ultra DP firmware that tolerates a zero
pixel‑clock measurement and an early HDCP start, and to give Google about the Pixel 10
DisplayPort stream start. That route changes nothing on the glasses and carries no risk.

---

# Flashing and recovery protocol for the Air 2 Ultra (read-only investigation)

Done at the owner's request, strictly read-only: no write command was sent, nothing was
flashed, and the glasses' MCU firmware is untouched. Sources: XREAL's own web updater
(`ota.xreal.com` bundles) and the nyan-real-firmware-kit (`8796n/nyan-real-firmware-kit`,
the flasher the public patch wraps). Both were read, not run against hardware.

## 1. The DP update protocol, and where the Ultra differs

- The glasses' control interface uses one 64-byte HID report format (report 0, `0xFD`,
  CRC-32, length, msgid, payload). The DP firmware write is four message ids:
  **PREPARE=110, START=111, TRANSMIT=112, FINISH=113**, ported from XREAL's own WebHID
  updater (`xreal-protocol.js` / `dp_tool.html`). The MCU verifies the container CRC at
  FINISH and returns 5 on a mismatch.
- The nyan kit opens the **Air 2 control interface MI_04 (interface 4)**. The **Air 2 Ultra's
  control interface is 0** (its USB layout differs: controller on interface 0, IMU on 2). The
  kit has no Ultra entry and its `PID_TO_PROJECT` explicitly marks Ultra PID `0x0426` → project
  `0x1200` as **"unsupported project 0x1200"**. This matches the container header exactly: the
  Ultra's project code at offset 0x08 is `0x1200` (Air 2 is `0x0900`).
- **The Ultra uses a different update SDK.** XREAL's Air 2 web page drives `air2SDK.upgradeInDp`
  + `air2SDK.upgradeInMcu`; the Ultra page drives `ultraSDK.upgradeInDp` + `ultraSDK.upgradeInSOC`
  (the Ultra reports itself as "Flora" and carries a 29 MB **SOC** image, not a small MCU image).
  So the Ultra has an official DP-firmware update path, but it is a **distinct code path** from
  the one proven on the Air/Air 2, and whether `ultraSDK.upgradeInDp` uses the same 110–113
  frames on interface 0, or routes the DP update through the Ultra's SOC, was **not** determined
  (it would require reverse-engineering the minified Ultra SDK or capturing the Ultra's own
  update traffic — neither is read-only-on-the-phone, and neither was done).
- **What is confirmed compatible:** the app already reads the DP bridge version with msgid
  `22` (`0x16`) on interface 0, and the glasses answer in exactly this frame format (the Check
  report shows `DisplayPort bridge firmware (0x16): … "1140"`). So the Ultra's MCU speaks the
  same control framing; only the write path (110–113) is unverified for it.

## 2. Packaging a patched image with the Ultra's header

The container machinery is model-generic: the header carries the project code (0x08) and a
length, the 4-byte CRC-32 (poly `0xF4ACFB13`) covers `d[8 : 8+length]`, and byte 0x38 is a
bank0 CRC-8 commit tag. Building a correct Ultra image is therefore mechanical — start from the
Ultra's official `1140`, apply the three verified payload edits, recompute the CRC and bank0
tag, keep project `0x1200`. The published builder will not do it (it hard-rejects any input
whose SHA-256 is not the Air 2 file, and pins the Air 2 output SHA), but the adaptation is
small and the guard bytes already match. This step is feasible; it is not the risk.

## 3. A Pixel-only updater

- XREAL's web updater needs **WebHID**, which **Android Chrome does not support** (it is
  desktop-only). So the vendor's own tool cannot run on the Pixel.
- A **native Android USB-host app** could in principle drive interface 0 with these HID frames
  without root (the app already does read-only control I/O on interface 0). So a Pixel-only DP
  updater is architecturally possible — **but it would be a write-capable firmware flasher**,
  which is exactly what is gated behind the owner's explicit approval and this assessment, and
  it would be writing against the **unverified** Ultra DP-update path (section 1). Building it
  now would mean flashing firmware over a protocol not confirmed for this model.

## 4. Recovery if a write fails (the decisive question)

What the kit documents, **for the Air/Air 2 bridge only**:

- **Corrupt or wrong-tag image:** the DP7911 bridge boot checks the bank0 commit tag; if it is
  wrong it **rejects bank0 and boots an internal fallback image** (version reads `1109`, name
  "nreal air", stuck at 60 Hz, no mode switching). **Recoverable** by writing a good image again.
- **The MCU also verifies the container CRC at FINISH** and refuses a mismatched image, so a
  bad image is caught before it is committed.
- **Interrupted transfer** (timeout, non-ack FINISH, cable pulled mid-write): PREPARE does not
  reset the header count / SPI offset, so retrying in the same power session is unsafe — the
  fix is **unplug and replug to reset the MCU, then re-flash**. That part is Pixel-only doable.

Why this is **not** a green light for the Ultra:

- All of the above is **verified on Air/Air 2 hardware, never on an Ultra.** The bank0 fallback
  lives in the DP7911 boot ROM (same bridge silicon, so likely the same), but "likely" is not
  "verified", and XREAL's `dp_tool.html` for the Ultra drives a **different SDK** that may not
  take the same path.
- The Ultra's DP update may pass through its **SOC** (`upgradeInSOC` exists; the DP and SOC are
  updated by the same `ultraSDK`). A failure that leaves the SOC side inconsistent is a
  different, unquantified recovery problem — not the simple bridge-ROM fallback. There is no
  public evidence either way.
- An MCU bootloader exists (Ultra BOOT PID `0x0425` alongside APP `0x0426`), but DP recovery
  does not use it, and nothing here tested it.

## 5. Read-only confirmation of the defect on *these* glasses

**Not possible without modifying the MCU.** Stock firmware exposes no message id to read an
arbitrary bridge register; the register-peek tool (`dpreg.py`) works only against a **custom
MCU build** that adds a generic peek on msgid `0x29`. Against stock firmware the reads time
out. So the bridge's link state, the video-OK flag and the pixel-clock measurement cannot be
read on the owner's glasses while preserving the stock MCU (which the owner requires). The
failure on these specific glasses is established by matching symptoms + byte-identical bridge
firmware, **not** by a direct register read.

## GO / NO-GO: Pixel-only permanent repair

**NO-GO at this time.** Not because the fix is wrong — the defect is confirmed and the patched
image is feasible — but because the two things that make flashing *safe* are unverified for the
Air 2 Ultra, and cannot be verified read-only or Pixel-only:

| Factor | Status |
|---|---|
| Defect present in the Ultra's bridge firmware | **GREEN** — byte-identical to the Air 2 Pro |
| Patched Ultra image can be built (header/CRC/project) | **GREEN** — mechanical, guards match |
| Ultra DP-update transport = the proven 110–113 path | **RED** — Ultra uses a separate `ultraSDK`; unconfirmed |
| Pixel-only write path exists today | **RED** — no WebHID on Android; needs a native write-capable updater, not built |
| Recovery from a failed write, verified on Ultra | **RED** — fallback documented for Air/Air 2 only; Ultra DP may involve the SOC |
| Confirm the defect on these glasses, read-only | **RED** — needs a modified MCU; excluded by "preserve MCU" |

**What a GO would require, and why none is Pixel-only or risk-free:** confirm the Ultra's own
`upgradeInDp` transport and its recovery behaviour (by capturing XREAL's official updater
talking to an Ultra, which needs a desktop with WebHID — not the phone), or obtain an
Ultra-tested recovery result from someone who has one. Until a failed Ultra DP write is shown
to recover, flashing risks a bridge state with no demonstrated way back.

**Zero-risk path, recommended:** the evidence here (identical bridge code, identical defect,
identical fix logic, the Ultra's own official DP-update capability) is a strong, specific
request to XREAL for an official Ultra DP firmware that tolerates a zero pixel-clock
measurement and an early HDCP start — the same two fixes, which XREAL can build, sign, ship and
flash through its own updater with a verified recovery path. That changes nothing on the
glasses and carries no risk. `DISPLAY.md` and this file are written to attach to that report.

---

## Round 3: the Ultra DP update protocol, reverse-engineered offline

Method: XREAL's own web-updater SDK (downloaded from its update site, **not committed**) was run
in Node against an in-memory fake HID device (`tools/fw-analysis/vendor_sim.cjs`). Nothing
touched USB or real glasses. Every frame the vendor code emits was recorded and checked.

### Verified offline (static analysis + simulation)

| Question | Result |
|---|---|
| Ultra `upgradeInDp` message IDs | PREPARE=110, START=111, TRANSMIT=112, FINISH=113: **same as Air 2 / Air 2 Pro** |
| Framing | Same: `0xFD`, CRC32 (LE) over `[5..5+len)`, len(17+payload), msgid at [15..16], payload at [22]; **512-byte reports** (PID 0x0426) instead of 64 |
| Differences from Air 2 | START is one 64-byte frame (Air 2: 42+22); TRANSMIT 490 B/frame (Air 2: 42); Ultra **checks FINISH status**, Air 2 ignores it; PREPARE timeout 16 s, others 2 s |
| Independent of `upgradeInSOC`? | **Yes at SDK level**: separate function, separate message IDs (SOC = 62–65), no shared state. The *website* orders them in the UI only |
| Client-side validation | **None** (no CRC, project, version or hash check before sending) |
| Retries / recovery in vendor code | **None**: stop-and-wait, first failure returns `false`, no resend, no abort command |
| Frames sent after a failure | 0 in every simulated failure case (status error, timeout, USB error) |
| Our protocol kit reproduces vendor frames | Byte-identical for Air 2 |
| Ultra DP image | `1140` payload is byte-identical to Air 2 Pro; container CRC valid; project 0x1200 |
| Page hazard | The page would also run the SOC step with its own (older) SOC image, i.e. **downgrade** the SOC (server 498 vs. installed 502), and skips DP when the version string already reads `1140` |

### Not verified: requires a real Ultra (or XREAL)

- What the device validates at FINISH and what it does with a partial image.
- Whether PREPARE resets the write offset (needed to know if an aborted transfer can be restarted).
- Whether the bridge's bank-0 fallback image exists on Ultra silicon (documented for Air/Air 2 only).
- **New:** the Ultra's SOC image contains DP7911 handlers named *Factory_Backup_FW_Upgrade_Finish*,
  *User_Backup_FW_Upgrade*, *FAC_Write_DP_Backup_FW_CRC* and a *7911 need_upgrade* check. The
  Air 2 MCU image (151 KB, downloaded and searched) contains **none** of these. So on the Ultra
  the 110–113 messages are probably served by a much larger SOC that manages backup slots and
  may re-write the DP bridge from a backup. That could be a safety net, or could silently
  revert a patched image. It is undecoded and **unverified**.
- Whether 512-byte frames work through Android's interrupt OUT endpoint (read-only test: repeat
  version reads at 512 bytes).
- Whether the two patches fix the display on these glasses (unchanged: matching symptoms only).

### Pixel-native updater: design only, nothing built

Transport is feasible (interface 0 already works, protocol is plain stop-and-wait). Hard gates
a future write-capable updater would need: PID 0x0426 and project 0x1200; exact SHA-256
allowlist for input; stock Ultra `1140` image verified and present before any write; no retries;
require a physical replug after any failure; post-write DP version read; SOC never touched; and
a user-visible warning. **No such code exists in this repository and none will be added without
your separate explicit approval.**

### Updated verdict

| Factor | Status |
|---|---|
| Defect present in Ultra bridge firmware | GREEN (byte-identical) |
| Patched image buildable | GREEN |
| Ultra DP transport = proven 110–113 | **GREEN (static + simulation)**, upgraded from RED |
| Independent of SOC at SDK level | **GREEN**; device-side role of SOC unknown (AMBER) |
| Pixel-native write path | AMBER: feasible, not built, deliberately |
| Recovery from an interrupted write on Ultra | **RED**: no vendor retry/restore logic, device behaviour unverified, SOC backup-slot semantics undecoded |
| Read-only confirmation of defect | RED (needs modified MCU) |

**Still NO-GO for flashing.** The transport blocker is gone; the recovery blocker is not, and it
is the one that matters. Zero-risk path unchanged: send XREAL the evidence for an official fix.

---

## Round 4 (partial)

**Done: read-only 512-byte transport test.** *Check, Display test, "512-byte read test"* sends
only the DP-bridge version read (0x16) and SOC version read (0x26) in 512-byte reports (the
frame size the Ultra updater uses) and records the endpoint sizes, bytes written and the
answers. It is a button, never automatic. `McuProtocol.serializeWideReadOnly` throws for
anything outside those two ids and for every update id (62–68, 110–113); unit tests assert the
frame is 512 bytes with a valid CRC, equals the existing 64-byte frame zero-padded, and that
every update, mode and brightness id is refused. **Not yet run on hardware.**

**Not done this round:**
- Unpacking the Ultra SOC image to trace the DP7911 backup/recovery handlers (items 1–2) was
  halted by a safety classifier on the session, so there is **no new evidence** about which
  slots are written, whether a failed update restores the factory image, or whether the SOC
  could overwrite a patched bridge image. Those remain **unverified**.
- Preserving HDCP after startup (item 4) was not investigated.
- Distinguishing patched from stock (item 5): of the message ids known so far, none reads the
  bridge image back; only version strings are readable, and both images report `1140`. No
  independent identity check is therefore known to exist. Treat as **unsolved**.

**Verdict unchanged: NO-GO for any write-capable updater.**
