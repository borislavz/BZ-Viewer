using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Windows.Forms;

namespace BZView.Viewer
{
    /// <summary>
    /// Shows the live picture. Mouse wheel zooms, dragging pans while zoomed, double-click zooms
    /// in or resets. The LIVE badge is drawn on top and is not affected by zoom.
    /// </summary>
    internal sealed class VideoView : Control
    {
        private const float MaxZoom = 8f;
        private const float DoubleClickZoom = 2.5f;
        private const float BrightGain = 1.35f;
        private const float BrightOffset = 25f / 255f;

        private readonly object sync = new object();
        private readonly ImageAttributes brightAttributes = new ImageAttributes();
        private Bitmap frame;

        private float scale = 1f;
        private float panX;
        private float panY;
        private bool dragging;
        private Point dragStart;
        private float panStartX;
        private float panStartY;

        private bool bright;
        private string message = "";
        private string badgeText;
        private Color badgeColor = Color.Gray;
        private Font badgeFont;
        private Font messageFont;

        public VideoView()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint
                    | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw
                    | ControlStyles.Selectable, true);
            BackColor = Color.Black;
            TabStop = true;
            ColorMatrix matrix = new ColorMatrix(new float[][]
            {
                new float[] { BrightGain, 0, 0, 0, 0 },
                new float[] { 0, BrightGain, 0, 0, 0 },
                new float[] { 0, 0, BrightGain, 0, 0 },
                new float[] { 0, 0, 0, 1, 0 },
                new float[] { BrightOffset, BrightOffset, BrightOffset, 0, 1 }
            });
            brightAttributes.SetColorMatrix(matrix);
        }

        public bool Bright
        {
            get { return bright; }
            set
            {
                bright = value;
                Invalidate();
            }
        }

        /// <summary>Text shown in the middle while there is no picture.</summary>
        public string Message
        {
            get { return message; }
            set
            {
                message = value ?? "";
                Invalidate();
            }
        }

        public bool HasFrame
        {
            get
            {
                lock (sync)
                {
                    return frame != null;
                }
            }
        }

        public bool Zoomed
        {
            get { return scale > 1.001f; }
        }

        /// <summary>Sets the badge in the top middle; null hides it.</summary>
        public void SetBadge(string text, Color color)
        {
            if (text == badgeText && color == badgeColor)
            {
                return;
            }
            badgeText = text;
            badgeColor = color;
            Invalidate();
        }

        /// <summary>Takes ownership of the bitmap and shows it. Call on the UI thread.</summary>
        public void SetFrame(Bitmap bitmap)
        {
            Bitmap old;
            bool sizeChanged;
            lock (sync)
            {
                old = frame;
                sizeChanged = old == null || bitmap == null
                        || old.Width != bitmap.Width || old.Height != bitmap.Height;
                frame = bitmap;
            }
            if (old != null)
            {
                old.Dispose();
            }
            if (sizeChanged)
            {
                ClampPan(); // picture shape changed (for example another camera)
            }
            Invalidate();
        }

        public void ClearFrame()
        {
            SetFrame(null);
            ResetZoom();
        }

        public void ResetZoom()
        {
            scale = 1f;
            panX = 0f;
            panY = 0f;
            Invalidate();
        }

        // ---------------------------------------------------------------- zoom and pan

        private float FitScale(Bitmap bmp)
        {
            return Math.Min(Width / (float)bmp.Width, Height / (float)bmp.Height);
        }

        private void ZoomAt(float newScale, float px, float py)
        {
            float s = Math.Max(1f, Math.Min(MaxZoom, newScale));
            float ratio = s / scale;
            float cx = px - Width / 2f;
            float cy = py - Height / 2f;
            panX = cx - (cx - panX) * ratio;
            panY = cy - (cy - panY) * ratio;
            scale = s;
            if (scale < 1.05f)
            {
                scale = 1f;
                panX = 0f;
                panY = 0f;
            }
            ClampPan();
            Invalidate();
        }

        /// <summary>Keeps the picture from being dragged out of view.</summary>
        private void ClampPan()
        {
            float maxX = 0f;
            float maxY = 0f;
            lock (sync)
            {
                if (frame != null && Width > 0 && Height > 0)
                {
                    float fit = FitScale(frame);
                    maxX = Math.Max(0f, (frame.Width * fit * scale - Width) / 2f);
                    maxY = Math.Max(0f, (frame.Height * fit * scale - Height) / 2f);
                }
            }
            panX = Math.Max(-maxX, Math.Min(maxX, panX));
            panY = Math.Max(-maxY, Math.Min(maxY, panY));
        }

        protected override void OnMouseEnter(EventArgs e)
        {
            base.OnMouseEnter(e);
            Focus(); // so the mouse wheel reaches this control
        }

        protected override void OnMouseWheel(MouseEventArgs e)
        {
            base.OnMouseWheel(e);
            if (!HasFrame)
            {
                return;
            }
            float factor = e.Delta > 0 ? 1.15f : 1f / 1.15f;
            ZoomAt(scale * factor, e.X, e.Y);
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            if (e.Button == MouseButtons.Left && scale > 1f)
            {
                dragging = true;
                dragStart = e.Location;
                panStartX = panX;
                panStartY = panY;
                Cursor = Cursors.SizeAll;
            }
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            base.OnMouseMove(e);
            if (dragging)
            {
                panX = panStartX + (e.X - dragStart.X);
                panY = panStartY + (e.Y - dragStart.Y);
                ClampPan();
                Invalidate();
            }
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            base.OnMouseUp(e);
            if (dragging)
            {
                dragging = false;
                Cursor = Cursors.Default;
            }
        }

        protected override void OnMouseDoubleClick(MouseEventArgs e)
        {
            base.OnMouseDoubleClick(e);
            if (e.Button != MouseButtons.Left || !HasFrame)
            {
                return;
            }
            if (scale > 1f)
            {
                ResetZoom();
            }
            else
            {
                ZoomAt(DoubleClickZoom, e.X, e.Y);
            }
        }

        protected override void OnSizeChanged(EventArgs e)
        {
            base.OnSizeChanged(e);
            ClampPan();
        }

        // ---------------------------------------------------------------- painting

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.Clear(Color.Black);
            bool drawn = false;
            lock (sync)
            {
                if (frame != null && Width > 0 && Height > 0)
                {
                    DrawFrame(g, frame);
                    drawn = true;
                }
            }
            if (!drawn)
            {
                DrawMessage(g);
            }
            DrawBadge(g);
        }

        private void DrawFrame(Graphics g, Bitmap bmp)
        {
            float fit = FitScale(bmp);
            float dw = bmp.Width * fit * scale;
            float dh = bmp.Height * fit * scale;
            float x = Width / 2f + panX - dw / 2f;
            float y = Height / 2f + panY - dh / 2f;
            g.InterpolationMode = InterpolationMode.Bilinear;
            g.PixelOffsetMode = PixelOffsetMode.Half;
            PointF[] destination = new PointF[] { new PointF(x, y), new PointF(x + dw, y), new PointF(x, y + dh) };
            RectangleF source = new RectangleF(0, 0, bmp.Width, bmp.Height);
            g.DrawImage(bmp, destination, source, GraphicsUnit.Pixel, bright ? brightAttributes : null);
        }

        private void DrawMessage(Graphics g)
        {
            if (message.Length == 0)
            {
                return;
            }
            if (messageFont == null)
            {
                messageFont = new Font("Segoe UI", 12f);
            }
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;
            using (StringFormat format = new StringFormat())
            {
                format.Alignment = StringAlignment.Center;
                format.LineAlignment = StringAlignment.Center;
                RectangleF area = new RectangleF(40, 40, Math.Max(10, Width - 80), Math.Max(10, Height - 80));
                using (Brush brush = new SolidBrush(Color.FromArgb(190, 190, 190)))
                {
                    g.DrawString(message, messageFont, brush, area, format);
                }
            }
        }

        private void DrawBadge(Graphics g)
        {
            if (string.IsNullOrEmpty(badgeText))
            {
                return;
            }
            if (badgeFont == null)
            {
                badgeFont = new Font("Consolas", 13f, FontStyle.Bold);
            }
            float k = g.DpiX / 96f;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAlias;
            SizeF text = g.MeasureString(badgeText, badgeFont, PointF.Empty, StringFormat.GenericTypographic);
            float dot = badgeFont.Height * 0.55f;
            float padX = 12f * k;
            float padY = 5f * k;
            float gap = 8f * k;
            float boxW = padX + dot + gap + text.Width + padX;
            float boxH = Math.Max(text.Height, dot) + padY * 2f;
            float boxX = (Width - boxW) / 2f;
            float boxY = 8f * k;

            using (GraphicsPath path = RoundedRectangle(boxX, boxY, boxW, boxH, boxH / 2.4f))
            using (Brush back = new SolidBrush(Color.FromArgb(204, 0, 0, 0)))
            {
                g.FillPath(back, path);
            }
            using (Brush dotBrush = new SolidBrush(badgeColor))
            {
                g.FillEllipse(dotBrush, boxX + padX, boxY + (boxH - dot) / 2f, dot, dot);
            }
            g.DrawString(badgeText, badgeFont, Brushes.White,
                    boxX + padX + dot + gap, boxY + (boxH - text.Height) / 2f, StringFormat.GenericTypographic);
        }

        private static GraphicsPath RoundedRectangle(float x, float y, float w, float h, float r)
        {
            float d = r * 2f;
            GraphicsPath path = new GraphicsPath();
            path.AddArc(x, y, d, d, 180, 90);
            path.AddArc(x + w - d, y, d, d, 270, 90);
            path.AddArc(x + w - d, y + h - d, d, d, 0, 90);
            path.AddArc(x, y + h - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                lock (sync)
                {
                    if (frame != null)
                    {
                        frame.Dispose();
                        frame = null;
                    }
                }
                brightAttributes.Dispose();
                if (badgeFont != null)
                {
                    badgeFont.Dispose();
                }
                if (messageFont != null)
                {
                    messageFont.Dispose();
                }
            }
            base.Dispose(disposing);
        }
    }
}
