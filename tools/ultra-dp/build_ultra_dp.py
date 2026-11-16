#!/usr/bin/env python3
"""OFFLINE ONLY. Prepare and verify an Air 2 Ultra DP-bridge image from XREAL's official file.

Touches no hardware and contains no firmware and no USB code. It applies the two guarded
patches published by github.com/mightatnight/xreal-pixel-patches (mkcalc + nohdcp) to the
Ultra's own official DP image, keeping the Ultra header (project 0x1200), and re-computes the
container CRC-32 and bank-0 tag. You supply:

  --src       the official Ultra DP image you downloaded (the file named 1140), unmodified
  --upstream  a checkout of mightatnight/xreal-pixel-patches (for its 8051 assembler and
              mkcalc.asm; nothing from it is copied into this repository)
  --air2-src  optional: the official Air 2 / Air 2 Pro 1140, for an independent cross-check

Modes: --check-only (build in memory, verify, write nothing) or --out FILE.
This script is not a flasher. It cannot talk to the glasses.
"""
from __future__ import annotations
import argparse, hashlib, struct, sys
from pathlib import Path

HDR = 0x40
BANK0 = 0x10000
TAG_OFF = 0x38
AIR2_STOCK_SHA256 = "350BACE369A83823D8EF867AE04AD07CF64D724C10EC7CEECFB83861AC9672F3"
ULTRA_PROJECT, ULTRA_FWTYPE, NAME = 0x1200, 2, "1140"
CAVE, CAVE_END = 0x9166, 0x91FD

def fail(msg: str):
    sys.exit("ERROR: " + msg)

def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest().upper()

def crc32_container(data: bytes) -> int:           # poly 0xF4ACFB13, MSB first, init 0, no xorout
    c = 0
    for b in data:
        c ^= b << 24
        for _ in range(8):
            c = ((c << 1) ^ 0xF4ACFB13) & 0xFFFFFFFF if c & 0x80000000 else (c << 1) & 0xFFFFFFFF
    return c

def crc8_tag(data: bytes) -> int:                  # poly 0x31, MSB first, init 0
    c = 0
    for b in data:
        c ^= b
        for _ in range(8):
            c = ((c << 1) ^ 0x31) & 0xFF if c & 0x80 else (c << 1) & 0xFF
    return c

def head(d: bytes) -> dict:
    return dict(crc=struct.unpack_from("<I", d, 0)[0], length=struct.unpack_from("<I", d, 4)[0],
                project=struct.unpack_from("<I", d, 8)[0], fwtype=struct.unpack_from("<I", d, 12)[0],
                name=d[16:36].split(b"\0")[0].decode("ascii", "replace"))

def bank0_tag(d: bytes) -> int:
    p = bytes(d[HDR:]); return crc8_tag(p + b"\xFF" * (BANK0 - 1 - len(p)))

def bank0_ok(d: bytes) -> bool:
    p = bytes(d[HDR:]); return crc8_tag(p + b"\xFF" * (BANK0 - 1 - len(p)) + bytes([d[TAG_OFF]])) == 0

def validate(d: bytes, what: str, project: int) -> None:
    h = head(d)
    if h["project"] != project or h["fwtype"] != ULTRA_FWTYPE or h["name"] != NAME:
        fail(f"{what}: header project/type/name = 0x{h['project']:04X}/{h['fwtype']}/{h['name']!r}")
    if h["length"] + 8 != len(d):
        fail(f"{what}: declared length does not match the file")
    if crc32_container(d[8:8 + h["length"]]) != h["crc"]:
        fail(f"{what}: container CRC-32 mismatch")
    if not bank0_ok(d):
        fail(f"{what}: bank-0 tag mismatch")

def load_patches(upstream: Path):
    sys.path.insert(0, str(upstream / "patches"))
    from asm8051 import assemble  # type: ignore
    mk = assemble((upstream / "patches" / "mkcalc.asm").read_text().splitlines(), CAVE)
    if len(mk) > CAVE_END - CAVE:
        fail("mkcalc does not fit the cave")
    return [
        ("mkcalc hook", 0x472F, bytes.fromhex("7bff7a9f798a900448121afe9005e8121b16123fc1"), bytes.fromhex("129166") + bytes(18)),
        ("mkcalc routine", CAVE, bytes(len(mk)), mk),
        ("nohdcp", 0x9219, bytes.fromhex("706f7bff7ac3"), bytes.fromhex("80")),
    ]

def build(src: bytes, patches) -> bytes:
    out = bytearray(src)
    for label, code, guard, new in patches:
        off = code + HDR
        if src[off:off + len(guard)] != guard:
            fail(f"guard mismatch at code 0x{code:04X} ({label}); refusing")
        out[off:off + len(new)] = new
    out[TAG_OFF] = bank0_tag(out)
    h = head(bytes(out))
    struct.pack_into("<I", out, 0, crc32_container(bytes(out[8:8 + h["length"]])))
    return bytes(out)

def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--src", type=Path, required=True)
    ap.add_argument("--upstream", type=Path, required=True)
    ap.add_argument("--air2-src", type=Path)
    ap.add_argument("--expect-src-sha256", required=True, help="SHA-256 you trust for the official Ultra file")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--out", type=Path); g.add_argument("--check-only", action="store_true")
    a = ap.parse_args()

    src = a.src.read_bytes()
    if sha(src) != a.expect_src_sha256.upper():
        fail(f"source SHA-256 {sha(src)} is not the one you pinned")
    validate(src, "official Ultra image", ULTRA_PROJECT)
    patches = load_patches(a.upstream)
    img = build(src, patches)
    validate(img, "patched Ultra image", ULTRA_PROJECT)

    allowed = {0, 1, 2, 3, TAG_OFF}
    for _l, code, _g, new in patches:
        allowed |= set(range(code + HDR, code + HDR + len(new)))
    stray = [i for i, (x, y) in enumerate(zip(src, img)) if x != y and i not in allowed]
    if stray: fail("bytes changed outside the patch records: " + ", ".join(hex(i) for i in stray[:8]))
    if build(src, patches) != img: fail("build is not deterministic")
    hs, hi = head(src), head(img)
    assert (hs["project"], hs["fwtype"], hs["name"], hs["length"]) == (hi["project"], hi["fwtype"], hi["name"], hi["length"])
    if src[0x08:0x38] != img[0x08:0x38] or src[0x39:0x40] != img[0x39:0x40]:
        fail("header fields other than CRC and bank-0 tag changed")

    print("=== Air 2 Ultra DP image (mkcalc + nohdcp) : OFFLINE, no hardware touched ===")
    print("  input   sha256", sha(src))
    print("  output  sha256", sha(img), f"({len(img)} B)")
    print(f"  header  project 0x{hi['project']:04X}  type {hi['fwtype']}  name {hi['name']!r}  (unchanged from official)")
    print(f"  CRC-32  0x{hi['crc']:08X} (was 0x{hs['crc']:08X}) [verified]   bank0 tag 0x{img[TAG_OFF]:02X} (was 0x{src[TAG_OFF]:02X}) [verified]")
    print("  patched bytes:", sum(len(p[3]) for p in patches), " changed file bytes:", sum(x != y for x, y in zip(src, img)))
    if a.air2_src:
        air = a.air2_src.read_bytes()
        if sha(air) != AIR2_STOCK_SHA256: fail("--air2-src is not the official Air 2 / Air 2 Pro 1140")
        ref = build(air, patches)
        if ref[HDR:] != img[HDR:]: fail("patched payload differs from the Air 2 build; payloads should be identical")
        print("  cross-check: patched payload == patched Air 2 / Air 2 Pro payload (the combination reported working on an Air 2 Pro)")
    if a.out:
        if a.out.resolve() == a.src.resolve(): fail("refusing to overwrite the input")
        a.out.write_bytes(img); print("  wrote", a.out)
    else:
        print("  check-only: nothing written")

if __name__ == "__main__":
    main()
