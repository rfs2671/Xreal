#!/usr/bin/env python3
"""One-command preparation for the Air 2 Ultra DP fix on Windows. WRITES NOTHING TO THE GLASSES.

    python tools\\ultra-dp\\prepare.py

It: (1) downloads XREAL's official Ultra DP firmware from XREAL's own update service and checks
its SHA-256; (2) downloads the two small patch source files from the public
xreal-pixel-patches project at a pinned commit and checks their SHA-256; (3) builds the
corrected image locally and checks its SHA-256; (4) runs the offline simulator tests;
(5) runs Stage 0 (read-only) against the connected glasses and verifies both files.
Everything is saved in the folder  ultra-dp-work  and summarised in  ultra_prepare_report.txt.
"""
from __future__ import annotations
import hashlib, io, json, os, subprocess, sys, urllib.request
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build_ultra_dp as B
import ultra_dp_writer as W

API = "https://app-api.xreal.com/api/nebula/v1/isc/device/package?packageName=ai.nreal.web&hardwareCode=5&versionCode=1"
UPSTREAM = "https://raw.githubusercontent.com/mightatnight/xreal-pixel-patches/b90f3fda0b53b6fc33803a4d85786e28eb3bdde3/patches/"
UPSTREAM_FILES = {"asm8051.py": "F2D7E43C96A50ADD364C2983DE716A52A8A7A90F988131F3DC87A95723A8963F",
                  "mkcalc.asm": "511CF567014BF584A3E60EE7D3B90B45105EC60CF5C79F4E8A623BE8754BECE2"}
WORK = Path.cwd() / "ultra-dp-work"
STOCK, PATCHED = WORK / "ultra_dp_1140_official.bin", WORK / "ultra_dp_1140_corrected.bin"
lines: list[str] = []

def say(msg=""):
    print(msg); lines.append(msg)

def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest().upper()

def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()

def step_official() -> bool:
    say("[1/5] Official Ultra DP firmware")
    if STOCK.is_file() and sha(STOCK.read_bytes()) == W.STOCK_SHA:
        say("      already present and verified"); return True
    try:
        info = json.loads(fetch(API))
        entry = next(f for f in info["data"]["files"] if f.get("fileType") == "dp")
        data = fetch(entry["filePath"])
    except Exception as e:
        say("      DOWNLOAD FAILED: %s" % e); return False
    if sha(data) != W.STOCK_SHA:
        say("      XREAL now serves a DIFFERENT file (SHA-256 %s)." % sha(data))
        say("      STOP: the analysis in this repository applies only to %s. Send me this report." % W.STOCK_SHA); return False
    STOCK.write_bytes(data); say("      downloaded from XREAL, SHA-256 verified: " + W.STOCK_SHA); return True

def step_patch_sources() -> bool:
    say("[2/5] Patch sources (public xreal-pixel-patches, pinned commit)")
    d = WORK / "upstream" / "patches"; d.mkdir(parents=True, exist_ok=True)
    for name, want in UPSTREAM_FILES.items():
        f = d / name
        if not (f.is_file() and sha(f.read_bytes()) == want):
            try: data = fetch(UPSTREAM + name)
            except Exception as e: say("      DOWNLOAD FAILED (%s): %s" % (name, e)); return False
            if sha(data) != want: say("      %s does not match its pinned SHA-256; STOP." % name); return False
            f.write_bytes(data)
        say("      %s verified" % name)
    return True

def step_build() -> bool:
    say("[3/5] Build the corrected image locally")
    try:
        src = STOCK.read_bytes()
        B.validate(src, "official image", B.ULTRA_PROJECT)
        img = B.build(src, B.load_patches(WORK / "upstream"))
        B.validate(img, "corrected image", B.ULTRA_PROJECT)
    except SystemExit as e:
        say("      BUILD FAILED: %s" % e); return False
    if sha(img) != W.PATCHED_SHA:
        say("      built image SHA-256 %s is not the expected %s; STOP." % (sha(img), W.PATCHED_SHA)); return False
    PATCHED.write_bytes(img); say("      built, SHA-256 verified: " + W.PATCHED_SHA); return True

def step_tests() -> bool:
    say("[4/5] Offline simulator tests (no hardware)")
    env = dict(os.environ, ULTRA_STOCK=str(STOCK), ULTRA_PATCHED=str(PATCHED))
    r = subprocess.run([sys.executable, str(HERE / "test_writer_sim.py")], env=env, capture_output=True, text=True)
    tail = [l for l in (r.stderr or "").strip().splitlines() if l.strip()][-2:]
    for l in tail: say("      " + l)
    ok = r.returncode == 0 and "skipped" not in " ".join(tail)
    if not ok: (WORK / "test_output.txt").write_text((r.stdout or "") + (r.stderr or ""))
    return ok

def step_stage0() -> bool:
    say("[5/5] Stage 0 on the connected glasses (read-only)")
    try:
        import hid  # noqa: F401
    except ImportError:
        say("      installing the HID library (pip install hidapi)...")
        subprocess.run([sys.executable, "-m", "pip", "install", "--quiet", "hidapi"])
    s = W.Session(W.open_hardware, W.STATE_DIR, log=lambda m: say("    " + m), presence=None)
    try:
        s.stage0(STOCK, PATCHED); return True
    except W.Refused as e:
        say("      Stage 0 did not complete: %s" % e)
    except OSError as e:
        say("      Stage 0 could not reach the glasses: %s" % e)
    return False

def main() -> int:
    WORK.mkdir(exist_ok=True)
    say("Air 2 Ultra DP fix: preparation (nothing is written to the glasses)")
    say("Python %s on %s; hardware writes in this build: %s" % (sys.version.split()[0], sys.platform, (", ".join(sorted(W.HARDWARE_WRITE_STAGES)) + " only") if W.HARDWARE_WRITE_STAGES else "DISABLED"))
    results = {}
    for name, fn in (("official firmware", step_official), ("patch sources", step_patch_sources), ("corrected image", step_build),
                     ("simulator tests", step_tests), ("stage 0", step_stage0)):
        if name in ("corrected image", "simulator tests") and not all(results.values()):
            results[name] = False; say("[skipped] %s: an earlier step failed" % name); continue
        if name == "stage 0" and not (results["official firmware"] and results["corrected image"]):
            results[name] = False; say("[skipped] stage 0: image files are not ready"); continue
        results[name] = fn()
    say(); say("SUMMARY")
    for k, v in results.items(): say("  %-18s %s" % (k, "OK" if v else "NOT OK"))
    say("  files: %s" % WORK)
    (Path.cwd() / "ultra_prepare_report.txt").write_text("\n".join(lines) + "\n")
    print("\nReport saved as ultra_prepare_report.txt - send me that file.")
    return 0 if all(results.values()) else 1

if __name__ == "__main__":
    sys.exit(main())
