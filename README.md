# BZ View

Turn two Android phones into a home camera system, similar to Alfred Camera.

* **BZ View Camera** (`:camera`, `com.borislav007.bzview.camera`) runs on Phone 1. It streams live video and shows a pairing QR code.
* **BZ View Viewer** (`:viewer`, `com.borislav007.bzview.viewer`) runs on Phone 2. It scans the QR code and shows the live video.
* **BZ View Viewer for Windows** (`windows/`) is a PC viewer that connects by typing the camera's address, for computers without a camera. See [windows/README.md](windows/README.md).
* **Web viewer** (`web/assets/viewer.html`) is a single page the camera phone serves itself. Open the web address shown on the camera phone (`http://<camera-ip>:8765/?t=<code>`) in any browser on the same Wi-Fi (Windows, Chrome OS, Mac, Linux, iPhone, Android). It has the same LIVE badge, zoom and pan (mouse wheel, pinch, double-tap), Bright, camera switch, light (flashlight), full screen and **Snapshot** (button or the `S` key; downloads the current frame as a JPEG). The camera app's **Share web link** button sends the address by any app.
* `:core` holds the shared code: pairing address format, the streaming server and client, QR generation, network helpers.

## How it works (version 1)

Both phones are normally on the same Wi-Fi network (see below for remote access). No server of ours is needed.

* The camera phone runs a foreground service that owns the camera and a small HTTP server on port 8765.
* Video is sent as an MJPEG stream (`GET /stream?t=TOKEN`). Remote controls use `GET /cmd?t=TOKEN&name=...`.
* `GET /?t=TOKEN` returns the web viewer page. It is plain HTTP on the local network, so keep the web address private: anyone who has it can watch.
* The pairing address is `bzview://<ip>:8765/<token>`. The token is random, created on first run, and required for every request.
* The viewer scans the QR code (or the address can be typed in) and remembers the camera. It reconnects automatically.

## Local network first, remote fallback (Tailscale)

At home the viewer connects over Wi-Fi (best quality and stability). Away from home it falls back to a
remote connection through [Tailscale](https://tailscale.com), a free VPN overlay. No server of ours is
involved and traffic is encrypted end to end by WireGuard.

One-time setup:

1. Install Tailscale on the camera phone and on every viewer device (phone, Windows PC) and sign in to the same account.
2. Start streaming on the camera. The camera screen shows "Remote access ready" with its Tailscale address.
3. Pair viewers as usual. The QR code and address now carry the remote address (`bzview://<ip>:8765/<token>?r=<tailscale ip>`).
   Viewers paired earlier learn it automatically the next time they connect over Wi-Fi.

How it behaves:

* Each connection attempt tries the local address first (2 s), then the remote one. The badge shows `LOCAL` or `REMOTE` next to the green dot.
* While on remote, the viewer checks the local network every 15 s and switches back when you are home again.
* The web viewer is served from one address, so it cannot switch by itself. It shows `LOCAL` or `REMOTE` according to the address it was opened on; the camera screen lists both web links ("Same Wi-Fi" and "Remote"), and the remote one needs Tailscale on that device.

## Features in version 1

* Live video from the camera phone to the viewer phone
* QR code pairing (or manual address entry)
* Camera keeps streaming with the screen off (foreground service)
* Remote controls from the viewer: switch front/back camera, flashlight

## Not yet implemented

Audio and two-way talk, motion detection and alerts, recording and snapshots, viewing over the internet without a VPN (needs a signaling server and relay), TLS encryption on the local network.

## Build

```
gradlew :camera:assembleDebug :viewer:assembleDebug
```

APKs are written to `camera/build/outputs/apk/debug/` and `viewer/build/outputs/apk/debug/`.
