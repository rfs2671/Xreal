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
