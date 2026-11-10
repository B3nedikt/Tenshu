using System.ComponentModel;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;

namespace Tenshu.TrayAgent;

/// <summary>
/// The agent's half of the IPC gate (desktop-windows-port.md §3.2): the bridge server must run in the same
/// MSIX package family as this agent — both are executables of the Control package, so "ours" needs no
/// anchor beyond our own identity.
///
/// Same mechanism as the JVM side (<c>WindowsPeerProcess</c>): <c>SIO_AF_UNIX_GETPEERPID</c> on the connected
/// socket names the listening process; its package family name comes from <c>GetPackageFamilyName</c>.
///
/// ⚠ An agent without package identity (a dev run outside the MSIX) has nothing to compare with. It is then
/// admitted only when <c>TENSHU_TRAY_DEV=1</c> is set, the counterpart of the JVM's developer mode; a packaged
/// agent never reads that variable as permission, because it always has an identity to compare.
/// </summary>
internal static class PeerPackage
{
    private const int SioAfUnixGetPeerPid = 0x58000100;
    private const uint ProcessQueryLimitedInformation = 0x1000;
    private const int ErrorSuccess = 0;

    /// <summary>
    /// Returns null when the server is ours, else the reason it is refused. <paramref name="pid"/> is the server's
    /// process id (0 when unreadable) — the agent hands it the foreground before asking it to show its window.
    /// </summary>
    public static string? CheckServer(Socket socket, out int pid)
    {
        pid = 0;
        string? pidError = null;
        try
        {
            var output = new byte[4];
            socket.IOControl(SioAfUnixGetPeerPid, null, output);
            pid = BitConverter.ToInt32(output, 0);
        }
        catch (Exception e)
        {
            pidError = $"cannot read the server pid: {e.Message}";
        }

        var own = OwnFamilyName();
        if (own == null)
        {
            return Environment.GetEnvironmentVariable("TENSHU_TRAY_DEV") == "1"
                ? null
                : "this agent has no package identity (set TENSHU_TRAY_DEV=1 for a dev run)";
        }
        if (pidError != null) return pidError;

        var server = FamilyNameOf(pid);
        if (server == null) return $"server pid {pid} has no package identity";
        return string.Equals(server, own, StringComparison.Ordinal)
            ? null
            : $"server package '{server}' is not '{own}'";
    }

    /// <summary>This process's package family name, or null when it runs without package identity.</summary>
    public static string? OwnFamilyName()
    {
        uint length = 0;
        var rc = GetCurrentPackageFamilyName(ref length, null);
        if (rc != 122 /* ERROR_INSUFFICIENT_BUFFER */) return null; // APPMODEL_ERROR_NO_PACKAGE and friends
        var buffer = new StringBuilder((int)length);
        return GetCurrentPackageFamilyName(ref length, buffer) == ErrorSuccess ? buffer.ToString() : null;
    }

    private static string? FamilyNameOf(int pid)
    {
        var process = OpenProcess(ProcessQueryLimitedInformation, false, (uint)pid);
        if (process == IntPtr.Zero) return null;
        try
        {
            uint length = 256;
            var buffer = new StringBuilder((int)length);
            return GetPackageFamilyName(process, ref length, buffer) == ErrorSuccess ? buffer.ToString() : null;
        }
        finally
        {
            CloseHandle(process);
        }
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetCurrentPackageFamilyName(ref uint length, StringBuilder? name);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetPackageFamilyName(IntPtr process, ref uint length, StringBuilder name);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);
}
