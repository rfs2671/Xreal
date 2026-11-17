#!/usr/bin/env python3
"""Talks to a page in Wolvic (Chromium engine) through its developer tools socket.

Wolvic's Chromium build listens on the abstract socket content_shell_devtools_remote. With

    adb forward tcp:9222 localabstract:content_shell_devtools_remote

a test can list the pages, run script in one (optionally as a user gesture, which is what
requestSession needs), and read its console. This is for looking inside third-party pages that the
test cannot change; it is not available in the Gecko build.

  python cdp.py pages
  python cdp.py eval <url-substring> "<javascript>"        result as JSON
  python cdp.py click <url-substring> "<css selector>"     a real click with a user gesture
"""
import asyncio, json, sys, urllib.request
import websockets

PORT = 9222

def pages():
    with urllib.request.urlopen("http://localhost:%d/json" % PORT, timeout=15) as r:
        return [p for p in json.load(r) if p.get("type") == "page"]

def find(part):
    for p in pages():
        if part in p.get("url", ""): return p
    raise SystemExit("no page whose address contains %r" % part)

async def _run(ws_url, calls, listen_s=0.0):
    out = []; events = []
    async with websockets.connect(ws_url, max_size=50_000_000, open_timeout=20) as ws:
        n = 0
        for method, params in calls:
            n += 1
            await ws.send(json.dumps({"id": n, "method": method, "params": params}))
            while True:
                m = json.loads(await asyncio.wait_for(ws.recv(), 60))
                if m.get("id") == n: out.append(m.get("result", m.get("error"))); break
                events.append(m)
        if listen_s > 0:
            try:
                end = asyncio.get_event_loop().time() + listen_s
                while True:
                    left = end - asyncio.get_event_loop().time()
                    if left <= 0: break
                    events.append(json.loads(await asyncio.wait_for(ws.recv(), left)))
            except asyncio.TimeoutError: pass
    return out, events

def evaluate(part, script, gesture=False, await_promise=True):
    """Runs [script] in the page and returns its value (JSON-serialisable), or {"error": ...}."""
    p = find(part)
    res, _ = asyncio.run(_run(p["webSocketDebuggerUrl"], [("Runtime.evaluate", {
        "expression": script, "returnByValue": True, "awaitPromise": await_promise, "userGesture": gesture})]))
    r = res[0] or {}
    if "exceptionDetails" in r: return {"error": r["exceptionDetails"].get("exception", {}).get("description") or r["exceptionDetails"].get("text")}
    return r.get("result", {}).get("value")

def click(part, selector):
    """Clicks the first element matching [selector], as a user gesture."""
    return evaluate(part, "(() => { const e = document.querySelector(%s); if (!e) return 'no element'; e.click(); return 'clicked ' + (e.textContent || e.id || e.tagName).trim().slice(0, 60); })()" % json.dumps(selector), gesture=True)

def console(part, seconds=5.0):
    """Console messages and exceptions the page produces in the next [seconds]."""
    p = find(part)
    _, events = asyncio.run(_run(p["webSocketDebuggerUrl"], [("Runtime.enable", {}), ("Log.enable", {})], listen_s=seconds))
    out = []
    for e in events:
        if e.get("method") == "Runtime.consoleAPICalled":
            out.append(e["params"]["type"] + ": " + " ".join(str(a.get("value", a.get("description", ""))) for a in e["params"]["args"]))
        elif e.get("method") == "Runtime.exceptionThrown":
            d = e["params"]["exceptionDetails"]; out.append("exception: " + (d.get("exception", {}).get("description") or d.get("text", "")))
        elif e.get("method") == "Log.entryAdded":
            out.append("log " + e["params"]["entry"].get("level", "") + ": " + e["params"]["entry"].get("text", ""))
    return out

if __name__ == "__main__":
    a = sys.argv[1:]
    if not a or a[0] == "pages":
        for p in pages(): print(p["url"])
    elif a[0] == "eval": print(json.dumps(evaluate(a[1], a[2]), indent=1))
    elif a[0] == "click": print(click(a[1], a[2]))
    elif a[0] == "console": print("\n".join(console(a[1], float(a[2]) if len(a) > 2 else 5.0)))
