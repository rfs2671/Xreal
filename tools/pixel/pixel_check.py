#!/usr/bin/env python3
"""Installs Ultra XR and the Ultra XR copy of Wolvic on the Pixel and proves what is on it.

  python pixel_check.py install  ULTRAXR.apk  WOLVIC.apk     install both if they differ, then verify
  python pixel_check.py verify   ULTRAXR.apk  WOLVIC.apk     verify only, change nothing
  python pixel_check.py journal                               what the last sessions on the phone recorded

Everything goes over adb (wireless debugging); nothing on the phone has to be touched. "Verify"
compares the SHA-256 of the APK that is installed on the phone with the file on this machine, asks
the phone's OpenXR runtime broker which runtime it would give an application, and reads back the
settings that must not have changed (30 second screen timeout, stay-awake off).

Exit code 0 only if every check passed. It never claims the apps work: that needs the glasses.
"""
import hashlib, json, subprocess, sys

PIXEL_SERIAL = "59250DLCQ002HQ"; PIXEL_IP = "192.168.1.162"
ULTRA = "dev.ultraxr"; WOLVIC = "com.igalia.wolvic.vanilla"
FILES = "/sdcard/Android/data/dev.ultraxr/files"

def run(args, timeout=120):
    try:
        r = subprocess.run(args, capture_output=True, timeout=timeout)
        return r.returncode, (r.stdout + r.stderr).decode("utf-8", "replace")
    except subprocess.TimeoutExpired: return 124, "timed out"

def device():
    run(["adb", "connect", PIXEL_IP + ":5555"], 20)
    code, out = run(["adb", "devices"], 20)
    for line in out.splitlines():
        p = line.split("\t")
        if len(p) == 2 and p[1].strip() == "device" and (PIXEL_SERIAL in p[0] or PIXEL_IP in p[0]): return p[0]
    return None

def sh(dev, cmd, timeout=120): return run(["adb", "-s", dev, "shell", cmd], timeout)[1].strip()

def sha_local(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    return h.hexdigest()

def sha_installed(dev, package):
    path = sh(dev, "pm path " + package).replace("package:", "").splitlines()
    if not path or not path[0].startswith("/"): return None
    out = sh(dev, "sha256sum " + path[0], 300)
    return out.split()[0] if out and len(out.split()[0]) == 64 else None

results = []
def check(name, ok, detail=""):
    results.append(bool(ok)); print(("PASS  " if ok else "FAIL  ") + name + (("  [" + str(detail) + "]") if detail != "" else ""), flush=True)

def journal(dev):
    out = sh(dev, "cat " + FILES + "/journal.jsonl 2>/dev/null")
    lines = [l for l in out.splitlines() if l.startswith("{")]
    if not lines: print("no session has been recorded on the phone yet"); return []
    rows = []
    for l in lines[-8:]:
        try: rows.append(json.loads(l))
        except Exception: pass
    for r in rows:
        print("%-28s %-28s %6.0f s  position tracked %3.0f%%  moved up to %.2f m  hands L/R %d/%d frames  pinches L/R %d/%d  recentres %d  landmarks %.0f (both cameras %.1f)" % (
            r.get("client", "?")[:28], r.get("display", "?")[:28], r.get("seconds", 0), 100 * r.get("position_tracked_fraction", 0), r.get("max_head_travel_m", 0),
            r.get("left_hand_frames", 0), r.get("right_hand_frames", 0), r.get("left_pinches", 0), r.get("right_pinches", 0), r.get("recenters", 0),
            r.get("mean_landmarks", 0), r.get("mean_stereo_matches", 0)))
        extra = []
        for side in ("left", "right"):
            if side + "_min_gap_mm" in r: extra.append("%s hand: fingertips closest %d mm, frames under 20/30/50 mm %s" % (side, r[side + "_min_gap_mm"], r.get(side + "_frames_gap_under_20_30_50_mm", "?")))
        if "touchpad_selections" in r: extra.append("touchpad selections %d" % r["touchpad_selections"])
        if extra: print("    " + "; ".join(extra))
    rec = sh(dev, "ls -la " + FILES + "/recordings 2>/dev/null")
    if rec: print("tracking recordings on the phone:\n" + rec)
    return rows

def main():
    a = sys.argv[1:]
    if not a or a[0] not in ("install", "verify", "journal"): print(__doc__); return 2
    dev = device()
    if dev is None: print("The Pixel is not reachable over adb (asleep, off Wi-Fi, or wireless debugging off). Nothing was checked."); return 3
    print("Pixel reachable as " + dev + ": " + sh(dev, "getprop ro.product.model") + ", build " + sh(dev, "getprop ro.build.id"))
    if a[0] == "journal": journal(dev); return 0
    if len(a) != 3: print(__doc__); return 2
    for package, path in ((ULTRA, a[1]), (WOLVIC, a[2])):
        want = sha_local(path); have = sha_installed(dev, package)
        if a[0] == "install" and have != want:
            code, out = run(["adb", "-s", dev, "install", "-r", path], 900)
            print("installing %s: %s" % (package, out.strip().splitlines()[-1] if out.strip() else code))
            have = sha_installed(dev, package)
        check("%s on the phone is this build" % package, have == want, "phone %s, built %s" % ((have or "not installed")[:12], want[:12]))
    row = sh(dev, "content query --uri content://org.khronos.openxr.runtime_broker/openxr/1/abi/arm64-v8a/runtimes/active")
    check("the phone's OpenXR broker gives applications Ultra XR's runtime", "package_name=dev.ultraxr" in row and "libopenxr_monado.so" in row, row[:90])
    lib = row.split("native_lib_dir=")[-1].split(",")[0] if "native_lib_dir=" in row else ""
    check("the runtime library is where the broker says", lib != "" and "libopenxr_monado.so" in sh(dev, "ls " + lib), lib[-40:])
    check("the browser is installed as an XR application", "VRBrowserActivity" in sh(dev, "cmd package query-activities --brief -a android.intent.action.MAIN -c org.khronos.openxr.intent.category.IMMERSIVE_HMD"))
    check("screen timeout is still 30 seconds", sh(dev, "settings get system screen_off_timeout") == "30000")
    check("stay-awake is still off", sh(dev, "settings get global stay_on_while_plugged_in") == "0")
    check("Ultra XR does not hold the overlay permission (the runtime needs it not to)", "SYSTEM_ALERT_WINDOW: allow" not in sh(dev, "appops get dev.ultraxr SYSTEM_ALERT_WINDOW"))
    print("\n%d checks, %d failed" % (len(results), results.count(False)))
    return 0 if all(results) else 1

if __name__ == "__main__":
    sys.exit(main())
