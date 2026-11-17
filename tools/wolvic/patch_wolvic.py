#!/usr/bin/env python3
"""Makes the Ultra XR copy of the Wolvic browser from Igalia's official release.

  python patch_wolvic.py phone  Wolvic-lynx-arm64-chromium-generic-release.apk  wolvic-ultraxr-arm64.apk
  python patch_wolvic.py emulator  Wolvic-aosp-x64-chromium-magicleap-release.apk  wolvic-emu-test.apk

Inputs are the v1.4-chromium release files from https://github.com/Igalia/wolvic/releases (checked by
SHA-256 below). The output is unsigned-aligned and then signed with the key given by --ks (default: the
repo's debug key), because changing a file inside an APK invalidates Igalia's signature. It installs as
a separate app from an official Wolvic only if that one is not installed (same package name).

Why: Wolvic gives web pages XR input (hands, joints, select) only on device models it knows (Quest,
Pico, Lynx-R1, Magic Leap 2, ...). On any other model its controller type is "unknown" and the web
engine drops every input source, so a WebXR game would start but could not be played. The one change
for the phone makes an unknown model count as the Lynx-R1, a headset that is used with hands:

  crow::DeviceUtils::GetDeviceTypeFromSystem(): the value returned for a model that is not in the
  table is 14 (device::LynxR1) instead of 0 (device::UnknownType).

Checked on an emulator with the x86-64 build: with this change all 20 checks of
tools/ui-automation/webxr_test.py pass; without it the four input checks fail. The arm64 output has
not been run on a phone. Wolvic is MPL-2.0; its source is at https://github.com/Igalia/wolvic.

The emulator variant also accepts a graphics configuration with a depth buffer, because the Android
emulator offers no other; a real phone needs no such change and the phone variant does not have it.

A proper source build is the better long-term route, but Wolvic's engines need their own patched
builds of GeckoView or Chromium, which this Windows machine cannot produce.
"""
import hashlib, os, re, struct, subprocess, sys, zipfile

RELEASES = {
    "phone": ("lib/arm64-v8a/libnative-lib.so", "8a9a1cc0ea1fafed64f001974f69810baa07c6cf6bf29f0071d8d22e62e8d4a5"),
    "emulator": ("lib/x86_64/libnative-lib.so", "00a3e2ca31f3a9f3d10437b5c799e202828c8ef899cf5df4406d3b37e64be532"),
}
LYNX_R1 = 14

def file_offset(d, va):
    phoff, = struct.unpack_from("<Q", d, 0x20); phentsize, phnum = struct.unpack_from("<HH", d, 0x36)
    for i in range(phnum):
        t, fl, off, vaddr, pa, fsz, msz, al = struct.unpack_from("<IIQQQQQQ", d, phoff + i * phentsize)
        if t == 1 and vaddr <= va < vaddr + fsz: return va - vaddr + off
    raise SystemExit("address %x is not in the library" % va)

def patch_phone(data):
    # 0xf245c: bl __android_log_print ("Device %s is not supported"); 0xf2460: mov w0, wzr  ->  mov w0, #14
    o = file_offset(data, 0xf2460)
    if struct.unpack_from("<I", data, o)[0] != 0x2A1F03E0 or struct.unpack_from("<I", data, file_offset(data, 0xf245c))[0] >> 26 != 0x25:
        raise SystemExit("the library is not the expected one: nothing changed")
    struct.pack_into("<I", data, o, 0x52800000 | (LYNX_R1 << 5))

def patch_emulator(data):
    # 0xf859a..0xf85ba: the warning and "xor ebx, ebx"  ->  mov ebx, 14 and no-ops
    a = file_offset(data, 0xf859a); b = file_offset(data, 0xf85bb); x = file_offset(data, 0xf85a8)
    if bytes(data[a:a + 3]) != bytes([0x48, 0x8D, 0x35]) or bytes(data[x:x + 2]) != bytes([0x31, 0xDB]) or b - a != 33:
        raise SystemExit("the library is not the expected one: nothing changed")
    data[a:b] = bytes([0xBB, LYNX_R1, 0, 0, 0]) + bytes([0x90]) * 28
    # the emulator has no EGL configuration without a depth buffer: accept depth 24 and stencil 8 instead of 0 and 0
    for attr, want in ((0x25, 24), (0x26, 8)):
        pat = re.compile(re.escape(bytes([0xBA, attr, 0x30, 0, 0, 0x4C, 0x89, 0xF9, 0xE8])) + b"[\x00-\xff]{4}" + re.escape(bytes([0x83, 0x7C, 0x24, 0x10, 0x00])), re.S)
        ms = list(pat.finditer(bytes(data)))
        if len(ms) != 1: raise SystemExit("graphics configuration check not found: nothing changed")
        data[ms[0].end() - 1] = want

def main():
    a = sys.argv[1:]
    ks = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "debug.keystore"); tools = None
    if "--ks" in a: i = a.index("--ks"); ks = a[i + 1]; del a[i:i + 2]
    if "--build-tools" in a: i = a.index("--build-tools"); tools = a[i + 1]; del a[i:i + 2]
    if len(a) != 3 or a[0] not in RELEASES: print(__doc__); return 2
    kind, src, out = a
    lib, sha = RELEASES[kind]
    got = hashlib.sha256(open(src, "rb").read()).hexdigest()
    if got != sha: raise SystemExit("%s is not the v1.4-chromium release file this tool was written for\n  expected %s\n  got      %s" % (src, sha, got))
    z = zipfile.ZipFile(src); data = bytearray(z.read(lib))
    (patch_phone if kind == "phone" else patch_emulator)(data)
    tmp = out + ".unsigned"
    o = zipfile.ZipFile(tmp, "w")
    for item in z.infolist():
        if item.filename.startswith("META-INF/") and item.filename.endswith((".RSA", ".SF", ".MF", ".DSA", ".EC")): continue
        zi = zipfile.ZipInfo(item.filename, date_time=item.date_time); zi.compress_type = item.compress_type; zi.external_attr = item.external_attr
        o.writestr(zi, bytes(data) if item.filename == lib else z.read(item.filename))
    o.close()
    if tools is None:
        sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "C:/Android"
        tools = os.path.join(sdk, "build-tools", sorted(os.listdir(os.path.join(sdk, "build-tools")))[-1])
    ext = ".exe" if os.name == "nt" else ""; bat = ".bat" if os.name == "nt" else ""
    aligned = out + ".aligned"
    subprocess.run([os.path.join(tools, "zipalign" + ext), "-f", "-P", "16", "4", tmp, aligned], check=True)
    subprocess.run([os.path.join(tools, "apksigner" + bat), "sign", "--ks", ks, "--ks-pass", "pass:android", "--key-pass", "pass:android",
                    "--ks-key-alias", "androiddebugkey", "--out", out, aligned], check=True, shell=(os.name == "nt"))
    for f in (tmp, aligned, out + ".idsig"):
        if os.path.exists(f): os.remove(f)
    print("written: " + out)
    return 0

if __name__ == "__main__":
    sys.exit(main())
