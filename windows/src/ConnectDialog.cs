using System;
using System.Drawing;
using System.Windows.Forms;

namespace BZView.Viewer
{
    /// <summary>Asks for the camera address (typed or pasted) instead of scanning a QR code.</summary>
    internal sealed class ConnectDialog : Form
    {
        private readonly TextBox addressBox = new TextBox();
        private readonly Label errorLabel = new Label();

        public PairingInfo Result;

        public ConnectDialog(string initialText)
        {
            AutoScaleMode = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96f, 96f);
            Text = "Connect to camera";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition = FormStartPosition.CenterParent;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            Font = new Font("Segoe UI", 9.5f);
            AutoSize = true;
            AutoSizeMode = AutoSizeMode.GrowAndShrink;

            TableLayoutPanel table = new TableLayoutPanel();
            table.AutoSize = true;
            table.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            table.ColumnCount = 1;
            table.Padding = new Padding(16);
            table.Dock = DockStyle.Fill;

            Label help = new Label();
            help.AutoSize = true;
            help.MaximumSize = new Size(460, 0);
            help.Text = "On the camera phone, open BZ View Camera and tap Start streaming. "
                    + "Type or paste the address shown under the QR code, for example:\r\n"
                    + "bzview://192.168.1.20:8765/abcd1234\r\n"
                    + "(\"192.168.1.20 abcd1234\" works too.) Both devices must be on the same network.";
            help.Margin = new Padding(0, 0, 0, 10);
            table.Controls.Add(help);

            addressBox.Width = 460;
            addressBox.Text = initialText ?? "";
            addressBox.Margin = new Padding(0, 0, 0, 4);
            table.Controls.Add(addressBox);

            errorLabel.AutoSize = true;
            errorLabel.MaximumSize = new Size(460, 0);
            errorLabel.ForeColor = Color.Firebrick;
            errorLabel.Margin = new Padding(0, 0, 0, 8);
            errorLabel.Text = "";
            table.Controls.Add(errorLabel);

            FlowLayoutPanel buttons = new FlowLayoutPanel();
            buttons.AutoSize = true;
            buttons.FlowDirection = FlowDirection.RightToLeft;
            buttons.Dock = DockStyle.Right;
            buttons.Margin = new Padding(0);

            Button cancel = new Button();
            cancel.Text = "Cancel";
            cancel.AutoSize = true;
            cancel.DialogResult = DialogResult.Cancel;
            Button connect = new Button();
            connect.Text = "Connect";
            connect.AutoSize = true;
            connect.Click += OnConnectClicked;
            Button paste = new Button();
            paste.Text = "Paste";
            paste.AutoSize = true;
            paste.Click += delegate
            {
                if (Clipboard.ContainsText())
                {
                    addressBox.Text = Clipboard.GetText().Trim();
                    addressBox.SelectionStart = addressBox.Text.Length;
                }
                addressBox.Focus();
            };
            buttons.Controls.Add(cancel);
            buttons.Controls.Add(connect);
            buttons.Controls.Add(paste);
            table.Controls.Add(buttons);

            Controls.Add(table);
            AcceptButton = connect;
            CancelButton = cancel;
            Shown += delegate
            {
                addressBox.Focus();
                addressBox.SelectAll();
            };
        }

        private void OnConnectClicked(object sender, EventArgs e)
        {
            string error;
            PairingInfo parsed = PairingInfo.Parse(addressBox.Text, out error);
            if (parsed == null)
            {
                errorLabel.Text = error;
                addressBox.Focus();
                return;
            }
            Result = parsed;
            DialogResult = DialogResult.OK;
            Close();
        }
    }
}
