using System.Drawing;
using System.Drawing.Drawing2D;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Xml.Linq;
using Microsoft.Win32;

namespace Tenshu.TrayAgent;

/// <summary>
/// The Tenshu Control cat as a tray icon, rendered at run time from the embedded Android themed-icon vector
/// (<c>control/src/main/res/drawable/ic_launcher_monochrome.xml</c>) — one source for both platforms, rather
/// than a pasted copy (the macOS agent embeds a base64 PDF it has to regenerate by hand).
///
/// Windows tray icons are not template images, so the ink is chosen here: white on a dark taskbar, black on a
/// light one (<c>SystemUsesLightTheme</c>). The mark is tight-cropped so it fills the icon, as on macOS.
///
/// ⚠ A <b>silhouette</b>: head and ears only — no whiskers, no eyes, no mouth (the maintainer's call, 2026-11-10,
/// after both the full mark and a whisker-stub crop read as busy at 16–24 px). The vector's strokes (whiskers)
/// are skipped and each filled path keeps only its first figure (the outline; the eyes and mouth are later
/// figures of the head path).
///
/// ⚠ Always full ink, like the macOS template image. A dimmed cat for "paused" or "not running" was tried
/// and read as a grey blob on a dark taskbar — at 16–24 px the eyes and whiskers vanish. State goes in the
/// tooltip and the flyout instead.
///
/// ⚠ Supports exactly what the vector uses — nested groups with translate/scale, and path commands
/// M L H V C Q A Z (absolute and relative) — and throws on anything else, so a redrawn mark fails loudly
/// instead of rendering half a cat.
/// </summary>
internal static class CatIcon
{
    private const string Android = "http://schemas.android.com/apk/res/android";

    private sealed record Shape(GraphicsPath Path, bool Fill, float StrokeWidth);

    private static readonly Lazy<List<Shape>> Shapes = new(Load);

    /// <summary>The square frame's side, relative to the silhouette's larger side: a hair of breathing room.</summary>
    private const float FrameMargin = 1.06f;

    /// <summary>Whether the taskbar uses the light theme (then the cat is drawn black).</summary>
    public static bool TaskbarIsLight()
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        return key?.GetValue("SystemUsesLightTheme") is int value && value == 1;
    }

    /// <summary>An HICON of <paramref name="size"/> pixels; the caller destroys it with <c>DestroyIcon</c>.</summary>
    public static IntPtr CreateHicon(int size)
    {
        using var bitmap = Render(size, TaskbarIsLight() ? Color.Black : Color.White, 255);
        return bitmap.GetHicon();
    }

    /// <summary>
    /// ⚠⚠ Framed on the <b>face</b> (the filled shapes: head and ears, nearly square), not on the whole mark. A
    /// tray icon is always square, while the mark with its whiskers is about 1.6 : 1 — fitting all of it left the
    /// face a squashed strip across the middle. The macOS menu bar can widen a status item instead; Windows cannot.
    /// The whiskers run off the sides, leaving stubs; <see cref="FrameMargin"/> is how much of them shows.
    /// </summary>
    public static Bitmap Render(int size, Color ink, int alpha)
    {
        var shapes = Shapes.Value;
        var face = RectangleF.Empty;
        foreach (var s in shapes.Where(s => s.Fill))
        {
            var b = s.Path.GetBounds();
            face = face.IsEmpty ? b : RectangleF.Union(face, b);
        }
        var side = Math.Max(face.Width, face.Height) * FrameMargin;
        var bounds = new RectangleF(
            face.X + face.Width / 2 - side / 2, face.Y + face.Height / 2 - side / 2, side, side);

        var bitmap = new Bitmap(size, size, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(bitmap);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        var scale = size / Math.Max(bounds.Width, bounds.Height);
        g.TranslateTransform(size / 2f, size / 2f);
        g.ScaleTransform(scale, scale);
        g.TranslateTransform(-(bounds.X + bounds.Width / 2), -(bounds.Y + bounds.Height / 2));

        var color = Color.FromArgb(alpha, ink);
        using var brush = new SolidBrush(color);
        foreach (var s in shapes)
        {
            if (s.Fill)
            {
                g.FillPath(brush, s.Path);
            }
            else
            {
                using var pen = new Pen(color, s.StrokeWidth) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round };
                g.DrawPath(pen, s.Path);
            }
        }
        return bitmap;
    }

    // ── Loading the vector ──────────────────────────────────────────────────────────────────────────

    private static List<Shape> Load()
    {
        using var stream = typeof(CatIcon).Assembly.GetManifestResourceStream("cat.xml")
            ?? throw new InvalidOperationException("embedded cat.xml missing");
        var root = XDocument.Load(stream).Root ?? throw new InvalidOperationException("empty cat.xml");
        var shapes = new List<Shape>();
        Walk(root, new Matrix(), shapes);
        return shapes;
    }

    private static void Walk(XElement element, Matrix parent, List<Shape> shapes)
    {
        foreach (var child in element.Elements())
        {
            switch (child.Name.LocalName)
            {
                case "group":
                {
                    // Android applies a group's scale, then its translate: p' = T·S·p, composed onto the parent.
                    var local = new Matrix();
                    local.Translate(Attr(child, "translateX"), Attr(child, "translateY"));
                    local.Scale(Attr(child, "scaleX", 1f), Attr(child, "scaleY", 1f));
                    var combined = parent.Clone();
                    combined.Multiply(local, MatrixOrder.Prepend);
                    Walk(child, combined, shapes);
                    break;
                }
                case "path":
                {
                    var data = (string?)child.Attribute(XName.Get("pathData", Android))
                        ?? throw new InvalidOperationException("path without pathData");
                    // The silhouette: whiskers (stroked paths) are skipped entirely.
                    if (child.Attribute(XName.Get("fillColor", Android)) == null) break;
                    var path = FirstFigure(SvgPath.Parse(data, FillMode.Winding));
                    path.Transform(parent);
                    shapes.Add(new Shape(path, Fill: true, StrokeWidth: 0f));
                    break;
                }
            }
        }
    }

    /// <summary>The first closed figure of <paramref name="path"/> — a shape's outline without the holes cut into it.</summary>
    private static GraphicsPath FirstFigure(GraphicsPath path)
    {
        using var iterator = new GraphicsPathIterator(path);
        var first = new GraphicsPath(FillMode.Winding);
        iterator.NextSubpath(first, out _);
        path.Dispose();
        return first;
    }

    private static float Attr(XElement e, string name, float fallback = 0f) =>
        e.Attribute(XName.Get(name, Android)) is { } a ? float.Parse(a.Value, CultureInfo.InvariantCulture) : fallback;

    [DllImport("user32.dll")]
    public static extern bool DestroyIcon(IntPtr icon);
}

/// <summary>Minimal SVG/Android path-data parser into a GDI+ <see cref="GraphicsPath"/>.</summary>
internal static class SvgPath
{
    public static GraphicsPath Parse(string data, FillMode mode)
    {
        var path = new GraphicsPath(mode);
        var tokens = Tokenize(data);
        var i = 0;
        var cmd = ' ';
        PointF current = default, start = default;

        float Next() => float.Parse(tokens[i++], CultureInfo.InvariantCulture);
        bool HasNumber() => i < tokens.Count && !char.IsLetter(tokens[i][0]);

        while (i < tokens.Count)
        {
            if (char.IsLetter(tokens[i][0])) cmd = tokens[i++][0];
            var rel = char.IsLower(cmd);
            PointF P(float x, float y) => rel ? new PointF(current.X + x, current.Y + y) : new PointF(x, y);

            switch (char.ToUpperInvariant(cmd))
            {
                case 'M':
                    path.StartFigure();
                    current = start = P(Next(), Next());
                    cmd = rel ? 'l' : 'L'; // implicit lineto after the first pair
                    break;
                case 'L':
                {
                    var p = P(Next(), Next());
                    path.AddLine(current, p);
                    current = p;
                    break;
                }
                case 'H':
                {
                    var x = Next();
                    var p = new PointF(rel ? current.X + x : x, current.Y);
                    path.AddLine(current, p);
                    current = p;
                    break;
                }
                case 'V':
                {
                    var y = Next();
                    var p = new PointF(current.X, rel ? current.Y + y : y);
                    path.AddLine(current, p);
                    current = p;
                    break;
                }
                case 'C':
                {
                    var c1 = P(Next(), Next());
                    var c2 = P(Next(), Next());
                    var p = P(Next(), Next());
                    path.AddBezier(current, c1, c2, p);
                    current = p;
                    break;
                }
                case 'Q':
                {
                    var c = P(Next(), Next());
                    var p = P(Next(), Next());
                    // Elevate the quadratic to a cubic.
                    var c1 = new PointF(current.X + 2f / 3 * (c.X - current.X), current.Y + 2f / 3 * (c.Y - current.Y));
                    var c2 = new PointF(p.X + 2f / 3 * (c.X - p.X), p.Y + 2f / 3 * (c.Y - p.Y));
                    path.AddBezier(current, c1, c2, p);
                    current = p;
                    break;
                }
                case 'A':
                {
                    float rx = Next(), ry = Next(), rotation = Next(), large = Next(), sweep = Next();
                    var p = P(Next(), Next());
                    AddArc(path, current, p, rx, ry, rotation, large != 0, sweep != 0);
                    current = p;
                    break;
                }
                case 'Z':
                    path.CloseFigure();
                    current = start;
                    break;
                default:
                    throw new InvalidOperationException($"unsupported path command '{cmd}' in the cat");
            }
            if (char.ToUpperInvariant(cmd) == 'Z' && HasNumber()) throw new InvalidOperationException("numbers after Z");
        }
        return path;
    }

    /// <summary>SVG endpoint arc → centre form (SVG spec F.6.5), flattened into segments by the parametric angle.</summary>
    private static void AddArc(GraphicsPath path, PointF from, PointF to, float rx, float ry, float rotation, bool large, bool sweep)
    {
        if (rotation != 0) throw new InvalidOperationException("rotated arcs are not used by the cat");
        if (rx == 0 || ry == 0) { path.AddLine(from, to); return; }
        double x1p = (from.X - to.X) / 2.0, y1p = (from.Y - to.Y) / 2.0;
        double rxd = Math.Abs(rx), ryd = Math.Abs(ry);
        var lambda = x1p * x1p / (rxd * rxd) + y1p * y1p / (ryd * ryd);
        if (lambda > 1) { rxd *= Math.Sqrt(lambda); ryd *= Math.Sqrt(lambda); }
        var num = rxd * rxd * ryd * ryd - rxd * rxd * y1p * y1p - ryd * ryd * x1p * x1p;
        var den = rxd * rxd * y1p * y1p + ryd * ryd * x1p * x1p;
        var coef = (large != sweep ? 1 : -1) * Math.Sqrt(Math.Max(0, num / den));
        var cxp = coef * rxd * y1p / ryd;
        var cyp = coef * -ryd * x1p / rxd;
        var cx = cxp + (from.X + to.X) / 2.0;
        var cy = cyp + (from.Y + to.Y) / 2.0;
        var theta1 = Math.Atan2((y1p - cyp) / ryd, (x1p - cxp) / rxd);
        var theta2 = Math.Atan2((-y1p - cyp) / ryd, (-x1p - cxp) / rxd);
        var delta = theta2 - theta1;
        if (sweep && delta < 0) delta += 2 * Math.PI;
        if (!sweep && delta > 0) delta -= 2 * Math.PI;

        const int segments = 48;
        var previous = from;
        for (var s = 1; s <= segments; s++)
        {
            var t = theta1 + delta * s / segments;
            var p = s == segments ? to : new PointF((float)(cx + rxd * Math.Cos(t)), (float)(cy + ryd * Math.Sin(t)));
            path.AddLine(previous, p);
            previous = p;
        }
    }

    private static List<string> Tokenize(string data)
    {
        var tokens = new List<string>();
        var i = 0;
        while (i < data.Length)
        {
            var c = data[i];
            if (char.IsWhiteSpace(c) || c == ',') { i++; continue; }
            if (char.IsLetter(c) && c != 'e' && c != 'E') { tokens.Add(c.ToString()); i++; continue; }
            var startIndex = i;
            if (c == '-' || c == '+') i++;
            while (i < data.Length && (char.IsDigit(data[i]) || data[i] == '.')) i++;
            if (i < data.Length && (data[i] == 'e' || data[i] == 'E'))
            {
                i++;
                if (i < data.Length && (data[i] == '-' || data[i] == '+')) i++;
                while (i < data.Length && char.IsDigit(data[i])) i++;
            }
            if (i == startIndex) throw new InvalidOperationException($"unexpected '{c}' in path data");
            tokens.Add(data[startIndex..i]);
        }
        return tokens;
    }
}
