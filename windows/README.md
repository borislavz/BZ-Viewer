# BZ View Viewer for Windows

A Windows viewer for the BZ View Camera phone app. It connects by typing or pasting the
camera's address, so the PC does not need a camera to scan the QR code.

## Using it

1. On the camera phone, open **BZ View Camera** and tap **Start streaming**.
2. On the PC (same Wi-Fi or network), run `BZ-View-Viewer.exe` and click **Connect...**.
3. Type or paste the address shown under the QR code, for example
   `bzview://192.168.1.20:8765/abcd1234`. The short form `192.168.1.20 abcd1234` works too.

The address is remembered, and the viewer reconnects automatically if the connection drops.
You can also start it with the address as an argument: `BZ-View-Viewer.exe bzview://192.168.1.20:8765/abcd1234`.

| Control | What it does |
|---|---|
| Connect... | Enter a camera address |
| Camera... | Choose which camera (lens) the phone uses |
| Torch on/off | Flashlight on the camera phone (if it has one) |
| Bright / Normal | Brightens the picture on this PC only |
| Full screen (F11) | Picture only; Esc or F11 returns. Right-click also has these actions |
| Forget | Forget the saved camera |
| Mouse wheel / drag / double-click | Zoom, pan while zoomed, zoom in or reset |

The badge at the top shows the stream is alive: green `LIVE` with the camera phone's clock,
amber `CONNECTING`, red `NO VIDEO` when no frame has arrived for a few seconds.

Settings are stored in `%APPDATA%\BZ View Viewer\settings.txt`.

## Building

No SDK or Visual Studio is needed. Windows includes the C# compiler (.NET Framework 4):

```
powershell -ExecutionPolicy Bypass -File build.ps1
```

This writes `dist\BZ-View-Viewer.exe` (about 45 KB). It runs on Windows 10 and 11.
`tools\make-icon.ps1` regenerates `app.ico`.

## Notes

* It speaks the same protocol as the Android viewer (see the main README and `core/`).
* Like the phone apps, traffic on the local network is not encrypted.
