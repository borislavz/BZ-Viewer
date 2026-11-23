using System.Collections.Generic;

namespace BZView.Viewer
{
    /// <summary>One camera lens exposed by the camera phone, e.g. "Back, 4.4 mm (id 0)".</summary>
    internal sealed class CameraOption
    {
        public readonly string Id;
        public readonly string Label;

        public CameraOption(string id, string label)
        {
            Id = id;
            Label = label;
        }

        /// <summary>Parses the "camera=ID|LABEL" lines of a status reply.</summary>
        public static List<CameraOption> ParseAll(string status)
        {
            List<CameraOption> list = new List<CameraOption>();
            foreach (string line in status.Split('\n'))
            {
                string t = line.Trim();
                if (t.StartsWith("camera="))
                {
                    string rest = t.Substring("camera=".Length);
                    int bar = rest.IndexOf('|');
                    if (bar > 0)
                    {
                        list.Add(new CameraOption(rest.Substring(0, bar), rest.Substring(bar + 1)));
                    }
                }
            }
            return list;
        }

        /// <summary>Id of the camera currently in use, or null if unknown.</summary>
        public static string ParseCurrent(string status)
        {
            foreach (string line in status.Split('\n'))
            {
                string t = line.Trim();
                if (t.StartsWith("current="))
                {
                    string id = t.Substring("current=".Length);
                    return id.Length == 0 ? null : id;
                }
            }
            return null;
        }
    }
}
