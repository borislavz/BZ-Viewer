using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace BZView.Viewer
{
    /// <summary>
    /// Connects to the camera phone, delivers JPEG frames and reconnects automatically until
    /// <see cref="Stop"/> is called. Events are raised on the client's own thread.
    /// </summary>
    internal sealed class StreamClient
    {
        private const int MaxFrameBytes = 8 * 1024 * 1024;
        /// <summary>The camera sends a frame or a keep-alive at least every 2 s, so silence means trouble.</summary>
        private const int ReadTimeoutMs = 6000;
        /// <summary>A same-network camera answers at once; if it does not, try the remote route.</summary>
        private const int LocalConnectMs = 2000;
        private const int RemoteConnectMs = 7000;
        private const int SingleRouteConnectMs = 5000;
        /// <summary>While on the remote route, check this often whether the local one works again.</summary>
        private const int LocalProbeIntervalMs = 15000;

        /// <summary>Route that last worked, so control commands go there first.</summary>
        private static volatile string lastGoodRoute;

        /// <summary>Raised just before Connected: true when the remote (VPN) route is in use.</summary>
        public event Action<bool> RouteChosen;
        public event Action Connected;
        public event Action<byte[], long> FrameReceived;
        public event Action<string> Disconnected;

        private volatile PairingInfo info;
        private volatile bool running;
        private volatile bool switchingToLocal;
        private volatile TcpClient socket;
        private Thread thread;

        public StreamClient(PairingInfo info)
        {
            this.info = info;
        }

        /// <summary>
        /// Replaces the pairing details (same camera and code), for example after the camera
        /// reported a remote address. Takes effect the next time the client connects.
        /// </summary>
        public void UpdatePairing(PairingInfo newInfo)
        {
            info = newInfo;
        }

        public void Start()
        {
            lock (this)
            {
                if (running)
                {
                    return;
                }
                running = true;
                thread = new Thread(Run);
                thread.IsBackground = true;
                thread.Name = "bzview-stream";
                thread.Start();
            }
        }

        public void Stop()
        {
            lock (this)
            {
                running = false;
                TcpClient s = socket;
                if (s != null)
                {
                    try
                    {
                        s.Close();
                    }
                    catch (Exception)
                    {
                    }
                }
                thread = null;
            }
        }

        private static int ConnectTimeout(List<Route> routes, Route route)
        {
            if (routes.Count == 1)
            {
                return SingleRouteConnectMs;
            }
            return route.Remote ? RemoteConnectMs : LocalConnectMs;
        }

        private static void CloseQuietly(TcpClient tcp)
        {
            if (tcp == null)
            {
                return;
            }
            try
            {
                tcp.Close();
            }
            catch (Exception)
            {
            }
        }

        private void Sleep(int ms)
        {
            for (int waited = 0; running && waited < ms; waited += 100)
            {
                Thread.Sleep(100);
            }
        }

        /// <summary>
        /// Each round tries the local route first and then the remote one, so the viewer is back
        /// on the local network as soon as the PC is home again.
        /// </summary>
        private void Run()
        {
            int backoffMs = 1000;
            while (running)
            {
                switchingToLocal = false;
                string reason = "no route";
                TcpClient tcp = null;
                Stream input = null;
                Route used = null;
                PairingInfo pairing = info;
                List<Route> routes = pairing.Routes();

                foreach (Route route in routes)
                {
                    if (!running)
                    {
                        return;
                    }
                    TcpClient candidate = null;
                    try
                    {
                        candidate = Open(route, ConnectTimeout(routes, route));
                        socket = candidate;
                        candidate.NoDelay = true;
                        candidate.ReceiveTimeout = ReadTimeoutMs;
                        NetworkStream ns = candidate.GetStream();
                        SendRequest(ns, Protocol.PathStream + "?t=" + Uri.EscapeDataString(pairing.Token));
                        Stream stream = new BufferedStream(ns, 64 * 1024);
                        // A different device at the same local address (another network) fails here.
                        ReadStatusAndHeaders(stream);
                        tcp = candidate;
                        input = stream;
                        used = route;
                        break;
                    }
                    catch (Exception e)
                    {
                        reason = e.Message;
                        CloseQuietly(candidate);
                    }
                }

                if (tcp == null)
                {
                    socket = null;
                    if (!running)
                    {
                        return;
                    }
                    RaiseDisconnected("Cannot reach camera (" + reason + ")");
                    Sleep(backoffMs);
                    backoffMs = Math.Min(backoffMs * 2, 5000);
                    continue;
                }

                if (!running)
                {
                    CloseQuietly(tcp);
                    return;
                }
                lastGoodRoute = used.Key;
                backoffMs = 1000;
                ManualResetEvent streamEnded = new ManualResetEvent(false);
                if (used.Remote && !routes[0].Remote)
                {
                    StartLocalProbe(tcp, routes[0], pairing.Token, streamEnded);
                }
                try
                {
                    Action<bool> onRoute = RouteChosen;
                    if (onRoute != null)
                    {
                        onRoute(used.Remote);
                    }
                    Action onConnected = Connected;
                    if (onConnected != null)
                    {
                        onConnected();
                    }
                    ReadFrames(input);
                    reason = "Connection closed";
                }
                catch (Exception e)
                {
                    reason = e.Message;
                }
                finally
                {
                    streamEnded.Set();
                    socket = null;
                    CloseQuietly(tcp);
                }

                if (!running)
                {
                    return;
                }
                if (switchingToLocal)
                {
                    RaiseDisconnected("Switching to local network");
                    continue; // reconnect at once; the local route is tried first
                }
                RaiseDisconnected(reason);
                Sleep(backoffMs);
                backoffMs = Math.Min(backoffMs * 2, 5000);
            }
        }

        private void RaiseDisconnected(string reason)
        {
            Action<string> onDisconnected = Disconnected;
            if (onDisconnected != null)
            {
                onDisconnected(reason);
            }
        }

        /// <summary>While streaming over the remote route, notices when the local route works again.</summary>
        private void StartLocalProbe(TcpClient current, Route local, string token, ManualResetEvent streamEnded)
        {
            Thread probe = new Thread(delegate()
            {
                while (!streamEnded.WaitOne(LocalProbeIntervalMs))
                {
                    if (!running)
                    {
                        return;
                    }
                    try
                    {
                        SendCommand(local, token, Protocol.CmdStatus, null, 1200);
                    }
                    catch (Exception)
                    {
                        continue; // still away from home
                    }
                    switchingToLocal = true;
                    CloseQuietly(current);
                    return;
                }
            });
            probe.IsBackground = true;
            probe.Name = "bzview-route-probe";
            probe.Start();
        }

        private void ReadFrames(Stream input)
        {
            while (running)
            {
                string line;
                do
                {
                    line = ReadLine(input);
                    if (line == null)
                    {
                        return;
                    }
                }
                while (!line.StartsWith("--"));

                int length = -1;
                long cameraTimeMs = 0;
                while (true)
                {
                    line = ReadLine(input);
                    if (line == null)
                    {
                        return;
                    }
                    if (line.Length == 0)
                    {
                        break;
                    }
                    if (StartsWithIgnoreCase(line, "content-length:"))
                    {
                        if (!int.TryParse(line.Substring(15).Trim(), out length))
                        {
                            throw new IOException("Bad frame header");
                        }
                    }
                    else if (StartsWithIgnoreCase(line, Protocol.HeaderFrameTime + ":"))
                    {
                        if (!long.TryParse(line.Substring(Protocol.HeaderFrameTime.Length + 1).Trim(),
                                out cameraTimeMs))
                        {
                            cameraTimeMs = 0;
                        }
                    }
                }
                if (length <= 0 || length > MaxFrameBytes)
                {
                    throw new IOException("Bad frame length");
                }
                byte[] frame = new byte[length];
                int read = 0;
                while (read < length)
                {
                    int n = input.Read(frame, read, length - read);
                    if (n <= 0)
                    {
                        throw new EndOfStreamException("Stream ended");
                    }
                    read += n;
                }
                Action<byte[], long> onFrame = FrameReceived;
                if (onFrame != null)
                {
                    onFrame(frame, cameraTimeMs);
                }
            }
        }

        /// <summary>
        /// Sends one control command and returns the camera's status text. Uses the route that
        /// last worked first, then the other one.
        /// </summary>
        public static string SendCommand(PairingInfo info, string name, string arg)
        {
            List<Route> routes = info.Routes();
            string good = lastGoodRoute;
            if (good != null)
            {
                for (int i = 1; i < routes.Count; i++)
                {
                    if (routes[i].Key == good)
                    {
                        Route r = routes[i];
                        routes.RemoveAt(i);
                        routes.Insert(0, r);
                        break;
                    }
                }
            }
            Exception last = null;
            foreach (Route route in routes)
            {
                int connectMs = routes.Count == 1 ? 4000 : (route.Remote ? 6000 : 2000);
                try
                {
                    string body = SendCommand(route, info.Token, name, arg, connectMs);
                    lastGoodRoute = route.Key;
                    return body;
                }
                catch (Exception e)
                {
                    last = e;
                }
            }
            throw last ?? new IOException("No route");
        }

        private static string SendCommand(Route route, string token, string name, string arg, int connectMs)
        {
            using (TcpClient tcp = Open(route, connectMs))
            {
                tcp.ReceiveTimeout = Math.Max(4000, connectMs);
                tcp.SendTimeout = 4000;
                NetworkStream ns = tcp.GetStream();
                string path = Protocol.PathCommand + "?t=" + Uri.EscapeDataString(token)
                        + "&name=" + Uri.EscapeDataString(name)
                        + (arg != null ? "&arg=" + Uri.EscapeDataString(arg) : "");
                SendRequest(ns, path);
                Stream input = new BufferedStream(ns);
                ReadStatusAndHeaders(input);
                using (MemoryStream body = new MemoryStream())
                {
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = input.Read(buf, 0, buf.Length)) > 0)
                    {
                        body.Write(buf, 0, n);
                    }
                    return Encoding.UTF8.GetString(body.ToArray());
                }
            }
        }

        private static TcpClient Open(Route route, int timeoutMs)
        {
            TcpClient client = new TcpClient();
            try
            {
                IAsyncResult result = client.BeginConnect(route.Host, route.Port, null, null);
                if (!result.AsyncWaitHandle.WaitOne(timeoutMs))
                {
                    client.Close();
                    throw new IOException("timed out");
                }
                client.EndConnect(result);
                return client;
            }
            catch (Exception)
            {
                client.Close();
                throw;
            }
        }

        private static void SendRequest(Stream output, string path)
        {
            byte[] bytes = Encoding.ASCII.GetBytes(
                    "GET " + path + " HTTP/1.1\r\nConnection: close\r\n\r\n");
            output.Write(bytes, 0, bytes.Length);
            output.Flush();
        }

        private static void ReadStatusAndHeaders(Stream input)
        {
            string status = ReadLine(input);
            if (status == null)
            {
                throw new EndOfStreamException("No response");
            }
            if (!status.Contains(" 200"))
            {
                if (status.Contains(" 403"))
                {
                    throw new IOException("Wrong pairing code");
                }
                throw new IOException("Camera replied: " + status);
            }
            string line;
            while ((line = ReadLine(input)) != null && line.Length > 0)
            {
                // skip headers
            }
        }

        /// <summary>Reads one line without CR/LF. Returns null at end of stream.</summary>
        private static string ReadLine(Stream input)
        {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = input.ReadByte()) >= 0)
            {
                if (c == '\n')
                {
                    int len = sb.Length;
                    if (len > 0 && sb[len - 1] == '\r')
                    {
                        sb.Length = len - 1;
                    }
                    return sb.ToString();
                }
                sb.Append((char)c);
                if (sb.Length > 8192)
                {
                    throw new IOException("Line too long");
                }
            }
            return sb.Length == 0 ? null : sb.ToString();
        }

        private static bool StartsWithIgnoreCase(string text, string prefix)
        {
            return text.StartsWith(prefix, StringComparison.OrdinalIgnoreCase);
        }
    }
}
