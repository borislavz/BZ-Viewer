# Tapo web viewer (local network)

A web page that shows your Tapo cameras (C210, C120 and other Tapo models) in any browser on your home network:
both cameras side by side, tap one for the full-size picture.

Browsers cannot play a camera's RTSP stream directly, so a small program on your PC converts it.
This folder uses [go2rtc](https://github.com/AlexxIT/go2rtc) (open source, MIT) for that. It is downloaded
automatically on the first run into `bin\` and is not part of the repository. The page itself is `www\index.html`;
`www\video-rtc.js` and `www\video-stream.js` are go2rtc's browser player scripts (MIT).

## One-time setup

1. **Create a camera account for each camera** in the Tapo app (this is not your Tapo cloud login):
   camera > settings (gear) > **Advanced Settings** > **Camera Account**. Pick a user name and password.
2. **Find each camera's IP address**: camera settings > Device Info, or your router's device list.
   Giving the cameras a fixed address in the router (a DHCP reservation) keeps this working.
3. Double-click **`Start Tapo Viewer.cmd`** (or run `powershell -ExecutionPolicy Bypass -File start.ps1`).
   On the first run it creates `cameras.txt` and opens it. Enter your cameras, save, and start it again:

   ```
   Living room = 192.168.1.50, camera-user, camera-password
   Hallway     = 192.168.1.51, camera-user, camera-password
   ```

4. Open **http://localhost:1984/** on this PC, or the "other devices" address printed in the window
   from a phone or another computer on the same Wi-Fi. If Windows asks about the firewall, allow it on **Private** networks.

Stop it with Ctrl+C or by closing the window. The PC has to stay on while you watch.

## Using the page

* The grid shows each camera's standard-quality stream, which is light on the network.
* Tap or click a camera for the full-size picture on the **high-quality** stream (the other cameras pause).
  **Back** or Esc returns to the grid; **Full screen** or `F` fills the screen.

## Options in `cameras.txt`

* `port = 1984` changes the web port.
* `login = viewer, choose-a-password` asks for a user name and password in the browser. Recommended: otherwise anyone on
  your network who knows the address can watch. (go2rtc does not ask on the PC it runs on, only on other devices.)
* Any other RTSP camera: `Garage = rtsp://user:password@192.168.1.60:554/stream1`

`cameras.txt` and the generated `go2rtc.yaml` contain your camera passwords. They are git-ignored; keep them private.

## Notes

* Only the web port (1984) and the WebRTC port (8555) are opened. go2rtc's own RTSP server is switched off. Do not forward these
  ports on your router; this is meant for the local network.
* Tapo cameras give two streams: `stream1` (high quality) and `stream2` (standard). Each open page uses the camera's connection,
  and Tapo cameras only allow a few viewers at the same time.
* If a camera shows nothing: check the camera account, that the IP address is right, and that the PC and camera are on the same network.
  If a high-quality stream will not play in your browser, the camera may be set to H.265; choose H.264 in the Tapo app's video settings if it offers that.
* Tested with an MJPEG test stream standing in for a camera (grid, tap for full size, login). It has not been tried against real Tapo cameras yet.
