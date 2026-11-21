#!/usr/bin/env python3
"""Measure a running BedPlay stream over adb (resolution, fps, bitrate, loss, jitter buffer, decode time).

Requirements: adb in PATH (or pass --adb), the stick connected (`adb connect <ip>:5555`),
`pip install websockets`, and a stream that is currently playing.

    python tools/measure.py --serial <stick-ip>:5555 --seconds 3

The app's WebView exposes Chrome DevTools; we read WebRTC `getStats()` twice and print the difference.
"""
import argparse
import asyncio
import json
import re
import subprocess
import sys
import urllib.request

JS = r"""
(async () => {
  const c = window.player && window.player._webrtcClient;
  if (!c) return JSON.stringify({error: 'no stream is running'});
  const grab = async () => {
    const o = {t: Date.now()};
    (await c.getStats()).forEach(r => {
      if (r.type === 'inbound-rtp' && r.kind === 'video') Object.assign(o, {
        w: r.frameWidth, h: r.frameHeight, fps: r.framesPerSecond, rec: r.framesReceived, dec: r.framesDecoded,
        drop: r.framesDropped, lost: r.packetsLost, bytes: r.bytesReceived, jbd: r.jitterBufferDelay,
        jbe: r.jitterBufferEmittedCount, dt: r.totalDecodeTime, freeze: r.freezeCount });
      if (r.type === 'candidate-pair' && r.nominated) o.rtt = r.currentRoundTripTime;
    });
    return o;
  };
  const a = await grab();
  await new Promise(r => setTimeout(r, SECONDS * 1000));
  const b = await grab();
  return JSON.stringify({a, b});
})()
"""


def adb(args, serial):
    cmd = [ADB] + (["-s", serial] if serial else []) + args
    return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout


async def run(port, seconds):
    import websockets
    tabs = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json", timeout=10))
    ws_url = [t for t in tabs if t["type"] == "page"][0]["webSocketDebuggerUrl"]
    # `player` is a top-level `var` in app.js, so it is a property of window
    expr = JS.replace("SECONDS", str(seconds))
    async with websockets.connect(ws_url, max_size=None) as ws:
        await ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate",
                                  "params": {"expression": expr, "returnByValue": True, "awaitPromise": True}}))
        while True:
            msg = json.loads(await asyncio.wait_for(ws.recv(), seconds + 20))
            if msg.get("id") == 1:
                return json.loads(msg["result"]["result"]["value"])


def main():
    global ADB
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--adb", default="adb")
    p.add_argument("--serial", help="adb device serial, e.g. <stick-ip>:5555")
    p.add_argument("--package", default="app.bedplay")
    p.add_argument("--port", type=int, default=9333, help="local port used for the DevTools forward")
    p.add_argument("--seconds", type=int, default=3)
    a = p.parse_args()
    ADB = a.adb

    pid = adb(["shell", "pidof", a.package], a.serial).strip().split()[0]
    unix = adb(["shell", "cat", "/proc/net/unix"], a.serial)
    m = re.search(r"@(webview_devtools_remote_%s)\b" % pid, unix)
    if not m:
        sys.exit("DevTools socket not found: is BedPlay running?")
    adb(["forward", f"tcp:{a.port}", f"localabstract:{m.group(1)}"], a.serial)

    r = asyncio.run(run(a.port, a.seconds))
    if "error" in r:
        sys.exit(r["error"])
    x, y = r["a"], r["b"]
    dt = (y["t"] - x["t"]) / 1000.0
    frames = y["rec"] - x["rec"]
    print(f"resolution       {y['w']}x{y['h']}")
    print(f"frame rate       {frames / dt:.1f} fps (reported {y.get('fps')})")
    print(f"bitrate          {(y['bytes'] - x['bytes']) * 8 / dt / 1e6:.1f} Mbit/s")
    print(f"packets lost     {y['lost']} total, {y['lost'] - x['lost']} in the window")
    print(f"frames dropped   {y['drop']} of {y['dec']} decoded ({100.0 * y['drop'] / max(1, y['dec']):.2f}%), "
          f"{y['drop'] - x['drop']} in the window")
    print(f"freezes          {y['freeze']} total")
    print(f"jitter buffer    {y['jbd'] / max(1, y['jbe']) * 1000:.1f} ms average since the start")
    print(f"decode time      {y['dt'] / max(1, y['dec']) * 1000:.1f} ms per frame average since the start")
    print(f"round trip       {y.get('rtt', 0) * 1000:.0f} ms")


if __name__ == "__main__":
    main()
