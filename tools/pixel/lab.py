#!/usr/bin/env python3
"""Everything that can be measured on the Pixel without its owner and without the glasses, in one run.

  python lab.py replay  RECORDING_NAME  OUT_DIR  [--runs base,epi10,...]     replay a recording under several settings
  python lab.py pacing  OUT_DIR                                             compositor frame pacing, both modes
  python lab.py restore                                                     remove the test settings, go Home

Needs the phone on Wi-Fi with wireless debugging, awake or asleep (the replay screen shows over the lock screen),
and the glasses unplugged. Nothing is installed here; use pixel_check.py install first.

replay: for each named run it writes the run's settings to files/vio_options.txt and files/hand_options.txt in the
app's folder, restarts the app, replays files/recordings/RECORDING_NAME through the trackers at recorded speed
(four minutes for the first recording), and pulls the CSV results. Afterwards the test settings are removed.
Compare the runs with tools/tracking/replay_report.py.

pacing: starts the browser with a simulated wearer and no glasses (the compositor then draws to the phone's own
screen) and reads SurfaceFlinger's record of when its frames were shown, once for each pacing mode. It measures
the phone's GPU and scheduler, not the glasses' display link.

The phone's screen timeout, stay-awake setting and everything outside the app's own folder are left alone.
"""
import os, re, subprocess, sys, time

IP = "192.168.1.162"; SERIAL_PART = "59250DLCQ002HQ"
PKG = "dev.ultraxr"; FILES = "/sdcard/Android/data/dev.ultraxr/files"; BROWSER = "com.igalia.wolvic.vanilla/com.igalia.wolvic.VRBrowserActivity"

RUNS = {
    # name: (vio_options.txt, hand_options.txt)
    "base":   ("", ""),
    "epi10":  ("config.optical_flow_epipolar_error=0.01\n", "stretch=1\n"),
    "epi20":  ("config.optical_flow_epipolar_error=0.02\n", "quality_filter=0\n"),
    "epi10l4": ("config.optical_flow_epipolar_error=0.01\nconfig.optical_flow_levels=4\n", "stretch=1\nquality_filter=0\n"),
}

def run(args, timeout=120):
    try:
        r = subprocess.run(args, capture_output=True, timeout=timeout); return (r.stdout + r.stderr).decode("utf-8", "replace")
    except subprocess.TimeoutExpired: return "timed out"

def device():
    run(["adb", "connect", IP + ":5555"], 20)
    for line in run(["adb", "devices"], 20).splitlines():
        p = line.split("\t")
        if len(p) == 2 and p[1].strip() == "device" and (SERIAL_PART in p[0] or IP in p[0]): return p[0]
    return None

def sh(dev, cmd, timeout=120): return run(["adb", "-s", dev, "shell", cmd], timeout).strip()

def put(dev, name, text):
    """Write a small text file into the app's folder (or remove it when empty)."""
    path = FILES + "/" + name
    if not text: sh(dev, "rm -f " + path); return
    tmp = os.path.join(os.environ.get("TEMP", "."), "uxr-" + name)
    with open(tmp, "w", newline="\n") as f: f.write(text)
    run(["adb", "-s", dev, "push", tmp, "/data/local/tmp/" + name], 60)
    sh(dev, "cp /data/local/tmp/%s %s && rm /data/local/tmp/%s" % (name, path, name))

def glasses_attached(dev): return "Air 2 Ultra" in sh(dev, "dumpsys display | grep -c 'Air 2 Ultra' ; dumpsys display | grep -m1 'Air 2 Ultra'")

def restore(dev):
    put(dev, "vio_options.txt", ""); put(dev, "hand_options.txt", "")
    sh(dev, "am force-stop com.igalia.wolvic.vanilla"); time.sleep(6); sh(dev, "am force-stop " + PKG)
    for p in ("sim_motion", "sim_auto"): sh(dev, "setprop debug.ultraxr.%s 0" % p)
    sh(dev, "setprop debug.xrt.ULTRAXR_DISPLAY_TIMING 0"); sh(dev, "input keyevent KEYCODE_HOME")
    print("restored: test settings removed; screen timeout " + sh(dev, "settings get system screen_off_timeout") + " ms, stay-awake " + sh(dev, "settings get global stay_on_while_plugged_in"))

def replay(dev, name, out, runs):
    os.makedirs(out, exist_ok=True)
    if glasses_attached(dev): print("The glasses are plugged in: a replay needs them unplugged. Nothing was run."); return 1
    if name not in sh(dev, "ls %s/recordings" % FILES): print("no such recording on the phone: " + name); return 1
    for tag in runs:
        vio, hands = RUNS[tag]
        put(dev, "vio_options.txt", vio); put(dev, "hand_options.txt", hands)
        sh(dev, "am force-stop " + PKG); time.sleep(2); sh(dev, "logcat -c")
        sh(dev, "input keyevent KEYCODE_WAKEUP")
        sh(dev, "am start -n %s/.ui.LauncherActivity --es replay %s --es replay_tag %s" % (PKG, name, tag))
        t0 = time.time(); done = ""
        while time.time() - t0 < 420:
            time.sleep(15)
            log = sh(dev, "logcat -d | grep -E 'UltraXR *: (replay finished|replayed|replay stopped|hand options|6DoF settings changed)' | tail -4")
            if "replay finished" in log or "replay stopped" in log: done = log; break
        print("%s: %s" % (tag, (done.splitlines()[-1].split("UltraXR :")[-1].strip() if done else "no end message after 7 minutes")), flush=True)
        for kind in ("vio.csv", "hands.csv", "notes.txt"):
            run(["adb", "-s", dev, "pull", "%s/replay/%s-%s" % (FILES, tag, kind), os.path.join(out, "%s-%s" % (tag, kind))], 120)
        temp = re.search(r"temperature: (\d+)", sh(dev, "dumpsys battery"))
        if temp and int(temp.group(1)) > 420: print("battery at %.1f C: pausing two minutes" % (int(temp.group(1)) / 10)); time.sleep(120)
    restore(dev)
    return 0

def pacing(dev, out):
    here = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ui-automation"); sys.path.insert(0, here)
    import json, uia, pacing_test as pt
    uia.SERIAL = dev; os.makedirs(out, exist_ok=True); results = {}
    if glasses_attached(dev): print("The glasses are plugged in: this measurement is made on the phone's own screen. Nothing was run."); return 1
    for mode in ("own", "display"):
        sh(dev, "am force-stop " + PKG); time.sleep(2)
        sh(dev, "input keyevent KEYCODE_WAKEUP")
        sh(dev, "setprop debug.xrt.ULTRAXR_DISPLAY_TIMING %d" % (1 if mode == "display" else 0))
        sh(dev, "setprop debug.xrt.XRT_COMPOSITOR_LOG warn"); sh(dev, "setprop debug.ultraxr.sim_motion 1"); sh(dev, "setprop debug.ultraxr.sim_auto 0")
        sh(dev, "am start -n %s/.ui.LauncherActivity --ez sim_wearer true --ez pace_by_display %s" % (PKG, "true" if mode == "display" else "false")); time.sleep(5)
        sh(dev, "am start -n " + BROWSER); time.sleep(22)
        r = pt.measure(40, layer_hint="wolvic"); results[mode] = r
        print(mode + ": " + json.dumps(r), flush=True)
        sh(dev, "am force-stop com.igalia.wolvic.vanilla"); time.sleep(7)
    json.dump(results, open(os.path.join(out, "pacing-pixel.json"), "w"), indent=1)
    sh(dev, "am start -n %s/.ui.LauncherActivity --ez sim_wearer false --ez pace_by_display false" % PKG); time.sleep(2)
    restore(dev)
    return 0

def main():
    a = sys.argv[1:]
    if not a or a[0] not in ("replay", "pacing", "restore"): print(__doc__); return 2
    dev = device()
    if dev is None: print("The Pixel is not reachable over adb. Nothing was done."); return 3
    if a[0] == "restore": restore(dev); return 0
    if a[0] == "pacing": return pacing(dev, a[1])
    runs = list(RUNS)
    if "--runs" in a: i = a.index("--runs"); runs = a[i + 1].split(","); del a[i:i + 2]
    return replay(dev, a[1], a[2], runs)

if __name__ == "__main__":
    sys.exit(main())
