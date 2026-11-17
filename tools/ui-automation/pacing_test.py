#!/usr/bin/env python3
"""Frame pacing of the OpenXR compositor, measured from Android's own record of when frames reached the display.

  python pacing_test.py [-s SERIAL] [--seconds N] [--modes own,display] [--out DIR] [--no-restart]

For each pacing mode the browser is started (its XR world is drawn every frame), left running, and then two things
are read:

  * SurfaceFlinger's frame history for the compositor's layer (dumpsys SurfaceFlinger --latency): the times frames
    were actually presented. A gap of two refresh periods between neighbours is a frame the display showed twice:
    a real dropped frame, whatever the runtime thought.
  * the runtime's own "missed frame" warnings, which say only that the compositor woke later than it planned to.

Modes: "own" = the compositor paces by its own clock (what shipped until now); "display" = it paces by the present
times the display reports (debug.xrt.ULTRAXR_DISPLAY_TIMING=1). The property is read when the runtime starts, so
the app is stopped between modes. On the emulator this checks that both modes work and gives numbers for the
emulator only; the same script with -s <phone> gives the phone's.
"""
import json, os, re, sys, time
import numpy as np
import uia
import webxr_pages as wp

def layer_name(hint=None):
    """The layer the compositor presents to: Ultra XR's window on the glasses, or, with no glasses, the
    application's own surface on the phone ([hint] is part of that application's name)."""
    out = uia.sh("dumpsys SurfaceFlinger --list")
    names = [l.strip() for l in out.splitlines() if "Ultra XR OpenXR application" in l and "SurfaceView" in l]
    if not names and hint: names = [l.strip() for l in out.splitlines() if hint in l and "SurfaceView" in l]
    names = [re.sub(r"^RequestedLayerState\{(.*?)( parentId=.*)?\}?$", r"\1", n) for n in names]
    blast = [n for n in names if "BLAST" in n]
    return (blast or names or [None])[0]

def latency(name):
    """Actual present times (ns) of the layer's recent frames, and the refresh period."""
    out = uia.sh("dumpsys SurfaceFlinger --latency '%s'" % name)
    lines = [l.split() for l in out.strip().splitlines()]
    if not lines or len(lines[0]) != 1: return None, []
    period = int(lines[0][0]); t = []
    for l in lines[1:]:
        if len(l) == 3 and l[1].isdigit():
            v = int(l[1])
            if 0 < v < (1 << 62): t.append(v)
    return period, t

def measure(seconds, layer_hint=None):
    name = None
    for _ in range(20):
        name = layer_name(layer_hint)
        if name: break
        time.sleep(2)
    if not name: return {"error": "the compositor's layer was not found"}
    uia.sh("dumpsys SurfaceFlinger --latency-clear '%s'" % name); uia.sh("logcat -c")
    seen = {}; period = None; t0 = time.time()
    while time.time() - t0 < seconds:
        time.sleep(1.5)
        p, t = latency(name)
        if p: period = p
        for v in t: seen[v] = 1
    ts = np.array(sorted(seen), dtype=np.int64)
    log = uia.sh("logcat -d | grep -E 'missed frame|compositor pacing by|Created pacer|pacing to the display'", timeout=120)
    late = [float(m) for m in re.findall(r"missed frame by ([0-9.]+)ms", log)]
    r = {"layer": name, "refresh_period_ms": period / 1e6 if period else None, "frames_presented": int(len(ts)), "seconds": seconds,
         "runtime_late_warnings": len(late), "runtime_late_median_ms": float(np.median(late)) if late else 0.0,
         "pacer": [l.split(": ", 1)[-1][:110] for l in log.splitlines() if "pacing by" in l or "Created pacer" in l or "pacing to the display" in l][:3]}
    if len(ts) > 10 and period:
        d = np.diff(ts) / period
        r.update({"presented_per_second": float(len(ts) / ((ts[-1] - ts[0]) / 1e9)),
                  "intervals": int(len(d)), "on_time_share": float((d < 1.5).mean()),
                  "shown_twice": int(((d >= 1.5) & (d < 2.5)).sum()), "shown_three_times_or_more": int((d >= 2.5).sum()),
                  "interval_ms_p99": float(np.percentile(d, 99) * period / 1e6), "interval_ms_max": float(d.max() * period / 1e6),
                  "interval_jitter_ms_std": float(np.std(np.diff(ts)[d < 1.5]) / 1e6) if (d < 1.5).sum() > 2 else None})
    return r

def main():
    a = sys.argv[1:]; out = os.path.join(wp.HERE, "out-pacing"); seconds = 40; modes = ["own", "display"]; restart = True
    if "-s" in a: i = a.index("-s"); uia.SERIAL = a[i + 1]; del a[i:i + 2]
    if "--out" in a: i = a.index("--out"); out = a[i + 1]; del a[i:i + 2]
    if "--seconds" in a: i = a.index("--seconds"); seconds = int(a[i + 1]); del a[i:i + 2]
    if "--modes" in a: i = a.index("--modes"); modes = a[i + 1].split(","); del a[i:i + 2]
    if "--no-restart" in a: a.remove("--no-restart"); restart = False
    os.makedirs(out, exist_ok=True); results = {}
    for mode in modes:
        if restart: wp.restart_emulator()
        wp.setup()
        uia.sh("setprop debug.xrt.ULTRAXR_DISPLAY_TIMING %d" % (1 if mode == "display" else 0))
        uia.sh("setprop debug.xrt.XRT_COMPOSITOR_LOG warn")
        uia.sh("setprop debug.ultraxr.sim_motion 1")
        uia.sh("am start -n %s" % wp.BROWSER); time.sleep(25)
        r = measure(seconds); results[mode] = r
        print(mode + ": " + json.dumps(r), flush=True)
        uia.sh("am force-stop " + wp.BROWSER.split("/")[0]); time.sleep(8); uia.sh("am force-stop " + wp.PKG); time.sleep(3)
    json.dump(results, open(os.path.join(out, "pacing.json"), "w"), indent=1)
    return 0

if __name__ == "__main__":
    sys.exit(main())
