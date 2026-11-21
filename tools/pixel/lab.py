#!/usr/bin/env python3
"""Everything that can be measured on the Pixel without its owner and without the glasses, in one run.

  python lab.py replay  RECORDING_NAME  OUT_DIR  [--runs base,epi10,...]     replay a recording under several settings
  python lab.py pacing  OUT_DIR                                             compositor frame pacing, both modes
  python lab.py diag3d  OUT_DIR                                             one logged look at half-width 3D (glasses plugged in)
  python lab.py restore                                                     remove the test settings, go Home

Needs the phone on Wi-Fi with wireless debugging and the glasses unplugged. A replay runs with the screen off and
the phone locked (a foreground service); the pacing measurement needs the phone unlocked, because the browser
does not show over the lock screen. Nothing is installed here; use pixel_check.py install first.

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

IP = os.environ.get("UXR_IP", "192.168.1.162"); SERIAL_PART = "59250DLCQ002HQ"      # UXR_IP: another phone (the S21 Ultra is 192.168.1.165)
PKG = "dev.ultraxr"; FILES = "/sdcard/Android/data/dev.ultraxr/files"; BROWSER = "com.igalia.wolvic.vanilla/com.igalia.wolvic.VRBrowserActivity"

RUNS = {
    # name: (vio_options.txt, hand_options.txt)
    "base":   ("", ""),
    "base2":  ("", ""),      # the same again: how much two runs of identical settings differ
    # position held while the motion sensor says the glasses are lying still (the default from this build on), twice,
    # and the same build with the hold switched off
    "hold":   ("", ""),
    "hold2":  ("", ""),
    # hand tracking on a recording with hands in it: as shipped, pictures brightened, quality filter off, both
    "h_base":    ("", ""),
    "h_stretch": ("", "stretch=1" + chr(10)),
    "h_qf0":     ("", "quality_filter=0" + chr(10)),
    "h_both":    ("", "stretch=1" + chr(10) + "quality_filter=0" + chr(10)),
    # the hand detector with and without its fisheye-corrected tiles (native/hands: ULTRA_HANDS_TILES), each twice
    "h_tiles0":  ("", "tiles=0" + chr(10)), "h_tiles0b": ("", "tiles=0" + chr(10)),
    "h_tiles3":  ("", "tiles=3" + chr(10)), "h_tiles3b": ("", "tiles=3" + chr(10)),
    "h_tiles6":  ("", "tiles=6" + chr(10)),
    # upstream's rules (no tiles, a hand dropped as soon as one camera is unsure) against the library as it now is, each twice
    "h_up":  ("", "tiles=0" + chr(10) + "keep=0" + chr(10)), "h_upb": ("", "tiles=0" + chr(10) + "keep=0" + chr(10)),
    "h_now": ("", ""), "h_nowb": ("", ""),
    # the plain CPU kernels against XNNPACK (the default), and what the time gained buys: three tiles a run
    "h_nox": ("", "xnnpack=0" + chr(10)), "h_noxb": ("", "xnnpack=0" + chr(10)),
    "h_t3": ("", "tiles=3" + chr(10)), "h_t3b": ("", "tiles=3" + chr(10)),
    "h_upx": ("", "tiles=0" + chr(10) + "keep=0" + chr(10)),      # upstream's rules on XNNPACK
    "h_keep": ("", "tiles=0" + chr(10) + "keep=1" + chr(10)),
    "h_tiles3c7": ("", "tiles=3" + chr(10) + "tiles_min_conf=0.7" + chr(10)),
    "hold3":  ("", ""),      # with the estimator restarted when it wanders while the glasses lie still
    "hold4":  ("", ""),
    "room":   ("", ""),      # the room model: floor, table tops and walls (TAG-room.csv)
    "room2":  ("", ""),
    "nohold": ("hold_when_still=0" + chr(10), ""),
    "epi10":  ("config.optical_flow_epipolar_error=0.01\n", "stretch=1\n"),
    "epi20":  ("config.optical_flow_epipolar_error=0.02\n", "quality_filter=0\n"),
    "epi10l4": ("config.optical_flow_epipolar_error=0.01\nconfig.optical_flow_levels=4\n", "stretch=1\nquality_filter=0\n"),
    # the right camera turned by the correction measured from the recording's own stereo pairs (third item)
    "rot":    ("", "", "0.24,-0.17,-0.04"),
    "rotxy":  ("", "", "0.24,-0.17,0"),
    "rothalf": ("", "", "0.12,-0.085,0"),
    # the angle between the cameras corrected by what makes the floor flat (tools/tracking/floor_flat.py)
    "verg":   ("", "", "0,-0.4,0"),
    "asis":   ("", "", ""),      # no trial value: the unit's standing correction (stereo_fix_unit.txt) applies
    "verg2":  ("", "", "0,-0.4,0"),
    "vergx":  ("", "", "0.24,-0.5,0"),
    # the estimator's start: the still second it waits for (XR Gate's limits, as shipped until 20 November), a third of
    # a second of a head not turning fast, three frames of the same, and no wait at all; "asis" twice is the app's own
    "g_still": ("native.startup_gate_frames=30" + chr(10) + "native.startup_gate_gyro=0.08" + chr(10) + "native.startup_gate_gyro_std=0.04" + chr(10) + "native.startup_gate_accel_err=0.75" + chr(10) + "native.startup_gate_accel_std=0.35" + chr(10), "", ""), "g_stillb": ("native.startup_gate_frames=30" + chr(10) + "native.startup_gate_gyro=0.08" + chr(10) + "native.startup_gate_gyro_std=0.04" + chr(10) + "native.startup_gate_accel_err=0.75" + chr(10) + "native.startup_gate_accel_std=0.35" + chr(10), "", ""),
    "g_10": ("native.startup_gate_frames=10" + chr(10) + "native.startup_gate_gyro=0.5" + chr(10) + "native.startup_gate_gyro_std=0.25" + chr(10) + "native.startup_gate_accel_err=1.5" + chr(10) + "native.startup_gate_accel_std=1.0" + chr(10), "", ""), "g_10b": ("native.startup_gate_frames=10" + chr(10) + "native.startup_gate_gyro=0.5" + chr(10) + "native.startup_gate_gyro_std=0.25" + chr(10) + "native.startup_gate_accel_err=1.5" + chr(10) + "native.startup_gate_accel_std=1.0" + chr(10), "", ""),
    "g_3": ("native.startup_gate_frames=3" + chr(10) + "native.startup_gate_gyro=1.0" + chr(10) + "native.startup_gate_gyro_std=0.5" + chr(10) + "native.startup_gate_accel_err=2.0" + chr(10) + "native.startup_gate_accel_std=1.5" + chr(10), "", ""), "g_3b": ("native.startup_gate_frames=3" + chr(10) + "native.startup_gate_gyro=1.0" + chr(10) + "native.startup_gate_gyro_std=0.5" + chr(10) + "native.startup_gate_accel_err=2.0" + chr(10) + "native.startup_gate_accel_std=1.5" + chr(10), "", ""),
    "g_off": ("native.startup_imu_gate=0" + chr(10), "", ""), "g_offb": ("native.startup_imu_gate=0" + chr(10), "", ""),
    "g_now": ("", "", ""), "g_nowb": ("", "", ""),
    # the saved map of the room: a run that makes one afresh (written when the replay ends), runs that look for the
    # head in the map that is there, and a run without any
    # what the map costs the estimator, finding in the map that is there: the worker kept to the small cores, OpenCV
    # with one thread, fewer corners, and all three
    "p_def": ("", "", ""), "p_defb": ("", "", ""),
    "p_little": ("slam.Ultra/Cores=little" + chr(10), "", ""),
    "p_cv1": ("slam.Ultra/CvThreads=1" + chr(10), "", ""),
    "p_little1": ("slam.Ultra/Cores=little" + chr(10) + "slam.Ultra/CvThreads=1" + chr(10), "", ""), "p_little1b": ("slam.Ultra/Cores=little" + chr(10) + "slam.Ultra/CvThreads=1" + chr(10), "", ""),
    "p_feat": ("slam.Kp/MaxFeatures=400" + chr(10) + "slam.Vis/MaxFeatures=600" + chr(10), "", ""),
    "p_all": ("slam.Ultra/Cores=little" + chr(10) + "slam.Ultra/CvThreads=1" + chr(10) + "slam.Kp/MaxFeatures=400" + chr(10) + "slam.Vis/MaxFeatures=600" + chr(10), "", ""),
    "m_offb": ("room_map=off" + chr(10), "", ""), "m_offc": ("room_map=off" + chr(10), "", ""),
    # the correction against the saved map, forced: at second 30 the app's world is pushed aside (metres along x and
    # z, degrees), as a wrong join after a loss would push it; small pushes must come back without a jump
    "s_8": ("room_map=find" + chr(10) + "map_test_slip=30,0.06,-0.05,0" + chr(10), "", ""), "s_8t": ("room_map=find" + chr(10) + "map_test_slip=30,0.06,-0.05,2" + chr(10), "", ""),
    "s_20": ("room_map=find" + chr(10) + "map_test_slip=30,0.15,0.13,3" + chr(10), "", ""), "s_100": ("room_map=find" + chr(10) + "map_test_slip=30,0.8,-0.6,25" + chr(10), "", ""),
    # the map added to with RTAB-Map merging a new place into the old one it was recognised as (Mem/ReduceGraph)
    "r_new": ("room_map=new" + chr(10) + "slam.Mem/ReduceGraph=true" + chr(10), "", ""), "r_grow": ("slam.Mem/ReduceGraph=true" + chr(10), "", ""), "r_growb": ("slam.Mem/ReduceGraph=true" + chr(10), "", ""),
    # the estimator's time for a frame, without the map: its settings one at a time against "e_base" (as shipped), the
    # estimator's threads ahead of ordinary ones, and what looked best together. Each is judged by its time AND by
    # whether the path stays the same (tools/tracking/vio_loss.py against e_base).
    "e_base": ("room_map=off" + chr(10) + "native.num_threads=2" + chr(10) + "native.thread_nice=0" + chr(10) + "config.vio_max_iterations=7" + chr(10), "", ""), "e_baseb": ("room_map=off" + chr(10) + "native.num_threads=2" + chr(10), "", ""), "e_basec": ("room_map=off" + chr(10) + "native.num_threads=2" + chr(10), "", ""),
    "e_t3": ("room_map=off" + chr(10) + "native.num_threads=3" + chr(10), "", ""), "e_t4": ("room_map=off" + chr(10) + "native.num_threads=4" + chr(10), "", ""),
    "e_nice": ("room_map=off" + chr(10) + "native.thread_nice=-8" + chr(10), "", ""), "e_nice4t": ("room_map=off" + chr(10) + "native.thread_nice=-8" + chr(10) + "native.num_threads=4" + chr(10), "", ""),
    "e_it5": ("room_map=off" + chr(10) + "config.vio_max_iterations=5" + chr(10), "", ""), "e_it4": ("room_map=off" + chr(10) + "config.vio_max_iterations=4" + chr(10), "", ""),
    "e_grid60": ("room_map=off" + chr(10) + "config.optical_flow_detection_grid_size=60" + chr(10), "", ""), "e_grid70": ("room_map=off" + chr(10) + "config.optical_flow_detection_grid_size=70" + chr(10), "", ""),
    "e_of3": ("room_map=off" + chr(10) + "config.optical_flow_max_iterations=3" + chr(10), "", ""), "e_kf5": ("room_map=off" + chr(10) + "config.vio_max_kfs=5" + chr(10), "", ""),
    "e_mix": ("room_map=off" + chr(10) + "native.thread_nice=-8" + chr(10) + "native.num_threads=3" + chr(10) + "config.vio_max_iterations=5" + chr(10), "", ""), "e_mixb": ("room_map=off" + chr(10) + "native.thread_nice=-8" + chr(10) + "native.num_threads=3" + chr(10) + "config.vio_max_iterations=5" + chr(10), "", ""),
    "e_mix2": ("room_map=off" + chr(10) + "native.thread_nice=-8" + chr(10) + "native.num_threads=3" + chr(10) + "config.vio_max_iterations=5" + chr(10) + "config.optical_flow_detection_grid_size=60" + chr(10), "", ""),
    # reliability, by replay: the cameras covered for six seconds (position must come back, and the world must be put
    # back onto the saved map: compare with x_blind_nomap, where nothing puts it back), and sessions in another light
    "x_blind": ("room_map=find" + chr(10) + "map_glide=1" + chr(10) + "test_blind=30,36" + chr(10), "", ""), "x_blind_nomap": ("room_map=off" + chr(10) + "test_blind=30,36" + chr(10), "", ""),
    "x_blind_r": ("room_map=find" + chr(10) + "restart_after_loss=1" + chr(10) + "test_blind=30,36" + chr(10), "", ""), "x_blind15_r": ("room_map=find" + chr(10) + "restart_after_loss=1" + chr(10) + "test_blind=30,45" + chr(10), "", ""),
    "x_blind_r_nomap": ("room_map=off" + chr(10) + "restart_after_loss=1" + chr(10) + "test_blind=30,36" + chr(10), "", ""),
    "x_blind15": ("room_map=find" + chr(10) + "map_glide=1" + chr(10) + "test_blind=30,45" + chr(10), "", ""),
    "x_dim": ("room_map=find" + chr(10) + "test_light=0.6" + chr(10), "", ""), "x_dark": ("room_map=find" + chr(10) + "test_light=0.35" + chr(10), "", ""),
    "x_bright": ("room_map=find" + chr(10) + "test_light=1.6" + chr(10), "", ""),
    # the map added to only where it has no places (the way from 20 November, night) against every place of every session
    "a_grow": ("slam.Ultra/AddAlways=0" + chr(10), "", ""), "a_growb": ("slam.Ultra/AddAlways=0" + chr(10), "", ""), "a_growc": ("slam.Ultra/AddAlways=0" + chr(10), "", ""),
    # the mesh kept with the map, furniture and the dropping of kept surfaces: what it costs and what it finds
    "g_mesh": ("room_map=find" + chr(10) + "room_mesh=1" + chr(10), "", ""), "g_meshb": ("room_map=find" + chr(10) + "room_mesh=1" + chr(10), "", ""),
    # a slip of a metre glided back in place of the jump
    "s_100g": ("room_map=find" + chr(10) + "map_glide=1" + chr(10) + "map_test_slip=30,0.8,-0.6,25" + chr(10), "", ""),
    "a_always": ("slam.Ultra/AddAlways=1" + chr(10), "", ""), "a_alwaysb": ("slam.Ultra/AddAlways=1" + chr(10), "", ""),
    "m_new": ("room_map=new" + chr(10), "", ""), "m_find": ("room_map=find" + chr(10), "", ""), "m_findb": ("room_map=find" + chr(10), "", ""),
    "m_grow": ("", "", ""), "m_growb": ("", "", ""),      # the map is added to (the app's own way from 20 November, late)
    "m_off": ("room_map=off" + chr(10), "", ""),
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

def restart_app(dev):
    """Ends Ultra XR's process so settings files are read afresh, WITHOUT "am force-stop": Android switches off an
    app's accessibility service (the touchpad) whenever the app is force-stopped, and the owner then has to switch
    it on again by hand. Going Home and killing the background process leaves the service enabled; Android restarts it."""
    sh(dev, "input keyevent KEYCODE_HOME"); time.sleep(1); sh(dev, "am kill " + PKG); time.sleep(2)
    # "am kill" leaves a process that holds a service. A debug build's process can be ended as the app itself, which is
    # an ordinary death of the process (Android starts its services again), not a force-stop.
    sh(dev, "run-as %s sh -c 'kill $(pidof %s)' 2>/dev/null" % (PKG, PKG)); time.sleep(3)

def glasses_attached(dev): return "Air 2 Ultra" in sh(dev, "dumpsys display | grep -c 'Air 2 Ultra' ; dumpsys display | grep -m1 'Air 2 Ultra'")

def restore(dev):
    put(dev, "vio_options.txt", ""); put(dev, "hand_options.txt", ""); put(dev, "stereo_rotation_fix.txt", "")
    sh(dev, "am force-stop com.igalia.wolvic.vanilla"); time.sleep(6)
    for p in ("sim_motion", "sim_auto"): sh(dev, "setprop debug.ultraxr.%s 0" % p)
    sh(dev, "setprop debug.xrt.ULTRAXR_DISPLAY_TIMING 0"); sh(dev, "input keyevent KEYCODE_HOME")
    print("restored: test settings removed; screen timeout " + sh(dev, "settings get system screen_off_timeout") + " ms, stay-awake " + sh(dev, "settings get global stay_on_while_plugged_in"))

def replay(dev, name, out, runs):
    os.makedirs(out, exist_ok=True)
    if glasses_attached(dev): print("The glasses are plugged in: a replay needs them unplugged. Nothing was run."); return 1
    if name not in sh(dev, "ls %s/recordings" % FILES): print("no such recording on the phone: " + name); return 1
    for tag in runs:
        vio, hands = RUNS[tag][0], RUNS[tag][1]
        put(dev, "vio_options.txt", vio); put(dev, "hand_options.txt", hands); put(dev, "stereo_rotation_fix.txt", RUNS[tag][2] if len(RUNS[tag]) > 2 else "0,0,0")      # "0,0,0": the factory calibration, whatever the unit's standing correction; "": the app as the owner runs it
        restart_app(dev); sh(dev, "logcat -c")
        sh(dev, "input keyevent KEYCODE_WAKEUP")
        sh(dev, "am start -n %s/.ui.LauncherActivity --es replay %s --es replay_tag %s" % (PKG, name, tag))
        t0 = time.time(); done = ""
        while time.time() - t0 < 420:
            time.sleep(15)
            log = sh(dev, "logcat -d | grep -E 'UltraXR *: (replay finished|replayed|replay stopped|hand options|6DoF settings changed)' | tail -4")
            # a start request that arrives twice is answered "a replay is already running": that is not the end of the run
            log = chr(10).join(l for l in log.splitlines() if "already running" not in l)
            if "replay finished" in log or "replay stopped" in log: done = log; break
        print("%s: %s" % (tag, (done.splitlines()[-1].split("UltraXR :")[-1].strip() if done else "no end message after 7 minutes")), flush=True)
        for kind in ("vio.csv", "hands.csv", "app.csv", "joints.csv", "room.csv", "notes.txt"):
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
        restart_app(dev)
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

def diag3d(dev, out):
    """The app's bounded 3D-mode experiment (GlassesLink.diag3d: three mode commands, 30 s), then the phone's own
    DisplayPort driver log for the same seconds, taken from a bug report (the only place it can be read without root)."""
    import zipfile
    os.makedirs(out, exist_ok=True)
    if not glasses_attached(dev): print("The glasses are not plugged into the phone. Nothing was run."); return 1
    sh(dev, "logcat -c")
    sh(dev, "am start -n %s/.ui.LauncherActivity --ez diag3d true" % PKG)
    t0 = time.time(); log = ""
    while time.time() - t0 < 60:
        time.sleep(5)
        log = sh(dev, "logcat -d | grep -E 'UltraXR *: (diag3d|glasses (log|say|mode)|display [0-9]+ (ADDED|REMOVED)|3D:)'")
        if "diag3d: done" in log or "diag3d: the glasses are not connected" in log: break
    open(os.path.join(out, "diag3d-app.txt"), "w", encoding="utf-8").write(log)
    for line in log.splitlines(): print(line.split("UltraXR :")[-1].strip()[:170])
    print("reading the phone's DisplayPort driver log (a bug report, two to three minutes)...", flush=True)
    z = os.path.join(out, "diag3d-bugreport.zip")
    run(["adb", "-s", dev, "bugreport", z], 600)
    kept = []
    try:
        with zipfile.ZipFile(z) as f:
            name = [n for n in f.namelist() if n.startswith("bugreport-") and n.endswith(".txt")][0]
            for raw in f.open(name):
                line = raw.decode("utf-8", "replace")
                if "dwc_dptx" in line and "TXEQ" not in line and "tx_eq_set" not in line and "PHY lane" not in line and "adjust lane" not in line and "Wait busy" not in line: kept.append(line.rstrip())
        os.remove(z)       # the bug report holds far more than this needs; only the driver's lines are kept
    except Exception as e: print("the bug report could not be read: %s" % e)
    open(os.path.join(out, "diag3d-kernel.txt"), "w", encoding="utf-8").write(chr(10).join(kept))
    def count(word): return sum(1 for l in kept if word in l)
    print("DisplayPort driver lines kept: %d; link trainings finished %d, unplug events %d, plug events %d, mode enables %d" % (
        len(kept), count("LINK TRAINING DONE"), count("HPD_UNPLUG start"), count("HPD_PLUG start"), count("ENABLE MODE")))
    for l in kept:
        if "RECEIVER CAPS" in l or "LINK TRAINING DONE" in l or "ENABLE MODE" in l or "HPD changed" in l or "CR_DONE = 0" in l or "DROP" in l: print("  " + l[l.find("["):][:150])
    return 0

def main():
    a = sys.argv[1:]
    if not a or a[0] not in ("replay", "pacing", "restore", "diag3d"): print(__doc__); return 2
    dev = device()
    if dev is None: print("The Pixel is not reachable over adb. Nothing was done."); return 3
    if a[0] == "restore": restore(dev); return 0
    if a[0] == "pacing": return pacing(dev, a[1])
    if a[0] == "diag3d": return diag3d(dev, a[1])
    runs = list(RUNS)
    if "--runs" in a: i = a.index("--runs"); runs = a[i + 1].split(","); del a[i:i + 2]
    return replay(dev, a[1], a[2], runs)

if __name__ == "__main__":
    sys.exit(main())
