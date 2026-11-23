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
| Light on/off | Flashlight on the camera phone (if it has one) |
| Bright / Normal | Brightens the picture on this PC only |
| Snapshot (Ctrl+S) | Saves the picture on screen as a JPEG in `Pictures\BZ View` (the original frame from the camera, without Bright or zoom). The file name is the camera's capture time, for example `BZ-View-20261123-101602.jpg` |
| Snapshots folder | Opens the folder (selecting the last snapshot) |
| Full screen (F11) | Picture only; Esc or F11 returns. Right-click also has these actions |
| Get Tailscale | Opens the Tailscale download page (install it here and on the camera phone for remote access) |
| Forget | Forget the saved camera |
| Mouse wheel / drag / double-click | Zoom, pan while zoomed, zoom in or reset |

The badge at the top shows the stream is alive: a green dot with the connection type (`LOCAL` on
the home network, `REMOTE` over Tailscale) and the camera phone's clock,
amber `CONNECTING`, red `NO VIDEO` when no frame has arrived for a few seconds.

The viewer always tries the local network first and falls back to the remote (Tailscale) address.
While on remote it checks every 15 seconds and switches back to local as soon as it is reachable.
See the main README for the one-time Tailscale setup.

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
