using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Windows.Forms;

class ScreenCapture {
    [DllImport("user32.dll")]
    private static extern bool SetProcessDPIAware();

    static void Main(string[] args) {
        try {
            // Ensure full DPI awareness so capture covers entire desktop without scaling cropping
            try { SetProcessDPIAware(); } catch {}

            string outputPath = args.Length > 0 ? args[0] : "screenshot.jpg";

            // Capture primary screen (or virtual screen)
            Rectangle bounds = Screen.PrimaryScreen.Bounds;
            using (Bitmap bitmap = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format24bppRgb)) {
                using (Graphics g = Graphics.FromImage(bitmap)) {
                    g.CopyFromScreen(bounds.Location, Point.Empty, bounds.Size, CopyPixelOperation.SourceCopy);
                }

                ImageCodecInfo jpegCodec = null;
                foreach (var codec in ImageCodecInfo.GetImageEncoders()) {
                    if (codec.MimeType == "image/jpeg") {
                        jpegCodec = codec;
                        break;
                    }
                }

                if (jpegCodec != null) {
                    EncoderParameters encoderParams = new EncoderParameters(1);
                    encoderParams.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 80L);
                    bitmap.Save(outputPath, jpegCodec, encoderParams);
                } else {
                    bitmap.Save(outputPath, ImageFormat.Jpeg);
                }
            }
            Console.WriteLine("OK:" + outputPath);
        } catch (Exception ex) {
            Console.Error.WriteLine("ERROR:" + ex.Message);
            Environment.Exit(1);
        }
    }
}
