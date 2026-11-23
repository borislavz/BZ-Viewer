using System;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

[assembly: AssemblyTitle("BZ View Viewer")]
[assembly: AssemblyProduct("BZ View")]
[assembly: AssemblyCompany("borislav007")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

namespace BZView.Viewer
{
    internal static class Program
    {
        /// <summary>
        /// Optional first argument: a camera address (bzview://host:port/code) to connect to
        /// right away.
        /// </summary>
        [STAThread]
        private static void Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
            Application.ThreadException += delegate(object sender, ThreadExceptionEventArgs e)
            {
                MessageBox.Show(e.Exception.Message, "BZ View Viewer",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);
            };
            string startAddress = args.Length > 0 ? args[0] : null;
            Application.Run(new MainForm(startAddress));
        }
    }
}
