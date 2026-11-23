using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Threading;
using System.Windows.Forms;

namespace BZView.Viewer
{
    /// <summary>The viewer window: toolbar, live picture and status line.</summary>
    internal sealed class MainForm : Form
    {
        private const long StallMs = 2500;

        private static readonly Color LiveColor = Color.FromArgb(76, 175, 80);
        private static readonly Color WaitColor = Color.FromArgb(255, 179, 0);
        private static readonly Color StalledColor = Color.FromArgb(244, 67, 54);
        private static readonly Color ButtonColor = Color.FromArgb(57, 73, 171);
        private static readonly Color DisabledColor = Color.FromArgb(70, 70, 70);

        /// <summary>One decoded frame waiting to be shown (only the newest is kept).</summary>
        private sealed class FramePacket
        {
            public Bitmap Image;
            public long CameraTimeMs;
        }

        private readonly VideoView view = new VideoView();
        private readonly FlowLayoutPanel toolbar = new FlowLayoutPanel();
        private readonly StatusStrip statusStrip = new StatusStrip();
        private readonly ToolStripStatusLabel statusLabel = new ToolStripStatusLabel();
        private readonly System.Windows.Forms.Timer badgeTimer = new System.Windows.Forms.Timer();
        private readonly Stopwatch clock = Stopwatch.StartNew();

        private Button connectButton;
        private Button cameraButton;
        private Button torchButton;
        private Button brightButton;
        private Button fullscreenButton;
        private Button forgetButton;
        private Button exitButton;
        private ToolStripMenuItem menuBright;
        private ToolStripMenuItem menuFullscreen;

        private PairingInfo info;
        private StreamClient client;
        private bool connected;
        private bool hasTorch;
        private bool torchOn;
        private bool fullscreen;

        private FramePacket pending;
        private long lastFrameArrivalMs;
        private long lastCameraTimeMs;
        private long connectedAtMs;

        private FormBorderStyle savedStyle;
        private FormWindowState savedState;
        private Rectangle savedBounds;

        public MainForm(string startAddress)
        {
            AutoScaleMode = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96f, 96f);
            Text = "BZ View Viewer";
            BackColor = Color.Black;
            ClientSize = new Size(1000, 680);
            MinimumSize = new Size(700, 460);
            StartPosition = FormStartPosition.CenterScreen;
            try
            {
                Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            }
            catch (Exception)
            {
            }

            BuildToolbar();
            BuildStatusStrip();
            BuildContextMenu();
            view.Dock = DockStyle.Fill;
            Controls.Add(view);
            Controls.Add(toolbar);
            Controls.Add(statusStrip);

            Settings.Load();
            view.Bright = Settings.Bright;
            UpdateBrightTexts();

            badgeTimer.Interval = 500;
            badgeTimer.Tick += delegate { UpdateBadge(); };
            badgeTimer.Start();

            string address = startAddress ?? Settings.Address;
            string error;
            info = PairingInfo.Parse(address, out error);
            if (info != null && startAddress != null)
            {
                Settings.Address = info.ToUri();
                Settings.Save();
            }
            UpdateButtons();
            UpdateBadge();
            Shown += delegate
            {
                if (info != null)
                {
                    Connect();
                }
                else
                {
                    ShowNotPaired();
                }
            };
        }

        // ---------------------------------------------------------------- building the window

        private void BuildToolbar()
        {
            toolbar.Dock = DockStyle.Top;
            toolbar.AutoSize = true;
            toolbar.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            toolbar.WrapContents = true;
            toolbar.BackColor = Color.FromArgb(28, 28, 32);
            toolbar.Padding = new Padding(6, 4, 6, 4);

            connectButton = MakeButton("Connect...", delegate { ShowConnectDialog(); });
            cameraButton = MakeButton("Camera...", delegate { ShowCameraMenu(); });
            torchButton = MakeButton("Torch on", delegate { RunCommand(Protocol.CmdTorch, Protocol.ArgToggle); });
            brightButton = MakeButton("Bright", delegate { ToggleBright(); });
            fullscreenButton = MakeButton("Full screen (F11)", delegate { SetFullscreen(!fullscreen); });
            forgetButton = MakeButton("Forget", delegate { ForgetCamera(); });
            exitButton = MakeButton("Exit", delegate { Close(); });
        }

        private Button MakeButton(string text, EventHandler onClick)
        {
            Button b = new Button();
            b.Text = text;
            b.AutoSize = true;
            b.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            b.FlatStyle = FlatStyle.Flat;
            b.UseVisualStyleBackColor = false;
            b.BackColor = ButtonColor;
            b.ForeColor = Color.White;
            b.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
            b.Padding = new Padding(8, 3, 8, 3);
            b.Margin = new Padding(3);
            b.Cursor = Cursors.Hand;
            b.FlatAppearance.BorderColor = Color.FromArgb(121, 134, 203);
            b.FlatAppearance.MouseOverBackColor = Color.FromArgb(92, 107, 192);
            b.FlatAppearance.MouseDownBackColor = Color.FromArgb(40, 53, 147);
            b.Click += onClick;
            b.EnabledChanged += delegate
            {
                b.BackColor = b.Enabled ? ButtonColor : DisabledColor;
                b.ForeColor = b.Enabled ? Color.White : Color.FromArgb(150, 150, 150);
            };
            toolbar.Controls.Add(b);
            return b;
        }

        private void BuildStatusStrip()
        {
            statusStrip.BackColor = Color.FromArgb(28, 28, 32);
            statusStrip.SizingGrip = false;
            statusLabel.ForeColor = Color.FromArgb(210, 210, 210);
            statusLabel.Spring = true;
            statusLabel.TextAlign = ContentAlignment.MiddleLeft;
            statusStrip.Items.Add(statusLabel);
        }

        private void BuildContextMenu()
        {
            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add("Connect...", null, delegate { ShowConnectDialog(); });
            menu.Items.Add("Reset zoom", null, delegate { view.ResetZoom(); });
            menuBright = new ToolStripMenuItem("Bright", null, delegate { ToggleBright(); });
            menu.Items.Add(menuBright);
            menuFullscreen = new ToolStripMenuItem("Full screen", null, delegate { SetFullscreen(!fullscreen); });
            menu.Items.Add(menuFullscreen);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Exit", null, delegate { Close(); });
            view.ContextMenuStrip = menu;
        }

        // ---------------------------------------------------------------- pairing and connection

        private void ShowConnectDialog()
        {
            string initial = info != null ? info.ToUri() : "";
            if (initial.Length == 0 && Clipboard.ContainsText())
            {
                string clip = Clipboard.GetText().Trim();
                if (clip.StartsWith(Protocol.Scheme + "://", StringComparison.OrdinalIgnoreCase))
                {
                    initial = clip;
                }
            }
            using (ConnectDialog dialog = new ConnectDialog(initial))
            {
                if (dialog.ShowDialog(this) == DialogResult.OK && dialog.Result != null)
                {
                    Pair(dialog.Result);
                }
            }
        }

        private void Pair(PairingInfo newInfo)
        {
            Disconnect();
            view.ClearFrame();
            info = newInfo;
            Settings.Address = newInfo.ToUri();
            Settings.Save();
            Connect();
        }

        private void ForgetCamera()
        {
            Disconnect();
            info = null;
            Settings.Address = null;
            Settings.Save();
            view.ClearFrame();
            ShowNotPaired();
            UpdateButtons();
            UpdateBadge();
        }

        private void ShowNotPaired()
        {
            view.Message = "Not connected.\r\n\r\nClick Connect and type the address shown on the camera phone.";
            SetStatus("Not connected");
        }

        private void Connect()
        {
            if (info == null)
            {
                return;
            }
            Disconnect();
            view.Message = "Connecting to " + info.Host + "...";
            SetStatus("Connecting to " + info.Host + "...");
            StreamClient c = new StreamClient(info);
            client = c;
            string host = info.Host;
            c.Connected += delegate
            {
                Post(delegate
                {
                    if (c != client)
                    {
                        return;
                    }
                    connected = true;
                    connectedAtMs = clock.ElapsedMilliseconds;
                    SetStatus("Connected to " + host);
                    UpdateButtons();
                    UpdateBadge();
                    FetchStatus(0);
                });
            };
            c.FrameReceived += delegate(byte[] jpeg, long cameraTimeMs) { OnFrameBytes(c, jpeg, cameraTimeMs); };
            c.Disconnected += delegate(string reason)
            {
                Post(delegate
                {
                    if (c != client)
                    {
                        return;
                    }
                    connected = false;
                    SetStatus(reason + ". Retrying...");
                    if (!view.HasFrame)
                    {
                        view.Message = reason + ".\r\nRetrying...";
                    }
                    UpdateButtons();
                    UpdateBadge();
                });
            };
            c.Start();
            UpdateButtons();
            UpdateBadge();
        }

        private void Disconnect()
        {
            if (client != null)
            {
                client.Stop();
                client = null;
            }
            connected = false;
            hasTorch = false;
            torchOn = false;
            FramePacket old = Interlocked.Exchange(ref pending, null);
            if (old != null && old.Image != null)
            {
                old.Image.Dispose();
            }
            lastFrameArrivalMs = 0;
            UpdateButtons();
        }

        // ---------------------------------------------------------------- frames

        /// <summary>Runs on the network thread: decode, then hand over only the newest frame.</summary>
        private void OnFrameBytes(StreamClient source, byte[] jpeg, long cameraTimeMs)
        {
            Bitmap bitmap;
            try
            {
                using (MemoryStream ms = new MemoryStream(jpeg))
                using (Image decoded = Image.FromStream(ms))
                {
                    bitmap = new Bitmap(decoded);
                }
            }
            catch (Exception)
            {
                return; // a damaged frame is simply skipped
            }
            FramePacket packet = new FramePacket();
            packet.Image = bitmap;
            packet.CameraTimeMs = cameraTimeMs;
            FramePacket old = Interlocked.Exchange(ref pending, packet);
            if (old != null)
            {
                old.Image.Dispose(); // a newer frame replaced it before it was shown
            }
            else
            {
                Post(delegate { ShowPendingFrame(source); });
            }
        }

        private void ShowPendingFrame(StreamClient source)
        {
            FramePacket packet = Interlocked.Exchange(ref pending, null);
            if (packet == null)
            {
                return;
            }
            if (source != client)
            {
                packet.Image.Dispose();
                return;
            }
            view.SetFrame(packet.Image);
            lastFrameArrivalMs = clock.ElapsedMilliseconds;
            lastCameraTimeMs = packet.CameraTimeMs;
            UpdateBadge();
        }

        // ---------------------------------------------------------------- live badge

        /// <summary>
        /// Shows whether the stream is alive. The badge is not part of the zoomed picture:
        /// green with the camera's capture time while frames arrive, amber while connecting,
        /// red when no frame has arrived for a few seconds.
        /// </summary>
        private void UpdateBadge()
        {
            if (info == null)
            {
                view.SetBadge(null, Color.Gray);
                return;
            }
            long now = clock.ElapsedMilliseconds;
            bool haveFrame = connected && lastFrameArrivalMs > 0 && lastFrameArrivalMs >= connectedAtMs;
            string text;
            Color color;
            if (!connected)
            {
                color = WaitColor;
                text = "CONNECTING";
            }
            else if (haveFrame && now - lastFrameArrivalMs <= StallMs)
            {
                DateTime t = lastCameraTimeMs > 0
                        ? new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc).AddMilliseconds(lastCameraTimeMs).ToLocalTime()
                        : DateTime.Now;
                color = LiveColor;
                text = "LIVE  " + t.ToString("HH:mm:ss");
            }
            else if (!haveFrame && now - connectedAtMs <= StallMs)
            {
                color = WaitColor;
                text = "CONNECTING";
            }
            else
            {
                long stalledSince = haveFrame ? lastFrameArrivalMs : connectedAtMs;
                color = StalledColor;
                text = "NO VIDEO " + ((now - stalledSince) / 1000) + "s";
            }
            view.SetBadge(text, color);
        }

        // ---------------------------------------------------------------- camera controls

        /// <summary>Asks the camera phone for its state (torch, camera list) after a delay.</summary>
        private void FetchStatus(int delayMs)
        {
            PairingInfo target = info;
            if (target == null)
            {
                return;
            }
            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    if (delayMs > 0)
                    {
                        Thread.Sleep(delayMs);
                    }
                    string status = StreamClient.SendCommand(target, Protocol.CmdStatus, null);
                    Post(delegate { ApplyStatus(status); });
                }
                catch (Exception)
                {
                    // the next status request will try again
                }
            });
        }

        private void ApplyStatus(string status)
        {
            bool torch = false;
            bool flash = false;
            foreach (string line in status.Split('\n'))
            {
                string t = line.Trim();
                if (t == "torch=1")
                {
                    torch = true;
                }
                else if (t == "hasTorch=1")
                {
                    flash = true;
                }
            }
            torchOn = torch;
            hasTorch = flash;
            UpdateButtons();
        }

        private void RunCommand(string name, string arg)
        {
            PairingInfo target = info;
            if (target == null)
            {
                return;
            }
            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    ApplyStatusLater(StreamClient.SendCommand(target, name, arg));
                }
                catch (Exception)
                {
                    Post(delegate { SetStatus("Could not reach the camera."); });
                }
            });
            // The camera applies the change a moment later; read the state again afterwards.
            FetchStatus(900);
        }

        private void ApplyStatusLater(string status)
        {
            Post(delegate { ApplyStatus(status); });
        }

        private void ShowCameraMenu()
        {
            PairingInfo target = info;
            if (target == null)
            {
                return;
            }
            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    string status = StreamClient.SendCommand(target, Protocol.CmdStatus, null);
                    List<CameraOption> options = CameraOption.ParseAll(status);
                    string current = CameraOption.ParseCurrent(status);
                    Post(delegate { PresentCameraMenu(target, options, current); });
                }
                catch (Exception)
                {
                    Post(delegate { SetStatus("Could not reach the camera."); });
                }
            });
        }

        private void PresentCameraMenu(PairingInfo target, List<CameraOption> options, string current)
        {
            if (options.Count == 0)
            {
                SetStatus("The camera phone reports no cameras.");
                return;
            }
            ContextMenuStrip menu = new ContextMenuStrip();
            foreach (CameraOption option in options)
            {
                CameraOption chosen = option;
                ToolStripMenuItem item = new ToolStripMenuItem(option.Label);
                item.Checked = option.Id == current;
                item.Click += delegate { RunCommand(Protocol.CmdSelectCamera, chosen.Id); };
                menu.Items.Add(item);
            }
            menu.Show(cameraButton, new Point(0, cameraButton.Height));
        }

        // ---------------------------------------------------------------- window state

        private void ToggleBright()
        {
            view.Bright = !view.Bright;
            Settings.Bright = view.Bright;
            Settings.Save();
            UpdateBrightTexts();
        }

        private void UpdateBrightTexts()
        {
            string text = view.Bright ? "Normal" : "Bright";
            if (brightButton != null)
            {
                brightButton.Text = text;
            }
            if (menuBright != null)
            {
                menuBright.Text = text;
            }
        }

        private void SetFullscreen(bool on)
        {
            if (on == fullscreen)
            {
                return;
            }
            fullscreen = on;
            if (on)
            {
                savedBounds = Bounds;
                savedState = WindowState;
                savedStyle = FormBorderStyle;
                toolbar.Visible = false;
                statusStrip.Visible = false;
                if (WindowState == FormWindowState.Maximized)
                {
                    WindowState = FormWindowState.Normal; // lets the window cover the taskbar
                }
                FormBorderStyle = FormBorderStyle.None;
                WindowState = FormWindowState.Maximized;
            }
            else
            {
                FormBorderStyle = savedStyle;
                WindowState = savedState;
                if (savedState == FormWindowState.Normal)
                {
                    Bounds = savedBounds;
                }
                toolbar.Visible = true;
                statusStrip.Visible = true;
            }
            string text = on ? "Exit full screen" : "Full screen";
            fullscreenButton.Text = on ? "Exit full screen (F11)" : "Full screen (F11)";
            menuFullscreen.Text = text;
        }

        protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
        {
            if (keyData == Keys.F11)
            {
                SetFullscreen(!fullscreen);
                return true;
            }
            if (keyData == Keys.Escape && fullscreen)
            {
                SetFullscreen(false);
                return true;
            }
            return base.ProcessCmdKey(ref msg, keyData);
        }

        private void UpdateButtons()
        {
            cameraButton.Enabled = connected;
            torchButton.Enabled = connected && hasTorch;
            torchButton.Text = torchOn ? "Torch off" : "Torch on";
            forgetButton.Enabled = info != null;
        }

        private void SetStatus(string text)
        {
            statusLabel.Text = text;
        }

        /// <summary>Runs the action on the UI thread (safe to call from any thread).</summary>
        private void Post(Action action)
        {
            if (IsDisposed || !IsHandleCreated)
            {
                return;
            }
            try
            {
                BeginInvoke(action);
            }
            catch (InvalidOperationException)
            {
                // window is closing
            }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            badgeTimer.Stop();
            Disconnect();
            Settings.Bright = view.Bright;
            Settings.Save();
            base.OnFormClosing(e);
        }
    }
}
