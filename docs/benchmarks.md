# Benchmarks: method and raw values

Date: 2026-11-20. Console: Xbox Series X, in the same room as the sticks. Network: home Wi-Fi on the 5 GHz band.

| Device | Model | OS | RAM | Wi-Fi |
|---|---|---|---|---|
| Mi TV Stick | MiTV-AYFR0 | Android 11, armv7 | 2 GB | 5 GHz |
| Fire TV Stick 4K (1st gen, 2018) | - | Fire OS 6 (Android 7.1.2) | about 1.5 GB | 5 GHz |

## Method

The app's WebView exposes Chrome DevTools over adb. We read `RTCPeerConnection.getStats()` (inbound video RTP and the nominated candidate pair) twice, 3 seconds apart, while a stream was running, and computed rates from the difference. `tools/measure.py` does the same:

```
pip install websockets
python tools/measure.py --serial <stick-ip>:5555 --seconds 3
```

Averages shown as "per frame" or "jitter buffer" are cumulative since the start of the session (`totalDecodeTime / framesDecoded`, `jitterBufferDelay / jitterBufferEmittedCount`).

## Raw values

### Mi TV Stick, stock player (declares a 1920x1080 screen with custom resolution on)

| Run | Resolution | fps | Frames received / decoded / dropped | Packets lost | Bytes received | Freezes | Jitter buffer avg | Decode avg | RTT |
|---|---|---|---|---|---|---|---|---|---|
| 1, t=0 | 2560x1440 | 58 | 7,190 / 7,029 / 157 | 488 | 357,281,707 | 5 | 17.9 ms | 71 ms | 454 ms (first sample, outlier) |
| 1, t=+3 s | 2560x1440 | 52 | 7,377 / 7,206 / 165 | 488 | 367,882,470 | 5 | 18.0 ms | 71 ms | 41 ms |
| 2, t=0 | 2560x1440 | 57 | 2,618 / 2,426 / 184 | 260 | 151,533,351 | 5 | 30.0 ms | - | 61 ms |
| 2, t=+3 s | 2560x1440 | 56 | 2,801 / 2,608 / 184 | 260 | 162,059,228 | 5 | 30.0 ms | - | 36 ms |

Bitrate in run 1: (367,882,470 - 357,281,707) x 8 / 3.02 s = 28.1 Mbit/s.

### Mi TV Stick, BedPlay (declared screen 1280x720, custom resolution off, 8 Mbit/s cap)

| Run | Resolution | fps | Frames received / decoded / dropped | Packets lost | Bytes received | Freezes | Jitter buffer avg | RTT |
|---|---|---|---|---|---|---|---|---|
| t=0 | 1920x1080 | 59 | 709 / 675 / 31 | 0 | 11,487,058 | 0 | 5.1 ms | 8 ms |
| t=+3 s | 1920x1080 | 60 | 889 / 856 / 31 | 0 | 14,552,801 | 0 | 4.8 ms | 10 ms |

Bitrate: (14,552,801 - 11,487,058) x 8 / 3.01 s = 8.1 Mbit/s.

### Fire TV Stick 4K (2018), BedPlay (same settings)

| Run | Resolution | fps | Frames received / decoded / dropped | Packets lost | Bytes received | Freezes | Jitter buffer avg | Decode avg | RTT |
|---|---|---|---|---|---|---|---|---|---|
| t=0 | 1920x1080 | 61 | 11,505 / 11,505 / 63 | 0 | 174,612,262 | 4 | 4.7 ms | 35.5 ms | 18 ms |
| t=+3 s | 1920x1080 | 59 | 11,687 / 11,687 / 63 | 0 | 177,525,160 | 4 | 4.8 ms | 35.5 ms | 7 ms |

Bitrate: (177,525,160 - 174,612,262) x 8 / 3.03 s = 7.7 Mbit/s.

## Caveats

- One configuration per row, measured once. No repetitions, no statistics.
- Sessions had different lengths, so cumulative counters (lost packets, dropped frames, freezes) are not directly comparable between rows.
- The stock-player runs and the BedPlay runs were different sessions, so the round-trip time also reflects the Wi-Fi load of the stream (28 vs 8 Mbit/s) and normal fluctuations.
- Decode time per frame includes the decoder's own queueing; it is a relative indicator, not a pure hardware figure.
- The console already had an open NAT type during the BedPlay runs.
- End-to-end input latency was not measured.
