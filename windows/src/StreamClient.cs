using System;
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

        public event Action Connected;
        public event Action<byte[], long> FrameReceived;
        public event Action<string> Disconnected;

        private readonly PairingInfo info;
        private volatile bool running;
        private volatile TcpClient socket;
        private Thread thread;

        public StreamClient(PairingInfo info)
        {
            this.info = info;
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

        private void Run()
        {
            int backoffMs = 1000;
            while (running)
            {
                string reason;
                bool connected = false;
                TcpClient tcp = null;
                try
                {
                    tcp = Open(info, 5000);
                    socket = tcp;
                    tcp.NoDelay = true;
                    tcp.ReceiveTimeout = 10000;
                    NetworkStream ns = tcp.GetStream();
                    SendRequest(ns, Protocol.PathStream + "?t=" + Uri.EscapeDataString(info.Token));
                    Stream input = new BufferedStream(ns, 64 * 1024);
                    ReadStatusAndHeaders(input);
                    connected = true;
                    backoffMs = 1000;
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
                    socket = null;
                    if (tcp != null)
                    {
                        try
                        {
                            tcp.Close();
                        }
                        catch (Exception)
                        {
                        }
                    }
                }

                if (!running)
                {
                    return;
                }
                Action<string> onDisconnected = Disconnected;
                if (onDisconnected != null)
                {
                    onDisconnected(connected ? reason : "Cannot reach camera (" + reason + ")");
                }
                for (int waited = 0; running && waited < backoffMs; waited += 100)
                {
                    Thread.Sleep(100);
                }
                backoffMs = Math.Min(backoffMs * 2, 5000);
            }
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

        /// <summary>Sends one control command and returns the camera's status text.</summary>
        public static string SendCommand(PairingInfo info, string name, string arg)
        {
            using (TcpClient tcp = Open(info, 4000))
            {
                tcp.ReceiveTimeout = 4000;
                tcp.SendTimeout = 4000;
                NetworkStream ns = tcp.GetStream();
                string path = Protocol.PathCommand + "?t=" + Uri.EscapeDataString(info.Token)
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

        private static TcpClient Open(PairingInfo info, int timeoutMs)
        {
            TcpClient client = new TcpClient();
            try
            {
                IAsyncResult result = client.BeginConnect(info.Host, info.Port, null, null);
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
