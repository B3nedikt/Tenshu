using System.Diagnostics;
using Windows.ApplicationModel;

namespace Tenshu.TrayAgent;

/// <summary>
/// Start at login on Windows (desktop-windows-port.md §3.5): the package's <c>windows.startupTask</c>, declared in
/// Control's AppxManifest with <b>this</b> executable as its target — the <c>SMAppService</c> analogue. Windows
/// owns the entry and the user sees it in Settings → Apps → Startup.
///
/// Why the agent and not Control: at login the icon is up in milliseconds, then the agent starts Control hidden
/// (<c>TENSHU_CONTROL_START_HIDDEN</c>, the macOS login item's variable), whose store may take far longer to open.
/// And the toggle is WinRT (<see cref="StartupTask"/>), which the JVM cannot call — so Control asks the agent
/// (<c>tray-agent --startup-task status|enable|disable</c>, <c>WindowsStartupTask</c> on the Kotlin side).
/// </summary>
internal static class LoginStart
{
    /// <summary>⚠ Must match <c>TaskId</c> in the manifest (gradle/desktop-msix.gradle.kts). Never rename it.</summary>
    public const string TaskId = "TenshuControl";

    /// <summary>Control's launcher in the package root, one level above <c>tray-agent\</c>.</summary>
    private const string ControlExecutable = "TenshuControl.exe";

    /// <summary>
    /// <c>--startup-task &lt;verb&gt;</c>: prints the task's state after the verb, one word Control parses —
    /// <c>enabled</c>, <c>disabled</c>, <c>disabledByUser</c>, <c>disabledByPolicy</c>, <c>enabledByPolicy</c>,
    /// or <c>unavailable</c> (no package identity, or no task in the manifest).
    /// ⚠ <c>disabledByUser</c> cannot be undone from here: Windows reserves that for its Settings page, so Control
    /// sends the user there — the macOS "approval required" path.
    /// </summary>
    public static int RunCli(string verb, TextWriter output)
    {
        string state;
        try
        {
            var task = StartupTask.GetAsync(TaskId).AsTask().GetAwaiter().GetResult();
            var current = verb switch
            {
                "enable" => task.RequestEnableAsync().AsTask().GetAwaiter().GetResult(),
                "disable" => Disable(task),
                _ => task.State,
            };
            state = Code(current);
        }
        catch (Exception)
        {
            state = "unavailable";
        }
        output.WriteLine(state);
        output.Flush();
        return state == "unavailable" ? 2 : 0;
    }

    private static StartupTaskState Disable(StartupTask task)
    {
        task.Disable();
        return task.State;
    }

    private static string Code(StartupTaskState state) => state switch
    {
        StartupTaskState.Enabled => "enabled",
        StartupTaskState.Disabled => "disabled",
        StartupTaskState.DisabledByUser => "disabledByUser",
        StartupTaskState.DisabledByPolicy => "disabledByPolicy",
        StartupTaskState.EnabledByPolicy => "enabledByPolicy",
        _ => "unavailable",
    };

    /// <summary>True when Windows started this process for the startup task, i.e. at login.</summary>
    public static bool LaunchedAtLogin()
    {
        try
        {
            return Microsoft.Windows.AppLifecycle.AppInstance.GetCurrent().GetActivatedEventArgs().Kind
                == Microsoft.Windows.AppLifecycle.ExtendedActivationKind.StartupTask;
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>
    /// Starts Control hidden, as a child of this packaged process so it runs in the same package context. A Control
    /// that is already running sees a second, hidden copy and exits without raising its window (its instance guard).
    /// </summary>
    public static void StartControlHidden()
    {
        try
        {
            // InstalledLocation, not InstalledPath: the latter needs Windows 10 2004, below the package's minimum.
            var root = Package.Current.InstalledLocation.Path;
            var control = Path.Combine(root, ControlExecutable);
            var start = new ProcessStartInfo(control) { UseShellExecute = false, WorkingDirectory = root };
            start.Environment["TENSHU_CONTROL_START_HIDDEN"] = "true";
            Process.Start(start);
        }
        catch (Exception)
        {
            // Best effort: the tray still shows, and its Open Control starts Control the ordinary way.
        }
    }
}
