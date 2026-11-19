#!/usr/bin/env python3
# Ultra XR: look inside a Quest game's APK and say, before it is run, what it will ask for that is not there.
# SPDX-License-Identifier: GPL-3.0-or-later
"""
  python tools/quest/quest_inspect.py GAME.apk [--out report.txt]

Reads the APK only; nothing is changed, installed or run, and no check inside the game is touched. It reports:

  - the package, version, and what its manifest says it needs (Quest features, permissions, split APKs, OBB);
  - the engine (Unity, Unreal, ...), the 64-bit libraries, and Meta's own libraries (OVRPlugin, the platform SDK,
    the old VrApi);
  - whether it has been converted (a dispatcher as libopenxr_loader.so) or is as bought;
  - every OpenXR extension named anywhere in its code, sorted into: answered by Ultra XR's layer, offered by the
    runtime, and offered by nobody (the ones to worry about), with what each missing one means for the game;
  - the blockers that can be known without running it, and the two commands to run next.

The runtime's list is tools/quest/runtime_extensions.txt (written by quest_diag.py from a real run on the phone).
The dispatcher (tools/quest/prebuilt) offers a converted game Quest's own list on top of that and answers some of
it itself; the names it knows are read from its library.
An extension named in a game's code is not always used: engines name every extension they know. Whether a missing
one matters is known for sure only from a run (quest_diag.py).
"""
import json, os, re, subprocess, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__)); REPO = os.path.dirname(os.path.dirname(HERE))
LAYER_JSON = os.path.join(REPO, "questlab", "src", "main", "assets", "openxr", "1", "api_layers", "implicit.d", "XrApiLayer_ultraxr_meta.json")
RUNTIME_LIST = os.path.join(HERE, "runtime_extensions.txt")
DISPATCHER = os.path.join(HERE, "prebuilt", "arm64-v8a", "libopenxr_loader.so")
NOT_THE_GAME = ("libopenxr_loader.so", "libopenxr_loader_generic.so", "libuxr_meta_layer.so")      # loaders and our layer name every extension they know

# What a missing extension means for a game, where that is known. Anything not listed is reported without a comment.
MEANING = {
    "XR_META_environment_depth": "room occlusion (answered for OpenGL ES games; a Vulkan game is told 'unsupported')",
    "XR_FB_passthrough": "the real world behind the game (answered: the glasses are see-through)",
    "XR_FB_triangle_mesh": "passthrough shown on a game's own surface: not drawn, the world is seen wherever the game draws nothing",
    "XR_FB_render_model": "Meta's controller models: a game that draws them has none to draw",
    "XR_FB_keyboard_tracking": "tracked physical keyboard: not there",
    "XR_FB_body_tracking": "body tracking: no body is tracked",
    "XR_FB_face_tracking2": "face tracking: none",
    "XR_FB_eye_tracking_social": "eye tracking: none",
    "XR_FB_space_warp": "application space warp: frames are shown as drawn",
    "XR_FB_foveation": "fixed foveation: accepted where the runtime lists it, changes nothing",
    "XR_META_colocation_discovery": "finding nearby headsets: none",
    "XR_META_spatial_entity_sharing": "shared anchors: no service behind them",
    "XR_META_spatial_entity_persistence": "the newer anchor save calls: only XR_FB_spatial_entity_storage is answered",
    "XR_META_spatial_entity_discovery": "the newer scene query calls: only XR_FB_spatial_entity_query is answered; a game using only these sees no room",
    "XR_META_virtual_keyboard": "Meta's virtual keyboard: none",
    "XR_META_passthrough_color_lut": "passthrough colour grading: nothing to grade",
    "XR_META_dynamic_object_tracker": "tracked keyboards and the like: none",
    "XR_META_hand_tracking_microgestures": "thumb micro-gestures: none",
    "XR_OCULUS_audio_device_guid": "Windows only; harmless",
    "XR_KHR_vulkan_enable": "the older Vulkan binding",
    "XR_FB_composition_layer_depth_test": "depth-tested layers: layers are composed in order",
    "XR_KHR_composition_layer_depth": "depth submitted with frames: used for reprojection only",
}

def build_tools():
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), "C:/Android", os.path.expanduser("~/Android/Sdk"), os.path.expanduser("~/AppData/Local/Android/Sdk")):
        d = os.path.join(root, "build-tools") if root else None
        if d and os.path.isdir(d):
            v = sorted(os.listdir(d))
            if v: return os.path.join(d, v[-1])
    return None

def manifest_tree(apk):
    bt = build_tools()
    if not bt: return ""
    exe = os.path.join(bt, "aapt2" + (".exe" if os.name == "nt" else ""))
    r = subprocess.run([exe, "dump", "xmltree", "--file", "AndroidManifest.xml", apk], capture_output=True)
    return r.stdout.decode("utf-8", "replace")

def attr(line):
    m = re.search(r'A: (?:http://schemas\.android\.com/apk/res/android:)?(\w+)(?:\(0x[0-9a-f]+\))?=(?:"([^"]*)"|(\S+))', line)
    return (m.group(1), m.group(2) if m.group(2) is not None else m.group(3)) if m else (None, None)

def read_manifest(tree):
    """A light reading of aapt2's xmltree: the elements we care about with their attributes."""
    out = {"package": "?", "version": "?", "features": [], "permissions": [], "meta": {}, "categories": [], "libs": [], "splits": False, "min": "?", "target": "?"}
    cur = None; attrs = {}
    def close():
        nonlocal cur, attrs
        if cur == "manifest": out["package"] = attrs.get("package", "?"); out["version"] = "%s (%s)" % (attrs.get("versionName", "?"), attrs.get("versionCode", "?")); out["splits"] = "requiredSplitTypes" in attrs or attrs.get("isSplitRequired") in ("true", "0xffffffff")
        elif cur == "uses-sdk": out["min"] = attrs.get("minSdkVersion", "?"); out["target"] = attrs.get("targetSdkVersion", "?")
        elif cur == "uses-feature": out["features"].append((attrs.get("name") or ("glEsVersion " + attrs.get("glEsVersion", "?")), attrs.get("required", "true")))
        elif cur == "uses-permission": out["permissions"].append(attrs.get("name", "?"))
        elif cur == "meta-data": out["meta"][attrs.get("name", "?")] = attrs.get("value", attrs.get("resource", ""))
        elif cur == "category": out["categories"].append(attrs.get("name", "?"))
        elif cur == "uses-native-library": out["libs"].append((attrs.get("name", "?"), attrs.get("required", "true")))
        cur = None; attrs = {}
    for line in tree.splitlines():
        s = line.strip()
        if s.startswith("E: "):
            close(); cur = s[3:].split(" ")[0]
        elif s.startswith("A: ") and cur:
            k, v = attr(s)
            if k: attrs[k] = v
    close()
    return out

EXT_RE = re.compile(rb"XR_(?:KHR|EXT|FB|META|OCULUS|METAX1|METAX2|MSFT|HTC|HTCX|BD|PICO|ML|VARJO|VALVE|QCOM|ANDROID|ANDROIDX|EXTX|MND|MNDX|ULTRALEAP|HUAWEI|ALMALENCE|LOGITECH|YVR|OPPO)_[a-z0-9_]{3,60}")
ENUM_NOISE = re.compile(r"_(?:SPEC_VERSION|EXTENSION_NAME)$")

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args: print(__doc__); return 2
    apk = args[0]; out_path = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else None
    lines = []
    def say(s=""): lines.append(s)
    z = zipfile.ZipFile(apk); names = z.namelist()
    man = read_manifest(manifest_tree(apk))
    say("Quest game inspection: %s (%.0f MB)" % (os.path.basename(apk), os.path.getsize(apk) / 1e6))
    say("  package %s, version %s, minSdk %s, targetSdk %s" % (man["package"], man["version"], man["min"], man["target"]))
    blockers, warnings = [], []

    # ---- libraries, engine
    libs64 = sorted(n[len("lib/arm64-v8a/"):] for n in names if n.startswith("lib/arm64-v8a/") and n.endswith(".so"))
    libs32 = [n for n in names if n.startswith("lib/armeabi-v7a/")]
    if not libs64:
        blockers.append("no 64-bit (arm64-v8a) libraries%s: it cannot run on the phone" % (" (32-bit only)" if libs32 else ""))
    engine = "unknown"
    if "libunity.so" in libs64:
        engine = "Unity (%s)" % ("IL2CPP" if "libil2cpp.so" in libs64 else "Mono")
        try:
            data = z.read("lib/arm64-v8a/libunity.so"); m = re.search(rb"(20\d\d\.\d+\.\d+[abfp]\d+|6000\.\d+\.\d+[abfp]\d+)", data)
            if m: engine += " " + m.group(1).decode()
        except Exception: pass
    elif any(l in libs64 for l in ("libUE4.so", "libUnreal.so")): engine = "Unreal Engine"
    elif any("godot" in l for l in libs64): engine = "Godot"
    say("  engine: %s" % engine)
    say("  64-bit libraries (%d): %s" % (len(libs64), " ".join(libs64)))
    meta_libs = {"libOVRPlugin.so": "Meta's OVRPlugin (the Unity/Unreal bridge to OpenXR on Quest)", "libovrplatformloader.so": "Meta's platform SDK (entitlement, user, achievements)",
                 "libvrapi.so": "the old VrApi (not OpenXR)", "libVrApi.so": "the old VrApi (not OpenXR)", "libOVRLipSync.so": "lip sync", "libMetaXRAudioUnity.so": "Meta XR audio", "libInteractionSdk.so": "Meta's Interaction SDK",
                 "libmetaxrsimulator.so": "Meta XR simulator", "libpxr_api.so": "Pico's SDK", "libopenxr_forwardloader.so": "Meta's OpenXR forward loader"}
    for l, what in meta_libs.items():
        if l in libs64: say("    has %s: %s" % (l, what))
    if any(l.lower() == "libvrapi.so" for l in libs64) and "libopenxr_loader.so" not in libs64 and "libOVRPlugin.so" not in libs64:
        blockers.append("the game talks to the old VrApi and has no OpenXR loader: there is no runtime for that here")
    if "libovrplatformloader.so" in libs64:
        warnings.append("it has Meta's platform SDK: on start it asks Meta's services whether the user owns it. Nothing on the phone answers that, and nothing in Ultra XR removes or fakes the check. What the game does when the answer does not come (go on, or quit) is seen in the run.")

    # ---- converted or as bought
    loader = z.read("lib/arm64-v8a/libopenxr_loader.so") if "libopenxr_loader.so" in libs64 else b""
    if not loader: state = "no libopenxr_loader.so of its own (the engine's plugin loads the system's; on Quest that is Meta's runtime)"
    elif b"libopenxr_loader_generic.so" in loader: state = "CONVERTED: libopenxr_loader.so is a dispatcher%s" % (" and the generic loader is there" if "libopenxr_loader_generic.so" in libs64 else "; the generic loader is missing (quest_pack.py adds it)")
    elif b"XR_APILAYER" in loader or b"api_layers" in loader: state = "has the Khronos loader as libopenxr_loader.so (an ordinary OpenXR app, or converted another way)"
    else: state = "libopenxr_loader.so is not a dispatcher and not the Khronos loader (Meta's own, most likely): AS BOUGHT, not converted"
    say("  loader: %s" % state)
    if "CONVERTED" not in state and "Khronos" not in state:
        blockers.append("not converted: as it is, it looks for Meta's runtime. quest_pack.py takes a converted game (OVRPort's output); converting is the owner's step.")
    if "assets/openxr/1/api_layers/implicit.d/XrApiLayer_ultraxr_meta.json" in names: say("  Ultra XR's layer is already packed in")

    # ---- manifest
    say(""); say("Manifest")
    cats = [c for c in man["categories"] if "oculus" in c or "openxr" in c or "LAUNCHER" in c]
    say("  activity categories: %s" % (" ".join(sorted(set(cats))) or "none of note"))
    if "com.oculus.intent.category.VR" not in man["categories"] and "org.khronos.openxr.intent.category.IMMERSIVE_HMD" not in man["categories"]:
        warnings.append("its activity has neither Quest's VR category nor OpenXR's immersive category: Ultra XR's launcher will not list it as a game (start it with adb or quest_diag.py)")
    for name, req in man["features"]:
        note = ""
        if name == "android.hardware.vr.headtracking": note = "  (the phone does not declare this; Android installs anyway with adb)"
        if name and name.startswith("oculus.software") or (name and name.startswith("com.oculus")): note = "  (a Quest feature: used by the game only if it asks the system about it)"
        say("  uses-feature %s required=%s%s" % (name, req, note))
    for k in sorted(man["meta"]):
        if any(t in k.lower() for t in ("oculus", "meta", "unity", "openxr", "pico", "vr")): say("  meta-data %s = %s" % (k, man["meta"][k]))
    for name, req in man["libs"]: say("  uses-native-library %s required=%s" % (name, req))
    perms = [p for p in man["permissions"] if "oculus" in p or "horizonos" in p or "openxr" in p]
    if perms: say("  Quest permissions: %s" % " ".join(perms))
    if "com.oculus.permission.USE_SCENE" in man["permissions"] or "horizonos.permission.USE_SCENE" in man["permissions"]: say("    USE_SCENE: on Quest the user grants room data; here the layer gives the room without asking (the permission does not exist on the phone, so a game that checks it with Android may think it was refused)")
    if man["splits"]: warnings.append("the manifest says split APKs are required: install all of them together (adb install-multiple), each packed and signed with the same key")
    obb_hint = any(n.endswith(".obb") for n in names) or man["meta"].get("unity.splash-enable") is None and False
    unity_split = "assets/bin/Data/data.unity3d" not in names and engine.startswith("Unity") and not any(n.startswith("assets/bin/Data/level") or n.startswith("assets/bin/Data/globalgamemanagers") for n in names)
    if unity_split: warnings.append("Unity's data is not in this APK: it expects an OBB file (Android/obb/%s/main.<versionCode>.%s.obb) or asset packs; without it the game shows nothing" % (man["package"], man["package"]))

    # ---- extensions named in the code
    layer = set(e["name"] for e in json.load(open(LAYER_JSON))["api_layer"]["instance_extensions"]) if os.path.isfile(LAYER_JSON) else set()
    runtime = set(open(RUNTIME_LIST).read().split()) - layer if os.path.isfile(RUNTIME_LIST) else set()
    found = {}
    scan = [n for n in names if n.startswith("lib/arm64-v8a/") and n.endswith(".so") and os.path.basename(n) not in NOT_THE_GAME] + [n for n in names if n.endswith("global-metadata.dat")]
    dispatcher = set(m.decode() for m in EXT_RE.findall(open(DISPATCHER, "rb").read())) - layer - runtime if os.path.isfile(DISPATCHER) else set()
    for n in scan:
        try: data = z.read(n)
        except Exception: continue
        for m in set(EXT_RE.findall(data)):
            e = m.decode()
            if ENUM_NOISE.search(e) or e.upper() == e: continue
            found.setdefault(e, set()).add(os.path.basename(n))
    # names that are only a prefix of another found name are fragments of it
    ext = sorted(found)
    say(""); say("OpenXR extensions named in the game's code: %d" % len(ext))
    if not runtime: say("  (tools/quest/runtime_extensions.txt is missing: run quest_diag.py once on the phone to write it; until then 'offered by nobody' cannot be told from 'offered by the runtime')")
    by = {"layer": [], "runtime": [], "dispatcher": [], "nobody": []}
    for e in ext: by["layer" if e in layer else "runtime" if e in runtime else "dispatcher" if e in dispatcher else "nobody"].append(e)
    say("  answered by Ultra XR's layer (%d): %s" % (len(by["layer"]), " ".join(x[3:] for x in by["layer"]) or "none"))
    say("  offered by the runtime (%d): %s" % (len(by["runtime"]), " ".join(x[3:] for x in by["runtime"]) or "none"))
    say("  known to the dispatcher only (%d): it offers them to the game, answers a few itself (colour space, refresh rate,\n    performance levels) and leaves the rest out; calls into those answer 'unsupported':" % len(by["dispatcher"]))
    for e in by["dispatcher"]: say("    %-52s in %s%s" % (e, ",".join(sorted(found[e]))[:60], ("   -> " + MEANING[e]) if e in MEANING else ""))
    say("  known to NOBODY (%d): the game cannot enable these (they are not offered to it):" % len(by["nobody"]))
    for e in by["nobody"]: say("    %-52s in %s%s" % (e, ",".join(sorted(found[e]))[:60], ("   -> " + MEANING[e]) if e in MEANING else ""))
    say("  Which of these the game really enables and calls is in the run's log (quest_diag.py).")
    if "XR_META_spatial_entity_discovery" in found and "XR_FB_spatial_entity_query" not in found: blockers.append("the game reads the room only through XR_META_spatial_entity_discovery, which nobody answers: it will see no room")
    if "XR_META_environment_depth" in found: warnings.append("it names XR_META_environment_depth: occlusion by the room works if the game draws with OpenGL ES; a Vulkan game is told 'unsupported' and draws without occlusion (if it handles the refusal)")
    hands = "XR_EXT_hand_tracking" in found; say("  hand tracking named: %s; scene (XR_FB_scene) named: %s; passthrough named: %s" % ("yes" if hands else "no", "yes" if "XR_FB_scene" in found else "no", "yes" if "XR_FB_passthrough" in found else "no"))

    # ---- graphics
    vk = [f for f, _ in man["features"] if f and "vulkan" in f]
    say(""); say("Graphics: %s" % ("the manifest asks for Vulkan (%s)" % ", ".join(vk) if vk else "the manifest does not say; engines carry both. The run's log has the line 'xrCreateSession with Vulkan' or 'with OpenGL ES'."))

    say(""); say("Blockers known before a run: %d" % len(blockers))
    for b in blockers: say("  BLOCKER  " + b)
    say("To watch for in the run: %d" % len(warnings))
    for w in warnings: say("  WATCH    " + w)
    say(""); say("Next:")
    say("  python tools/quest/quest_pack.py %s OUT.apk --install SERIAL" % os.path.basename(apk))
    say("  python tools/quest/quest_diag.py %s --serial SERIAL --seconds 60" % man["package"])
    text = "\n".join(lines); print(text)
    if out_path: open(out_path, "w", encoding="utf-8").write(text + "\n")
    return 1 if blockers else 0

if __name__ == "__main__": sys.exit(main())
