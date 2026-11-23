using System;
using System.Collections.Generic;
using System.IO;

namespace BZView.Viewer
{
    /// <summary>Remembers the last camera address and the brightness choice between runs.</summary>
    internal static class Settings
    {
        private static string FilePath
        {
            get
            {
                string dir = Path.Combine(
                        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                        "BZ View Viewer");
                return Path.Combine(dir, "settings.txt");
            }
        }

        public static string Address { get; set; }
        public static bool Bright { get; set; }

        public static void Load()
        {
            Address = null;
            Bright = false;
            try
            {
                if (!File.Exists(FilePath))
                {
                    return;
                }
                foreach (string line in File.ReadAllLines(FilePath))
                {
                    int eq = line.IndexOf('=');
                    if (eq <= 0)
                    {
                        continue;
                    }
                    string key = line.Substring(0, eq).Trim();
                    string value = line.Substring(eq + 1).Trim();
                    if (key == "address")
                    {
                        Address = value.Length == 0 ? null : value;
                    }
                    else if (key == "bright")
                    {
                        Bright = value == "1";
                    }
                }
            }
            catch (Exception)
            {
                // unreadable settings are simply ignored
            }
        }

        public static void Save()
        {
            try
            {
                string path = FilePath;
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                List<string> lines = new List<string>();
                if (Address != null)
                {
                    lines.Add("address=" + Address);
                }
                lines.Add("bright=" + (Bright ? "1" : "0"));
                File.WriteAllLines(path, lines.ToArray());
            }
            catch (Exception)
            {
                // not being able to remember is not fatal
            }
        }
    }
}
