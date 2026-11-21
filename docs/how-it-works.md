# How BedPlay works

## Parts

| Part | Where | What it does |
|---|---|---|
| `Auth.java` | `android/.../app/bedplay` | Microsoft device-code sign-in, Xbox Live / XSTS tokens, and the streaming login that returns a regional base URL and a `gsToken` |
| `ProxyServer.java` | same | Tiny HTTP server on `127.0.0.1:18080`. Serves the player page and forwards `/v5 /v6 /v2 /v1` to the streaming service adding the `Authorization` header. Requires a random per-launch key (cookie) |
| `MainActivity.java` | same | Full-screen WebView, gamepad capture (`KeyEvent` / `MotionEvent` mapped to the standard gamepad layout) and the bridge to the page |
| `assets/app.js` | `android/app/src/main/assets` | Screens (sign-in, menu, stream), session lifecycle, stats overlay, quality presets |
| `assets/dist/xCloudPlayer.min.js` | built by `web/build.js` | The WebRTC player from [xbox-xcloud-player](https://github.com/unknownskl/xbox-xcloud-player) with one patch |

## Flow of a Remote Play session

1. **Sign in** with the device-code flow, get Xbox Live and XSTS tokens for the relying party `http://gssv.xboxlive.com/`.
2. **Streaming login** (`/v2/login/user` on the `xhome` offering) returns the regional base URL and a `gsToken`.
3. **List consoles** with `GET /v6/servers/home`.
4. **Start a session** with `POST /v5/sessions/home/play`, then poll `/state` until `Provisioned` (a console in standby is woken here; this part is untested).
5. **WebRTC handshake**: the player creates an SDP offer, `POST /sdp`, applies the console's answer, then exchanges ICE candidates through `/ice`. Teredo candidates from the console are translated into plain IPv4 candidates so the stick can reach it on the LAN.
6. **Streaming**: H.264 video and Opus audio over WebRTC; controller input goes back through the input data channel.
7. A `keepalive` call every 30 s; the session is deleted when you quit.

The page and the player run inside an Android WebView, which uses the stick's own WebRTC/H.264 decoding (we did not verify whether it is the hardware decoder). Because the WebView on a stick does not expose gamepads to `navigator.getGamepads()`, the Java side captures them natively and pushes the state into a small shim in `index.html`.

## Why the stream was too heavy, and the fix

By default, the player declares a 1920x1080 screen with `supportsCustomResolution: true` in the `dimensionschanged` control message. A Series X answered by encoding **2560x1440 at about 28 Mbit/s**, which a 2 GB stick cannot decode smoothly (it dropped frames, froze and kept a larger jitter buffer).

Two changes:

1. **Declared screen size and custom resolution** are now read from `window.__dims` / `window.__customRes` (set by `app.js`). `web/build.js` patches this into the player at build time. With custom resolution off the console sends 1080p.
2. **Bitrate cap** via `player.setVideoBitrate()`, which writes a `b=AS:` line in the SDP offer. 8000 kbit/s is the default.

Things that did **not** work, so you do not repeat them: changing the `displayInfo` in the `X-MS-Device-Info` header alone did not change the resolution, and the console did not go below 1080p in any setting we tried. The H.264 profile negotiated is already the lightest (baseline, `42001f`), so a lighter profile is not available.

## Local server and security

The WebView loads `http://127.0.0.1:18080/?k=<random key>`. The server answers the first request, sets the key as an `HttpOnly; SameSite=Strict` cookie and returns **403** for everything without that cookie. The key is generated at each launch with `SecureRandom`. This prevents other apps on the device from using the local port to act on your Xbox account.

Cleartext HTTP is enabled in the manifest only because the page is served from `127.0.0.1`.
