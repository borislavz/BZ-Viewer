namespace BZView.Viewer
{
    /// <summary>Wire protocol shared with the Android camera app (see core/Protocol.java).</summary>
    internal static class Protocol
    {
        public const int DefaultPort = 8765;
        public const string Scheme = "bzview";
        public const string PathStream = "/stream";
        public const string PathCommand = "/cmd";
        public const string CmdStatus = "status";
        public const string CmdSelectCamera = "select";
        public const string CmdTorch = "torch";
        public const string ArgToggle = "toggle";

        /// <summary>Per-frame header: the camera phone's clock (epoch milliseconds) at capture.</summary>
        public const string HeaderFrameTime = "X-Frame-Time";
    }
}
