#!/usr/bin/env python3
"""Runs existing third-party WebXR pages in Wolvic on the emulator and records what each one gets.

  python webxr_pages.py [-s SERIAL] [--out DIR] [URL ...]

Same setup as webxr_test.py (emulator, Ultra XR -Pemu build, Wolvic x86-64, stand-in glasses in
half-width 3D, simulated wearer). The pages are not ours and are not changed. Through Wolvic's
developer tools socket the test notes the session a page starts (by wrapping requestSession before
the page's own button is pressed), presses that button as a user gesture, lets the simulated head
move and the hands pinch, and then reads back: the session's mode and blend mode, how many frames
the page rendered, its input sources, hand joints and select events, and a picture of the display.

What this shows: the page ran an immersive session here and was given stereo views, a moving head
pose and hands. What it does not show: that the game is playable or fun, anything about real
tracking, or anything on the phone or the glasses.
"""
import json, os, sys, time
import numpy as np, cv2
import uia, cdp

HERE = os.path.dirname(os.path.abspath(__file__))
PKG = "dev.ultraxr"; BROWSER = "com.igalia.wolvic/com.igalia.wolvic.VRBrowserActivity"

DEFAULT = [
    ("three.js hand input: pinch to make cubes", "https://threejs.org/examples/webxr_vr_handinput_cubes.html"),
    ("three.js XR dragging: pick things up with a ray", "https://threejs.org/examples/webxr_xr_dragging.html"),
    ("three.js XR ball shooter: physics", "https://threejs.org/examples/webxr_xr_ballshooter.html"),
    ("three.js AR cones: place objects in the room", "https://threejs.org/examples/webxr_ar_cones.html"),
    ("A-Frame hand tracking showcase", "https://aframe.io/aframe/examples/showcase/hand-tracking/"),
    ("Moon Rider (rhythm game)", "https://moonrider.xyz/"),
]

HOOK = r"""
(() => {
  if (window.__uxr) return 'already';
  const S = window.__uxr = { asked: [], mode: null, error: null, blend: null, frames: 0, views: 0, select: 0, squeeze: 0, inputs: [], hands: 0, joints: 0, yawMin: null, yawMax: null, ended: false };
  if (!navigator.xr) return 'no navigator.xr';
  const real = navigator.xr.requestSession.bind(navigator.xr);
  navigator.xr.requestSession = async (mode, init) => {
    S.asked.push([mode, init ? { required: init.requiredFeatures || [], optional: init.optionalFeatures || [] } : null]);
    let s;
    try { s = await real(mode, init); } catch (e) { S.error = String(e); throw e; }
    S.mode = mode; S.blend = s.environmentBlendMode; S.session = s;
    try { S.features = s.enabledFeatures ? Array.from(s.enabledFeatures) : null; } catch (e) {}
    s.addEventListener('select', () => S.select++); s.addEventListener('squeeze', () => S.squeeze++);
    s.addEventListener('end', () => { S.ended = true; });
    let space = null; s.requestReferenceSpace('local').then(r => { space = r; }).catch(() => {});
    const realRaf = s.requestAnimationFrame.bind(s);
    s.requestAnimationFrame = cb => realRaf((t, f) => {
      S.frames++;
      try {
        if (space) {
          const p = f.getViewerPose(space);
          if (p) { S.views = p.views.length; const o = p.transform.orientation; const yaw = Math.atan2(2 * (o.w * o.y + o.x * o.z), 1 - 2 * (o.y * o.y + o.x * o.x)); S.yawMin = Math.min(S.yawMin ?? yaw, yaw); S.yawMax = Math.max(S.yawMax ?? yaw, yaw); }
          let hands = 0, joints = 0; const list = [];
          for (const src of s.inputSources) {
            list.push([src.handedness, src.targetRayMode, !!src.hand, !!src.gamepad]);
            if (src.hand && f.getJointPose) { hands++; let n = 0; for (const [, sp] of src.hand) if (f.getJointPose(sp, space)) n++; joints = Math.max(joints, n); }
          }
          S.inputs = list; S.hands = Math.max(S.hands, hands); S.joints = Math.max(S.joints, joints);
        }
      } catch (e) { S.hookError = String(e); }
      return cb(t, f);
    });
    return s;
  };
  return 'hooked';
})()
"""

READ = "(() => { const S = window.__uxr; if (!S) return null; const o = {}; for (const k in S) if (k !== 'session') o[k] = S[k]; return o; })()"
# the button a page offers to enter XR: three.js's own, then anything that says so
BUTTON = """(() => { const b = document.querySelector('#XRButton, #VRButton, #ARButton, .a-enter-vr-button, .a-enter-ar-button, [data-enter-vr], #enterVRButton, .enter-vr, #vrButton') || Array.from(document.querySelectorAll('button, a')).find(e => /enter (vr|xr|ar)|start (vr|xr|ar)|^play|^start/i.test(e.textContent.trim()));
  if (!b) return 'no button'; const t = (b.textContent.trim() || b.className || b.id || b.tagName); b.click(); return 'pressed: ' + String(t).slice(0, 40); })()"""

def restart_emulator(avd="uxr"):
    """Kills the emulator and boots it again. Stopping a browser in the middle of an immersive session can hang
    the emulator's graphics pipe (adb then stops answering), so each page gets a fresh boot."""
    import subprocess
    subprocess.run(["powershell", "-NoProfile", "-Command",
        "Get-Process qemu-system-x86_64, emulator -ErrorAction SilentlyContinue | Stop-Process -Force -Confirm:$false; Start-Sleep 3; "
        "Remove-Item 'C:/uvio/avd/%s.avd/*.lock' -Recurse -Force -Confirm:$false -ErrorAction SilentlyContinue" % avd], capture_output=True, timeout=120)
    env = dict(os.environ, ANDROID_AVD_HOME=os.environ.get("ANDROID_AVD_HOME", "C:/uvio/avd"), ANDROID_SDK_ROOT=os.environ.get("ANDROID_SDK_ROOT", "C:/Android"))
    subprocess.Popen([os.path.join(env["ANDROID_SDK_ROOT"], "emulator", "emulator.exe"), "-avd", avd, "-no-snapshot-save", "-no-boot-anim", "-gpu", "host", "-memory", "4096", "-cores", "6"],
                     env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0))
    for _ in range(90):
        time.sleep(4)
        try:
            if uia.adb("shell", "getprop sys.boot_completed", timeout=10).strip() == "1": break
        except Exception: pass
    time.sleep(10)

def setup():
    for perm in ("CAMERA", "POST_NOTIFICATIONS"): uia.sh("pm grant %s android.permission.%s" % (PKG, perm))
    uia.adb("emu", "multidisplay", "del", "1"); time.sleep(2)
    uia.adb("emu", "multidisplay", "add", "1", "1920", "1080", "240", "0"); time.sleep(4)
    uia.sh("setprop debug.xrt.XRT_COMPOSITOR_LOG error")
    if MODEL: uia.sh("setprop debug.xr.vendor.model.x " + MODEL)
    uia.sh("am start -n %s/.ui.LauncherActivity --ez force_half_stereo true --ez sim_wearer true" % PKG); time.sleep(6)
    uia.adb("forward", "tcp:%d" % cdp.PORT, "localabstract:content_shell_devtools_remote")

MODEL = ""     # --model NAME: for a test copy of the browser that reads its device model from debug.xr.vendor.model.x

def grab(out, name):
    d = os.path.join(out, "shots"); os.makedirs(d, exist_ok=True)
    for f in os.listdir(d): os.remove(os.path.join(d, f))
    uia.adb("emu", "screenrecord", "screenshot", "--display", "1", d.replace(os.sep, "/")); time.sleep(2)
    fs = sorted(os.listdir(d))
    if not fs: return None
    img = cv2.imread(os.path.join(d, fs[-1])); cv2.imwrite(os.path.join(out, name), img); return img

def main():
    a = sys.argv[1:]; out = os.path.join(HERE, "out")
    if "-s" in a: i = a.index("-s"); uia.SERIAL = a[i + 1]; del a[i:i + 2]
    if "--out" in a: i = a.index("--out"); out = a[i + 1]; del a[i:i + 2]
    global MODEL
    if "--model" in a: i = a.index("--model"); MODEL = a[i + 1]; del a[i:i + 2]
    os.makedirs(out, exist_ok=True)
    targets = [(u, u) for u in a] or DEFAULT
    bpkg = BROWSER.split("/")[0]
    rows = []
    for n, (name, url) in enumerate(targets):
        restart_emulator(); setup()
        for k, v in (("sim_motion", "0"), ("sim_auto", "0"), ("sim_look", "0,0"), ("sim_aim", "0,0")): uia.sh("setprop debug.ultraxr.%s %s" % (k, v))
        uia.sh("am start -n %s -a android.intent.action.VIEW -d '%s'" % (BROWSER, url))
        key = url.split("//", 1)[-1].split("?")[0]
        row = {"name": name, "url": url}
        ok = False
        for _ in range(40):         # wait for the page to load
            time.sleep(3)
            try:
                st = cdp.evaluate(key, "document.readyState + '|' + !!navigator.xr + '|' + document.title")
                if isinstance(st, str) and st.startswith("complete"): ok = True; row["title"] = st.split("|", 2)[2][:60]; break
            except BaseException: pass
        if not ok:
            row["result"] = "page did not load"; rows.append(row); print(json.dumps(row), flush=True); continue
        time.sleep(4)
        try:
            row["hook"] = cdp.evaluate(key, HOOK)
            row["button"] = cdp.evaluate(key, BUTTON, gesture=True, await_promise=False)
        except BaseException as e: row["result"] = "developer tools: " + repr(e)[:80]
        time.sleep(10)
        uia.sh("setprop debug.ultraxr.sim_motion 1"); uia.sh("setprop debug.ultraxr.sim_auto 1")
        time.sleep(22)
        try: s = cdp.evaluate(key, READ) or {}
        except BaseException as e: s = {}; row["result"] = "developer tools: " + repr(e)[:80]
        row.update({k: s.get(k) for k in ("asked", "mode", "error", "blend", "features", "frames", "views", "inputs", "hands", "joints", "select", "squeeze", "hookError")})
        if s.get("yawMin") is not None: row["yaw_range"] = round(s["yawMax"] - s["yawMin"], 3)
        img = grab(out, "page-%d.png" % (n + 1))
        if img is not None and img.shape[1] == 1920:
            g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32); L = g[:, :960]; R = g[:, 960:]
            row["lit"] = [round(float((L > 40).mean()), 3), round(float((R > 40).mean()), 3)]
            pa = L.mean(axis=0) - L.mean(); pb = R.mean(axis=0) - R.mean()
            if np.linalg.norm(pa) > 1 and np.linalg.norm(pb) > 1:
                best = (-9, 0)
                for sft in range(-120, 121):
                    x = pa[max(0, sft):960 + min(0, sft)]; y = pb[max(0, -sft):960 + min(0, -sft)]
                    c = float((x * y).sum() / (np.linalg.norm(x) * np.linalg.norm(y) + 1e-9))
                    if c > best[0]: best = (c, sft)
                row["halves_match"] = round(best[0], 3); row["halves_shift_px"] = best[1]
        rows.append(row); print(json.dumps(row), flush=True)
    json.dump(rows, open(os.path.join(out, "webxr-pages.json"), "w"), indent=1)
    print("\n%-52s %-13s %-9s %6s %5s %-22s %6s" % ("page", "mode", "blend", "frames", "views", "hands/joints/select", "yaw"))
    for r in rows:
        print("%-52s %-13s %-9s %6s %5s %-22s %6s" % (r["name"][:52], r.get("mode") or r.get("result") or r.get("error") or "no session", r.get("blend"), r.get("frames"), r.get("views"),
              "%s/%s/%s" % (r.get("hands"), r.get("joints"), r.get("select")), r.get("yaw_range")))
    return 0

if __name__ == "__main__":
    sys.exit(main())
