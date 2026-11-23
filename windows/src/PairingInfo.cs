using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;

namespace BZView.Viewer
{
    /// <summary>Address and secret code that let this viewer connect to one camera phone.</summary>
    internal sealed class PairingInfo
    {
        private static readonly Regex HostPattern = new Regex("^[A-Za-z0-9.\\-]+$");
        private static readonly Regex TokenPattern = new Regex("^[A-Za-z0-9]{4,64}$");

        public readonly string Host;
        public readonly int Port;
        public readonly string Token;

        public PairingInfo(string host, int port, string token)
        {
            Host = host;
            Port = port;
            Token = token;
        }

        public string ToUri()
        {
            return Protocol.Scheme + "://" + Host + ":" + Port + "/" + Token;
        }

        /// <summary>
        /// Accepts what the camera app shows on its screen, typed or pasted:
        /// "bzview://192.168.1.20:8765/abcd1234", "192.168.1.20:8765/abcd1234",
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
            return new PairingInfo(host, port, parts[1]);
        }
    }
}
