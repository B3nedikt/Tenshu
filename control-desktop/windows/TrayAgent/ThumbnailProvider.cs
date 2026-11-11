using System.Runtime.InteropServices;
using System.Runtime.InteropServices.Marshalling;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Tenshu.TrayAgent;

/// <summary>
/// Explorer's thumbnail handler for the Tenshu Drive sync root (desktop-windows-port.md §7.9, X2). Declared in Control's
/// AppxManifest as the sync root's <c>ThumbnailProviderHandler</c> and served by this executable as an out-of-process
/// COM server: Explorer asks it for an online-only file's thumbnail instead of downloading the file to draw one.
///
/// The picture is the realm's <b>stored</b> thumbnail class, fetched from Control over the bridge
/// (<c>explorer.v1.thumbnail</c>). ⚠⚠ Never the file's own bytes and never <c>File.Preview</c>: nothing here may open the
/// file, or the folder of online-only photos downloads itself the first time it is shown (§7.3).
///
/// ⚠ Source-generated COM (<see cref="StrategyBasedComWrappers"/>), not classic COM interop: the agent installs CsWinRT's
/// global ComWrappers for WinUI, and classic marshalling would go through it.
/// </summary>
[GeneratedComClass]
public sealed partial class ThumbnailProvider : IInitializeWithItem, IThumbnailProvider
{
    public static readonly Guid Clsid = new("A7C1E2B4-5D3F-4E9A-8B60-2C4D7F1E9A35");

    private const uint SigdnFileSysPath = 0x80058000;
    private const int WtsatArgb = 2;

    private readonly BridgeClient _bridge = new();
    private string? _path;

    /// <summary>Takes the item's file-system path only — reading a path never opens the file.</summary>
    public unsafe void Initialize(IntPtr shellItem, uint mode)
    {
        if (shellItem == IntPtr.Zero) throw new ArgumentNullException(nameof(shellItem));
        // IShellItem::GetDisplayName is vtable slot 5 (IUnknown 0–2, BindToHandler, GetParent).
        var vtable = *(IntPtr**)shellItem;
        var getDisplayName = (delegate* unmanaged[Stdcall]<IntPtr, uint, IntPtr*, int>)vtable[5];
        IntPtr name;
        Marshal.ThrowExceptionForHR(getDisplayName(shellItem, SigdnFileSysPath, &name));
        try
        {
            _path = Marshal.PtrToStringUni(name);
        }
        finally
        {
            Marshal.FreeCoTaskMem(name);
        }
    }

    /// <summary>
    /// The stored thumbnail, fit within <paramref name="size"/> pixels. Throws (a failing HRESULT) when the realm has
    /// none or Control cannot be reached: Explorer then draws the file type's icon.
    /// </summary>
    public void GetThumbnail(uint size, out IntPtr bitmap, out int alpha)
    {
        var path = _path ?? throw new InvalidOperationException("not initialised");
        ThumbnailServer.Touch();
        var reply = _bridge.Call("explorer.v1.thumbnail", new Dictionary<string, string>
        {
            ["path"] = path,
            ["size"] = size.ToString(System.Globalization.CultureInfo.InvariantCulture),
        });
        if (reply["available"]?.GetValue<bool>() != true) throw new COMException("no stored thumbnail", HResultFail);
        var bytes = Convert.FromBase64String(reply["data"]!.GetValue<string>());
        bitmap = ToHBitmap(bytes, size);
        alpha = WtsatArgb;
    }

    private const int HResultFail = unchecked((int)0x80004005);

    /// <summary>Decodes JPEG / PNG / WebP (all three are stored) with the OS's codecs, scaled down to fit, as a 32-bpp DIB.</summary>
    private static IntPtr ToHBitmap(byte[] encoded, uint maxEdge)
    {
        using var stream = new InMemoryRandomAccessStream();
        using (var writer = new DataWriter(stream.GetOutputStreamAt(0)))
        {
            writer.WriteBytes(encoded);
            writer.StoreAsync().AsTask().GetAwaiter().GetResult();
            writer.FlushAsync().AsTask().GetAwaiter().GetResult();
            writer.DetachStream();
        }
        stream.Seek(0);
        var decoder = BitmapDecoder.CreateAsync(stream).AsTask().GetAwaiter().GetResult();
        // The transform scales the stored pixels, then the EXIF rotation turns them: a quarter turn swaps the sides.
        var (width, height) = Fit(decoder.PixelWidth, decoder.PixelHeight, maxEdge);
        var turned = decoder.OrientedPixelWidth != decoder.PixelWidth;
        var transform = new BitmapTransform
        {
            ScaledWidth = width,
            ScaledHeight = height,
            InterpolationMode = BitmapInterpolationMode.Fant,
        };
        var pixels = decoder.GetPixelDataAsync(
            BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, transform,
            ExifOrientationMode.RespectExifOrientation, ColorManagementMode.DoNotColorManage).AsTask().GetAwaiter().GetResult();
        var data = pixels.DetachPixelData();
        return turned ? Dib.Create((int)height, (int)width, data) : Dib.Create((int)width, (int)height, data);
    }

    /// <summary>Scaled down to fit <paramref name="maxEdge"/>, never up: Explorer scales a small picture itself.</summary>
    internal static (uint Width, uint Height) Fit(uint width, uint height, uint maxEdge)
    {
        if (width == 0 || height == 0) return (1, 1);
        var longest = Math.Max(width, height);
        if (maxEdge == 0 || longest <= maxEdge) return (width, height);
        var scale = (double)maxEdge / longest;
        return (Math.Max(1, (uint)Math.Round(width * scale)), Math.Max(1, (uint)Math.Round(height * scale)));
    }
}

[GeneratedComInterface]
[Guid("7f73be3f-fb79-493c-a6c7-7ee14e245841")]
public partial interface IInitializeWithItem
{
    void Initialize(IntPtr shellItem, uint mode);
}

[GeneratedComInterface]
[Guid("e357fccd-a995-4576-b01f-234630154e96")]
public partial interface IThumbnailProvider
{
    void GetThumbnail(uint size, out IntPtr bitmap, out int alpha);
}

/// <summary>A top-down 32-bpp DIB section holding premultiplied BGRA — what <c>WTSAT_ARGB</c> means.</summary>
internal static partial class Dib
{
    public static unsafe IntPtr Create(int width, int height, byte[] bgra)
    {
        var header = new BitmapInfoHeader
        {
            Size = (uint)sizeof(BitmapInfoHeader),
            Width = width,
            Height = -height, // negative: top-down rows, as the decoder hands them over
            Planes = 1,
            BitCount = 32,
        };
        var bitmap = CreateDIBSection(IntPtr.Zero, ref header, 0, out var bits, IntPtr.Zero, 0);
        if (bitmap == IntPtr.Zero) throw new COMException("CreateDIBSection failed", Marshal.GetHRForLastWin32Error());
        Marshal.Copy(bgra, 0, bits, Math.Min(bgra.Length, width * height * 4));
        return bitmap;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BitmapInfoHeader
    {
        public uint Size;
        public int Width;
        public int Height;
        public ushort Planes;
        public ushort BitCount;
        public uint Compression;
        public uint SizeImage;
        public int XPelsPerMeter;
        public int YPelsPerMeter;
        public uint ClrUsed;
        public uint ClrImportant;
    }

    [LibraryImport("gdi32.dll", SetLastError = true)]
    private static partial IntPtr CreateDIBSection(IntPtr hdc, ref BitmapInfoHeader info, uint usage, out IntPtr bits, IntPtr section, uint offset);
}

/// <summary>
/// Registers <see cref="ThumbnailProvider"/>'s class object with COM for the life of the process, on an MTA thread of
/// its own so Explorer's requests never wait on the tray's UI thread.
/// </summary>
public static partial class ThumbnailServer
{
    private const uint ClsctxLocalServer = 0x4;
    private const uint RegclsMultipleUse = 1;
    private static readonly StrategyBasedComWrappers Wrappers = new();
    private static long _lastUse = Environment.TickCount64;

    /// <summary>Starts the registration thread. Best effort: without it Explorer only draws type icons.</summary>
    public static void Start()
    {
        var thread = new Thread(Serve) { IsBackground = true, Name = "thumbnail-server" };
        thread.SetApartmentState(ApartmentState.MTA);
        thread.Start();
    }

    /// <summary>
    /// The process COM started only to serve a thumbnail (no tray running): serve until nothing has been asked for
    /// <paramref name="idle"/>, then exit.
    /// </summary>
    public static void RunUntilIdle(TimeSpan idle)
    {
        // ⚠ Named for Control's tray supervisor, which finds the agent by executable path alone: this process has no
        // icon and no heartbeat, and taken for the tray it would hold the real one back (WindowsTrayAgent.findAgent).
        var pidFile = Path.Combine(BridgeClient.DefaultSocketDir(), PidFile);
        try { File.WriteAllText(pidFile, Environment.ProcessId.ToString(System.Globalization.CultureInfo.InvariantCulture)); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
        try
        {
            Start();
            while (Environment.TickCount64 - Interlocked.Read(ref _lastUse) < idle.TotalMilliseconds) Thread.Sleep(1000);
        }
        finally
        {
            // Only while it still names this process: a second server may have written its own pid since.
            ForgetStalePid();
        }
    }

    /** In the socket directory; Control's `WindowsTrayAgent.THUMBNAIL_SERVER_PID`. */
    public const string PidFile = "thumbnail-server.pid";

    /** Removes the pid file when it names this very process — left by a thumbnail server whose pid was reused. */
    public static void ForgetStalePid()
    {
        try
        {
            var pidFile = Path.Combine(BridgeClient.DefaultSocketDir(), PidFile);
            if (File.Exists(pidFile) && File.ReadAllText(pidFile).Trim() == Environment.ProcessId.ToString(System.Globalization.CultureInfo.InvariantCulture))
            {
                File.Delete(pidFile);
            }
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    internal static void Touch() => Interlocked.Exchange(ref _lastUse, Environment.TickCount64);

    private static void Serve()
    {
        try
        {
            // S_OK, S_FALSE or RPC_E_CHANGED_MODE: any of them leaves the thread in the MTA, which is all that matters.
            CoInitializeEx(IntPtr.Zero, CoinitMultithreaded);
            var factory = Wrappers.GetOrCreateComInterfaceForObject(new ClassFactory(), CreateComInterfaceFlags.None);
            var clsid = ThumbnailProvider.Clsid;
            // ⚠ Retried: a thumbnail server Windows started while no tray ran may still hold the class when the tray
            // starts. Giving up would leave the tray serving nothing, and every later burst start a new server.
            while (CoRegisterClassObject(ref clsid, factory, ClsctxLocalServer, RegclsMultipleUse, out _) < 0)
            {
                Thread.Sleep(5000);
            }
            Thread.Sleep(Timeout.Infinite); // the registration lives as long as the process
        }
        catch (Exception)
        {
            // No thumbnails, nothing worse: Explorer falls back to the file type's icon.
        }
    }

    [GeneratedComClass]
    internal sealed partial class ClassFactory : IClassFactory
    {
        public void CreateInstance(IntPtr outer, in Guid iid, out IntPtr instance)
        {
            instance = IntPtr.Zero;
            if (outer != IntPtr.Zero) throw new COMException("no aggregation", unchecked((int)0x80040110)); // CLASS_E_NOAGGREGATION
            Touch();
            var unknown = Wrappers.GetOrCreateComInterfaceForObject(new ThumbnailProvider(), CreateComInterfaceFlags.None);
            try
            {
                var wanted = iid;
                Marshal.ThrowExceptionForHR(Marshal.QueryInterface(unknown, in wanted, out instance));
            }
            finally
            {
                Marshal.Release(unknown);
            }
        }

        public void LockServer([MarshalAs(UnmanagedType.Bool)] bool fLock) => Touch();
    }

    private const uint CoinitMultithreaded = 0;

    [LibraryImport("ole32.dll")]
    private static partial int CoInitializeEx(IntPtr reserved, uint coInit);

    [LibraryImport("ole32.dll")]
    private static partial int CoRegisterClassObject(ref Guid clsid, IntPtr unknown, uint context, uint flags, out uint cookie);
}

[GeneratedComInterface]
[Guid("00000001-0000-0000-C000-000000000046")]
internal partial interface IClassFactory
{
    void CreateInstance(IntPtr outer, in Guid iid, out IntPtr instance);

    void LockServer([MarshalAs(UnmanagedType.Bool)] bool fLock);
}
