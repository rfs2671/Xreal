#!/usr/bin/env python3
# Ultra XR: make a converted Quest game (or any OpenXR app) ready for Ultra XR's runtime, and install it.
# SPDX-License-Identifier: GPL-3.0-or-later
"""
  python tools/quest/quest_pack.py GAME.apk OUT.apk [--install SERIAL] [--dispatcher-front | --plain-openxr] [--kit questlab.apk]

What it does to the APK (nothing else in it is touched):

  - adds Ultra XR's Meta-extension layer (lib/arm64-v8a/libuxr_meta_layer.so and its manifest under
    assets/openxr/1/api_layers/implicit.d/), which answers passthrough, the Scene API, anchors, hand aim and the
    rest (docs/QUEST_COMPAT.md) from Ultra XR's room model;
  - makes sure the loader chain is the one a converted Quest game uses:
        libopenxr_loader.so          the open dispatcher (tools/quest/prebuilt, MIT)
        libopenxr_loader_generic.so  the Khronos loader, which finds Ultra XR's runtime and loads the layer
    An APK that already has both is left as it is. One that has the dispatcher but no generic loader gets the
    Khronos loader. One whose libopenxr_loader.so is some other loader (Meta's, or the Khronos one) keeps it, unless
    --dispatcher-front is given: then that loader becomes the generic one and the dispatcher goes in front
    (right only when the loader is the Khronos one, as in the test app);
  - removes the old signature, aligns the APK (native libraries on page boundaries) and signs it with the Android
    debug key, so it installs with adb. The original app must be uninstalled first if it was signed with another key.

--plain-openxr is for an app built for Quest with the ordinary OpenXR loader and no Meta bridge library in the way
(Unity's OpenXR plug-in with the Meta OpenXR package, Godot, native samples). Such an app needs no conversion, but
two things keep it from finding a runtime on a phone, and both are put right:
  - its loader is as old as the app and looks for the runtime only where a headset maker installs it; the current
    Khronos loader from the kit takes its place, which asks Android's runtime broker;
  - an app built for Quest does not declare that it wants to see the broker, and from Android 11 on an app that
    targets API 30 or more sees only what it declares. Its target API in the manifest is lowered to 29, where
    everything is visible. Nothing else in the manifest is touched.

What it does NOT do: it does not convert a Quest game. A Quest game as bought talks to Meta's runtime and Meta's
platform services; turning it into an OpenXR app that asks for the dispatcher is OVRPort's job, and whether to run
OVRPort on a game (it removes some of the game's ownership checks) is for the game's owner to decide. Give this tool
OVRPort's output. Games that come as several APKs (splits) or with an OBB file need those installed as usual.

The layer and the Khronos loader are taken from the test app's APK (./gradlew :questlab:assembleDebug), the "kit".
"""
import os, shutil, subprocess, sys, tempfile, zipfile

HERE = os.path.dirname(os.path.abspath(__file__)); REPO = os.path.dirname(os.path.dirname(HERE))
KIT = os.path.join(REPO, "questlab", "build", "outputs", "apk", "debug", "questlab-debug.apk")
DISPATCHER = os.path.join(HERE, "prebuilt", "arm64-v8a", "libopenxr_loader.so")
ABI = "lib/arm64-v8a/"; LOADER = ABI + "libopenxr_loader.so"; GENERIC = ABI + "libopenxr_loader_generic.so"; LAYER = ABI + "libuxr_meta_layer.so"
LAYER_JSON = "assets/openxr/1/api_layers/implicit.d/XrApiLayer_ultraxr_meta.json"

def is_dispatcher(data): return b"libopenxr_loader_generic.so" in data
def as_generic(khronos):
    """The Khronos loader under another library name: two libraries in one process cannot both be called
    libopenxr_loader.so, so its own name (SONAME) is changed to one of the same length."""
    return khronos.replace(b"libopenxr_loader.so\0", b"libopenxr_khrldr.so\0")

TARGET_SDK_ATTR = 0x01010270
def lower_target_sdk(axml, to=29):
    """Android's binary manifest with targetSdkVersion set to [to] when it was higher. Returns (data, old value or None)."""
    import struct
    b = bytearray(axml); pos = 8; ids = []; old = None
    while pos + 8 <= len(b):
        typ, hdr, size = struct.unpack_from("<HHI", b, pos)
        if size < 8: break
        if typ == 0x0180: ids = list(struct.unpack_from("<%dI" % ((size - hdr) // 4), b, pos + hdr))
        elif typ == 0x0102:      # an element's start: header, line, comment, namespace, name, then the attributes
            start, asize, count = struct.unpack_from("<HHH", b, pos + hdr + 8)
            for i in range(count):
                at = pos + hdr + start + i * asize
                name = struct.unpack_from("<I", b, at + 4)[0]
                if name < len(ids) and ids[name] == TARGET_SDK_ATTR and b[at + 15] in (0x10, 0x11):
                    v = struct.unpack_from("<I", b, at + 16)[0]
                    if v > to: struct.pack_into("<I", b, at + 16, to); old = v
        pos += size
    return bytes(b), old

def build_tools():
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), "C:/Android", os.path.expanduser("~/Android/Sdk"), os.path.expanduser("~/AppData/Local/Android/Sdk")):
        d = os.path.join(root, "build-tools") if root else None
        if d and os.path.isdir(d):
            v = sorted(os.listdir(d))
            if v: return os.path.join(d, v[-1])
    raise SystemExit("Android build-tools (zipalign, apksigner) not found: set ANDROID_HOME")

def run(args):
    r = subprocess.run(args, capture_output=True)
    if r.returncode != 0: raise SystemExit("failed: %s\n%s" % (" ".join(args), (r.stdout + r.stderr).decode("utf-8", "replace")))
    return (r.stdout + r.stderr).decode("utf-8", "replace")

def pack(src, dst, kit, dispatcher_front, plain=False):
    if not os.path.isfile(kit): raise SystemExit("the kit is missing: build it with  ./gradlew :questlab:assembleDebug  (%s)" % kit)
    if not os.path.isfile(DISPATCHER): raise SystemExit("the dispatcher is missing: " + DISPATCHER)
    with zipfile.ZipFile(kit) as k: layer = k.read(LAYER); layer_json = k.read(LAYER_JSON); khronos = k.read(LOADER)
    dispatcher = open(DISPATCHER, "rb").read()
    notes = []
    tmp = dst + ".unsigned"
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(tmp, "w") as zout:
        names = set(zin.namelist())
        if not any(n.startswith(ABI) for n in names): raise SystemExit("no arm64-v8a libraries in this APK: it cannot run on the phone")
        have_loader = LOADER in names; have_generic = GENERIC in names
        loader = zin.read(LOADER) if have_loader else b""
        add = {LAYER: layer, LAYER_JSON: layer_json}; drop = {LAYER, LAYER_JSON}
        if have_loader and have_generic: notes.append("loader chain: already a dispatcher and a generic loader, left as they are")
        elif have_loader and is_dispatcher(loader):
            add[GENERIC] = as_generic(khronos); notes.append("loader chain: the game's dispatcher kept, the Khronos loader added as the generic loader")
        elif have_loader and dispatcher_front:
            add[GENERIC] = as_generic(loader); add[LOADER] = dispatcher; drop.add(LOADER)
            notes.append("loader chain: the app's loader became the generic loader, the open dispatcher put in front")
        elif have_loader and plain:
            add[LOADER] = khronos; drop.add(LOADER)
            notes.append("loader chain: the app's own loader replaced by the current Khronos loader (it asks Android's runtime broker)")
        elif have_loader: notes.append("loader chain: the app's own libopenxr_loader.so kept (not a dispatcher). If this is a Quest game as bought, it has not been converted and will not find the runtime.")
        else:
            add[LOADER] = dispatcher; add[GENERIC] = as_generic(khronos)
            notes.append("loader chain: the APK had no libopenxr_loader.so; dispatcher and Khronos loader added (the app must load libopenxr_loader.so itself for this to matter)")
        for info in zin.infolist():
            n = info.filename
            if n in drop or (n.startswith("META-INF/") and n.rsplit(".", 1)[-1].upper() in ("SF", "RSA", "DSA", "EC", "MF")): continue
            data = zin.read(n)
            if plain and n == "AndroidManifest.xml" and "runtime_broker".encode("utf-16-le") not in data and b"runtime_broker" not in data:
                data, was = lower_target_sdk(data)
                notes.append("manifest: target API lowered from %d to 29, so that the app may see the runtime broker" % was if was else "manifest: the target API is already below 30; the app can see the runtime broker")
            keep = zipfile.ZipInfo(n, date_time=info.date_time); keep.external_attr = info.external_attr
            # native libraries and the resource table are stored, as Android wants for an app that does not unpack them
            keep.compress_type = zipfile.ZIP_STORED if (n.endswith(".so") or n == "resources.arsc") else info.compress_type
            zout.writestr(keep, data)
        for n, data in add.items():
            zi = zipfile.ZipInfo(n, date_time=(1981, 1, 1, 1, 1, 2)); zi.compress_type = zipfile.ZIP_STORED if n.endswith(".so") else zipfile.ZIP_DEFLATED
            zout.writestr(zi, data)
        notes.append("layer: libuxr_meta_layer.so and its manifest added (%d kB)" % (len(layer) // 1024))
        if "lib/armeabi-v7a/" in " ".join(names): notes.append("note: the APK also has 32-bit libraries; only the 64-bit ones were prepared")
    bt = build_tools(); exe = ".exe" if os.name == "nt" else ""; bat = ".bat" if os.name == "nt" else ""
    aligned = dst + ".aligned"
    run([os.path.join(bt, "zipalign" + exe), "-p", "-f", "4", tmp, aligned]); os.remove(tmp)
    ks = os.path.expanduser("~/.android/debug.keystore")
    if not os.path.isfile(ks): raise SystemExit("no Android debug key (%s): build any app once with Gradle to make it" % ks)
    run([os.path.join(bt, "apksigner" + bat), "sign", "--ks", ks, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", dst, aligned]); os.remove(aligned)
    for junk in (dst + ".idsig",):
        if os.path.exists(junk): os.remove(junk)
    return notes

def main():
    a = [x for x in sys.argv[1:] if not x.startswith("--")]; flags = [x for x in sys.argv[1:] if x.startswith("--")]
    if len(a) < 2: print(__doc__); return 2
    src, dst = a[0], a[1]; serial = None; kit = KIT
    if "--install" in sys.argv: serial = sys.argv[sys.argv.index("--install") + 1]; a = [x for x in a if x != serial]; src, dst = a[0], a[1]
    if "--kit" in sys.argv: kit = sys.argv[sys.argv.index("--kit") + 1]; a = [x for x in a if x != kit]; src, dst = a[0], a[1]
    for n in pack(src, dst, kit, "--dispatcher-front" in flags, "--plain-openxr" in flags): print(n)
    print("written: %s (%.1f MB), signed with the debug key" % (dst, os.path.getsize(dst) / 1e6))
    if serial:
        out = subprocess.run(["adb", "-s", serial, "install", "-r", dst], capture_output=True); text = (out.stdout + out.stderr).decode("utf-8", "replace").strip()
        print("install: " + text.splitlines()[-1] if text else "install: no answer from adb")
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in text: print("The app on the phone was signed with another key: uninstall it first (its saved data goes with it), then run this again.")
    return 0

if __name__ == "__main__": sys.exit(main())
