using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using System.Xml.Linq;

namespace Tenshu.TrayAgent;

/// <summary>
/// The flyout's app launcher (desktop-windows-port.md §3.9 T1), the Windows counterpart of the Swift agent's
/// LaunchServices lookup.
///
/// Each <c>listApps</c> row carries its MSIX package name; the agent completes it to a package family with its
/// **own** publisher id — first-party apps are signed by the same publisher as Control, so a package by anyone
/// else can never match — and asks Windows whether that family is installed for this user. Asked on every
/// refresh, so a new install appears the next time the flyout opens and an uninstalled app drops out even though
/// its registration stays (<c>control-app-catalogue.md</c> D5).
///
/// ⚠ Unlike macOS there is no stray-bundle problem: a package family is installed or it is not, so an installed
/// package counts on its own, registered here or not. ⚠ This decides what the flyout offers, nothing more — an
/// install is never reported back as a registration.
///
/// Plain Win32 (<c>GetPackagesByPackageFamily</c>, the package's manifest, <c>IApplicationActivationManager</c>)
/// rather than <c>PackageManager</c>, whose query methods are capability-gated in a package context.
/// </summary>
internal static class AppLauncher
{
    /// <summary>The launchable rows of a <c>listApps</c> reply; empty for an agent without package identity.</summary>
    public static IReadOnlyList<AppRow> Launchable(JsonObject? listApps)
    {
        var publisher = OwnPublisherId();
        if (publisher == null) return [];
        var rows = new List<AppRow>();
        foreach (var row in TrayState.Rows(listApps, "apps"))
        {
            var appId = TrayState.Str(row, "appId");
            var name = TrayState.Str(row, "name");
            var package = TrayState.Str(row, "windowsPackage");
            if (appId == null || name == null || package == null) continue;
            var aumid = InstalledAumid($"{package}_{publisher}");
            if (aumid == null) continue;
            rows.Add(new AppRow(
                appId,
                name,
                Registered: TrayState.Bool(row, "registered", fallback: true),
                Verified: TrayState.Bool(row, "verified"),
                Aumid: aumid));
        }
        return rows;
    }

    /// <summary>Activates the app the way the Start menu does; false when Windows refuses.</summary>
    public static bool Launch(string aumid)
    {
        try
        {
            var manager = (IApplicationActivationManager)new ApplicationActivationManager();
            return manager.ActivateApplication(aumid, null, 0, out _) >= 0;
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary><c>Tenshu.Control_asmrhn1bb0j6y</c> → <c>asmrhn1bb0j6y</c>.</summary>
    private static string? OwnPublisherId()
    {
        var family = PeerPackage.OwnFamilyName();
        var split = family?.LastIndexOf('_') ?? -1;
        return split > 0 ? family![(split + 1)..] : null;
    }

    /// <summary><c>{family}!{application id}</c> of the installed package, or null when the family is not installed.</summary>
    private static string? InstalledAumid(string family)
    {
        uint count = 0, bufferLength = 0;
        if (GetPackagesByPackageFamily(family, ref count, null, ref bufferLength, IntPtr.Zero) != ErrorInsufficientBuffer || count == 0)
        {
            return null;
        }
        var names = new IntPtr[count];
        // ⚠ Unmanaged: the returned name pointers point INTO this buffer, so it must not move or be a marshalling copy.
        var buffer = Marshal.AllocHGlobal((int)bufferLength * sizeof(char));
        try
        {
            if (GetPackagesByPackageFamily(family, ref count, names, ref bufferLength, buffer) != 0) return null;
            foreach (var namePtr in names)
            {
                var fullName = Marshal.PtrToStringUni(namePtr);
                var appId = fullName == null ? null : ApplicationId(fullName);
                if (appId != null) return $"{family}!{appId}";
            }
            return null;
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }

    /// <summary>The first <c>Application/@Id</c> in the installed package's manifest.</summary>
    private static string? ApplicationId(string fullName)
    {
        uint length = 0;
        if (GetPackagePathByFullName(fullName, ref length, null) != ErrorInsufficientBuffer) return null;
        var path = new StringBuilder((int)length);
        if (GetPackagePathByFullName(fullName, ref length, path) != 0) return null;
        try
        {
            var manifest = XDocument.Load(Path.Combine(path.ToString(), "AppxManifest.xml"));
            return manifest.Descendants().FirstOrDefault(e => e.Name.LocalName == "Application")?.Attribute("Id")?.Value;
        }
        catch (Exception)
        {
            return null;
        }
    }

    private const int ErrorInsufficientBuffer = 122;

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetPackagesByPackageFamily(
        string packageFamilyName, ref uint count, [Out] IntPtr[]? packageFullNames, ref uint bufferLength, IntPtr buffer);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetPackagePathByFullName(string packageFullName, ref uint pathLength, StringBuilder? path);

    [ComImport, Guid("45BA127D-10A8-46EA-8AB7-56EA9078943C")]
    private class ApplicationActivationManager;

    [ComImport, Guid("2e941141-7f97-4756-ba1d-9decde894a3d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IApplicationActivationManager
    {
        [PreserveSig]
        int ActivateApplication(
            [MarshalAs(UnmanagedType.LPWStr)] string appUserModelId,
            [MarshalAs(UnmanagedType.LPWStr)] string? arguments,
            int options,
            out uint processId);
    }
}
