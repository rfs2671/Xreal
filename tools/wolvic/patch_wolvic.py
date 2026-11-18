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

Second change, both variants: pages that list "hand-tracking" as a required WebXR feature (not an
optional one) were refused. Chromium asks the browser for a hand tracking permission; Wolvic's
engine maps every permission it has no name for to NOT_SUPPORTED, and its Java side
(ChromiumPermissionDelegate.requestContentPermission) denies those outright. The change makes that
method treat NOT_SUPPORTED as the XR permission instead: the answer is whatever the user's XR
permission for that site is. Cost, stated plainly: any other permission Wolvic has no name for now
also follows the site's XR permission instead of being always denied. It is a few bytes of bytecode
in a dex file (found with baksmali, patched in place, dex checksum and signature recomputed).

Third change, phone variant only: Chromium's Vulkan path is switched off. On the Pixel 10 (PowerVR) the browser's
GPU process lost its graphics context about two seconds after every start and again a few seconds into a WebXR
session, which froze the game (17 November 2026: 11 of 12 starts). Wolvic's source already disables Vulkan for its
x86-64 builds, which is why the emulator never showed it; the same switch is applied to the phone build here by
editing the --disable-features text. See docs/PIXEL_RESULTS.md for the before and after counts.

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

XR_PERMISSION = 3   # PermissionManagerBridge.ContentPermissionType.XR; NOT_SUPPORTED is 9

def patch_dex(d, kind):
    """In requestContentPermission: 'if (type == NOT_SUPPORTED) return DENY' becomes 'if (type == NOT_SUPPORTED) type = XR'. True if found."""
    any4 = b"[\x00-\xff]{2}"
    def inv(reg): return re.escape(bytes([0x71, 0x10])) + any4 + re.escape(bytes([reg, 0]))
    if kind == "phone":   # const/16 v1,9; if-ne v3,v1; const/4 v3,1; valueOf(v3); move-result v3; fromValue(v3); move-result v3; return-object v3
        pat = re.escape(bytes([0x13, 1, 9, 0, 0x33, 0x13])) + any4 + b"(" + re.escape(bytes([0x12, 0x13])) + inv(3) + re.escape(bytes([0x0C, 3])) + inv(3) + re.escape(bytes([0x0C, 3, 0x11, 3])) + b")"
        new = bytes([0x12, 0x30 | 3])            # const/4 v3, 3
    else:                 # const/16 v3,9; const/4 v4,1; if-ne v0,v3; valueOf(v4); move-result v0; fromValue(v0); move-result v0; goto
        pat = re.escape(bytes([0x13, 3, 9, 0, 0x12, 0x14, 0x33, 0x30])) + any4 + b"(" + inv(4) + re.escape(bytes([0x0C, 0])) + inv(0) + re.escape(bytes([0x0C, 0, 0x28])) + b"[\x00-\xff])"
        new = bytes([0x12, 0x30 | 0])            # const/4 v0, 3
    ms = list(re.finditer(pat, bytes(d), re.S))
    if len(ms) != 1: return len(ms)
    a, b = ms[0].span(1)
    d[a:b] = new + bytes(b - a - len(new))      # the rest becomes no-ops, falling through to the normal path
    if kind == "phone":
        # Third change, phone only: the engine's Vulkan graphics path is switched off, as Wolvic's own source already
        # does for x86-64 builds (RuntimeImpl.initBrowserProcess: disableFeatures += ",Vulkan"). The text constant
        # passed as --disable-features is edited in place. It must keep its length and its place in the file's sorted
        # string table, so "AndroidUseCorrectWindowBounds" becomes "AndroidUseCorrectWindo,Vulkan": Vulkan off, and
        # the first name no longer matches anything (that feature only affected how one video site sizes fullscreen).
        old = b"\x1dAndroidUseCorrectWindowBounds\x00"; new = b"\x1dAndroidUseCorrectWindo,Vulkan\x00"
        if bytes(d).count(old) != 1: raise SystemExit("the disable-features constant was not found exactly once: nothing changed")
        i = bytes(d).index(old); d[i:i + len(old)] = new
    d[12:32] = hashlib.sha1(bytes(d[32:])).digest()
    import zlib
    struct.pack_into("<I", d, 8, zlib.adler32(bytes(d[12:])) & 0xFFFFFFFF)
    return 1

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
    dexes = {}
    for item in z.infolist():
        if re.fullmatch(r"classes\d*\.dex", item.filename):
            dd = bytearray(z.read(item.filename)); n = patch_dex(dd, kind)
            if n == 1: dexes[item.filename] = bytes(dd)
            elif n > 1: raise SystemExit("the permission check was found more than once in %s: nothing changed" % item.filename)
    if len(dexes) != 1: raise SystemExit("the permission check was found in %d dex files, expected 1: nothing changed" % len(dexes))
    tmp = out + ".unsigned"
    o = zipfile.ZipFile(tmp, "w")
    for item in z.infolist():
        if item.filename.startswith("META-INF/") and item.filename.endswith((".RSA", ".SF", ".MF", ".DSA", ".EC")): continue
        zi = zipfile.ZipInfo(item.filename, date_time=item.date_time); zi.compress_type = item.compress_type; zi.external_attr = item.external_attr
        o.writestr(zi, bytes(data) if item.filename == lib else dexes.get(item.filename) or z.read(item.filename))
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
