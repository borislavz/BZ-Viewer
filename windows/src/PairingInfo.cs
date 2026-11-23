using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;

namespace BZView.Viewer
{
    /// <summary>One way to reach the camera: its Wi-Fi address or its remote (VPN) address.</summary>
    internal sealed class Route
    {
        public readonly string Host;
        public readonly int Port;
        /// <summary>True for the internet/VPN route, false for the same-network route.</summary>
        public readonly bool Remote;

        public Route(string host, int port, bool remote)
        {
            Host = host;
            Port = port;
            Remote = remote;
        }

        public string Key
        {
            get { return Host + ":" + Port; }
        }
    }

    /// <summary>Address and secret code that let this viewer connect to one camera phone.</summary>
    internal sealed class PairingInfo
    {
        private static readonly Regex HostPattern = new Regex("^[A-Za-z0-9.\\-]+$");
        private static readonly Regex TokenPattern = new Regex("^[A-Za-z0-9]{4,64}$");

        public readonly string Host;
        public readonly int Port;
        public readonly string Token;
        /// <summary>VPN / internet address of the camera, or null when it has none.</summary>
        public readonly string RemoteHost;
        public readonly int RemotePort;

        public PairingInfo(string host, int port, string token)
            : this(host, port, token, null, port)
        {
        }

        public PairingInfo(string host, int port, string token, string remoteHost, int remotePort)
        {
            Host = host;
            Port = port;
            Token = token;
            RemoteHost = remoteHost;
            RemotePort = remotePort;
        }

        /// <summary>Routes in the order to try them: local first, then remote.</summary>
        public List<Route> Routes()
        {
            List<Route> list = new List<Route>();
            list.Add(new Route(Host, Port, IsVpnAddress(Host)));
            if (RemoteHost != null && !(RemoteHost == Host && RemotePort == Port))
            {
                list.Add(new Route(RemoteHost, RemotePort, true));
            }
            return list;
        }

        public string ToUri()
        {
            string uri = Protocol.Scheme + "://" + Host + ":" + Port + "/" + Token;
            if (RemoteHost != null)
            {
                uri += "?r=" + RemoteHost + (RemotePort != Port ? ":" + RemotePort : "");
            }
            return uri;
        }

        /// <summary>
        /// True for addresses that only exist inside a VPN overlay (Tailscale uses 100.64.0.0/10
        /// and *.ts.net names), so a route to them counts as remote even when it is the only one.
        /// </summary>
        public static bool IsVpnAddress(string host)
        {
            if (host == null)
            {
                return false;
            }
            if (host.EndsWith(".ts.net", StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
            string[] p = host.Split('.');
            if (p.Length != 4)
            {
                return false;
            }
            int a;
            int b;
            return int.TryParse(p[0], out a) && int.TryParse(p[1], out b)
                    && a == 100 && b >= 64 && b <= 127;
        }

        /// <summary>
        /// Learns the camera's remote address from its status text (a "remote=" line), so a
        /// viewer paired on the local network gets the remote route without typing it.
        /// Returns the updated pairing, or null when the status adds nothing new.
        /// </summary>
        public PairingInfo WithReportedRemote(string statusText)
        {
            if (statusText == null)
            {
                return null;
            }
            foreach (string line in statusText.Split('\n'))
            {
                string t = line.Trim();
                if (t.StartsWith("remote=", StringComparison.Ordinal))
                {
                    string remote = t.Substring(7);
                    if (!HostPattern.IsMatch(remote) || remote == Host || remote == RemoteHost)
                    {
                        return null;
                    }
                    return new PairingInfo(Host, Port, Token, remote, Port);
                }
            }
            return null;
        }

        /// <summary>
        /// Accepts what the camera app shows on its screen, typed or pasted:
        /// "bzview://192.168.1.20:8765/abcd1234?r=100.101.102.103", "192.168.1.20:8765/abcd1234",
        /// "192.168.1.20/abcd1234" or "192.168.1.20 abcd1234". Returns null and an error text
        /// when the input is not usable.
        /// </summary>
        public static PairingInfo Parse(string text, out string error)
        {
            error = null;
            if (text == null || text.Trim().Length == 0)
            {
                error = "Enter the address shown on the camera phone.";
                return null;
            }

            string t = text.Trim();
            string prefix = Protocol.Scheme + "://";
            if (t.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                t = t.Substring(prefix.Length);
            }

            // optional remote address: ...?r=host[:port]
            string remoteHost = null;
            int remotePort = -1;
            int question = t.IndexOf('?');
            if (question >= 0)
            {
                string query = t.Substring(question + 1);
                t = t.Substring(0, question);
                if (!query.StartsWith("r=", StringComparison.Ordinal))
                {
                    error = "The address has an unknown option after the question mark.";
                    return null;
                }
                string r = query.Substring(2).Trim();
                int rc = r.LastIndexOf(':');
                if (rc >= 0)
                {
                    if (!int.TryParse(r.Substring(rc + 1), out remotePort) || remotePort < 1 || remotePort > 65535)
                    {
                        error = "The remote port number is not valid.";
                        return null;
                    }
                    r = r.Substring(0, rc);
                }
                if (!HostPattern.IsMatch(r))
                {
                    error = "The remote address is not valid.";
                    return null;
                }
                remoteHost = r;
            }

            List<string> parts = new List<string>();
            foreach (string p in Regex.Split(t, "[\\s/]+"))
            {
                if (p.Length > 0)
                {
                    parts.Add(p);
                }
            }
            if (parts.Count != 2)
            {
                error = "Enter the address and the pairing code, for example "
                        + "bzview://192.168.1.20:8765/abcd1234";
                return null;
            }

            string host = parts[0];
            int port = Protocol.DefaultPort;
            int colon = host.LastIndexOf(':');
            if (colon >= 0)
            {
                string portText = host.Substring(colon + 1);
                host = host.Substring(0, colon);
                if (!int.TryParse(portText, out port) || port < 1 || port > 65535)
                {
                    error = "The port number is not valid.";
                    return null;
                }
            }
            if (!HostPattern.IsMatch(host))
            {
                error = "The address is not valid. Use the IP address shown on the camera phone.";
                return null;
            }
            if (!TokenPattern.IsMatch(parts[1]))
            {
                error = "The pairing code is not valid (letters and digits only).";
                return null;
            }
            return new PairingInfo(host, port, parts[1], remoteHost, remotePort < 0 ? port : remotePort);
        }
    }
}
