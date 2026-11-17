#!/usr/bin/env python3
"""Small adb UI driver used by the desktop's automated tests (emulator or phone).

  python uia.py [-s SERIAL] texts | tap "<text or description>" ... | shot FILE.png | swipe x1 y1 x2 y2 [ms]
"""
import re, subprocess, sys, time

SERIAL = "emulator-5554"

def adb(*a, timeout=60, binary=False):
    r = subprocess.run(["adb", "-s", SERIAL, *a], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")

def sh(cmd, timeout=60): return adb("shell", cmd, timeout=timeout)

def dump():
    for _ in range(4):
        # --windows: all windows on all displays, so a window on an extra display (the stand-in for
        # the glasses) does not hide the phone screen from the dump
        sh("rm -f /data/local/tmp/ui.xml; uiautomator dump --windows /data/local/tmp/ui.xml >/dev/null 2>&1 || uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1")
        x = sh("cat /data/local/tmp/ui.xml 2>/dev/null")
        if "<node" in x: return x
        time.sleep(0.7)
    return ""

def nodes(x=None):
    out = []
    for m in re.finditer(r"<node[^>]*?>", x if x is not None else dump()):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n); d = re.search(r' content-desc="([^"]*)"', n); b = re.search(r' bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not b: continue
        x1, y1, x2, y2 = map(int, b.groups())
        out.append(dict(text=t.group(1) if t else "", desc=d.group(1) if d else "", x=(x1 + x2) // 2, y=(y1 + y2) // 2, box=(x1, y1, x2, y2)))
    return out

def find(label, ns=None):
    ns = ns if ns is not None else nodes()
    low = label.lower()
    for n in ns:
        if n["text"].strip().lower() == low or n["desc"].strip().lower() == low: return n
    for n in ns:
        if len(low) > 3 and (low in n["text"].lower() or low in n["desc"].lower()): return n
    return None

def tap(label):
    n = find(label)
    if not n: return False
    sh("input tap %d %d" % (n["x"], n["y"])); return True

def texts(): return [(n["text"] or ("[" + n["desc"] + "]")) for n in nodes() if n["text"] or n["desc"]]

def shot(path):
    data = adb("exec-out", "screencap", "-p", binary=True)
    open(path, "wb").write(data); return len(data)

if __name__ == "__main__":
    a = sys.argv[1:]
    if a and a[0] == "-s": SERIAL = a[1]; a = a[2:]
    if a[0] == "texts":
        for t in texts(): print(t.replace("\n", " | ")[:200].encode("ascii", "replace").decode())
    elif a[0] == "tap":
        for label in a[1:]: print("tap %r -> %s" % (label, tap(label))); time.sleep(1.2)
    elif a[0] == "shot": print(shot(a[1]), "bytes")
    elif a[0] == "swipe": sh("input swipe " + " ".join(a[1:]))
