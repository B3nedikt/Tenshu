using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Markup;
using Microsoft.UI.Xaml.XamlTypeInfo;

namespace Tenshu.TrayAgent;

/// <summary>
/// The tray agent: the cat in the notification area, the flyout on a left click, a short native menu on a right
/// click, and a 10 s poll of the bridge that keeps the icon and tooltip current (the macOS agent polls the same
/// way — the bridge is request/response).
///
/// ⚠ An <see cref="Application"/> with no App.xaml must provide WinUI's control metadata itself, or
/// <see cref="XamlControlsResources"/> — the Fluent styles — fails to load; hence <see cref="IXamlMetadataProvider"/>.
/// </summary>
public sealed class App : Application, IXamlMetadataProvider
{
    private readonly XamlControlsXamlMetaDataProvider _metadata = new();
    private readonly BridgeClient _bridge = new();
    private DispatcherQueue _dispatcher = null!;
    private TrayIcon _tray = null!;
    private FlyoutWindow _flyout = null!;
    private DispatcherQueueTimer _poll = null!;
    private TrayState _state = TrayState.Unknown;

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        Resources.MergedDictionaries.Add(new XamlControlsResources());
        _dispatcher = DispatcherQueue.GetForCurrentThread();

        _flyout = new FlyoutWindow();
        _flyout.OpenControl += OpenControl;
        _flyout.SetPaused += SetPaused;
        _flyout.Quit += QuitControl;

        _tray = new TrayIcon(
            onSelect: (x, y) => { if (_flyout.IsVisible) _flyout.Hide(); else { _flyout.ShowAt(x, y); Refresh(); } },
            menu: () =>
            [
                new TrayIcon.MenuItem("Open Control", OpenControl),
                new TrayIcon.MenuItem(_state.Paused ? "Resume sync" : "Pause sync", () => SetPaused(!_state.Paused), _state.Reachable),
                null,
                new TrayIcon.MenuItem("Quit Tenshu Control", QuitControl),
            ]);
        UpdateIcon();

        _poll = _dispatcher.CreateTimer();
        _poll.Interval = TimeSpan.FromSeconds(10);
        _poll.Tick += (_, _) => Refresh();
        _poll.Start();
        Refresh();
    }

    /// <summary>Reads the sync state off the UI thread, then renders it on the UI thread.</summary>
    private void Refresh()
    {
        Task.Run(() =>
        {
            TrayState next;
            try { next = TrayState.From(_bridge.Call("getSyncState")); }
            catch (BridgeException e) { next = TrayState.Unreachable(e.Message); }
            _dispatcher.TryEnqueue(() =>
            {
                _state = next;
                _flyout.Render(next);
                // Re-rendered each poll so a taskbar theme switch (light ↔ dark) is picked up within 10 s.
                UpdateIcon();
            });
        });
    }

    private void UpdateIcon() => _tray.Update(CatIcon.CreateHicon(IconSize()), Tooltip());

    private string Tooltip() => _state.Reachable ? $"Tenshu Control — {_state.Headline}" : "Tenshu Control — not running";

    /// <summary>
    /// Brings Control's window to the front. ⚠⚠ Windows lets only the process that received the user's last input
    /// take the foreground — here the agent, whose icon or flyout was just clicked. Without handing that right to
    /// Control (<c>AllowSetForegroundWindow</c>) Control's raise only flashes its taskbar button and the window
    /// stays behind others. The server's pid comes from the bridge socket itself, the same peer check that
    /// authenticated it, so the grant goes to the process that is really serving Control.
    /// </summary>
    private void OpenControl()
    {
        Task.Run(() =>
        {
            try
            {
                _bridge.Call("ping");
                if (_bridge.LastServerPid > 0) AllowSetForegroundWindow((uint)_bridge.LastServerPid);
                _bridge.Call("openControlWindow");
            }
            catch (BridgeException)
            {
                // Control is not running: start it through its own package, as the Start menu would.
                var family = PeerPackage.OwnFamilyName();
                if (family != null)
                {
                    Process.Start(new ProcessStartInfo("explorer.exe", $"shell:AppsFolder\\{family}!Control") { UseShellExecute = true });
                }
            }
        });
    }

    private void SetPaused(bool paused)
    {
        Task.Run(() =>
        {
            try { _bridge.Call("setSyncPaused", new Dictionary<string, string> { ["paused"] = paused ? "true" : "false" }); }
            catch (BridgeException) { }
            Refresh();
        });
    }

    private void QuitControl()
    {
        Task.Run(() =>
        {
            try { _bridge.Call("quitApp"); } catch (BridgeException) { }
            _dispatcher.TryEnqueue(() =>
            {
                _tray.Dispose();
                Exit();
            });
        });
    }

    /// <summary>
    /// The small-icon size at the DPI of the monitor the taskbar is on, so Windows draws the icon 1:1 instead of
    /// rescaling it. ⚠ Needs the PerMonitorV2 manifest: a DPI-unaware process is told 96 everywhere.
    /// </summary>
    private static int IconSize()
    {
        var taskbar = FindWindow("Shell_TrayWnd", null);
        var dpi = taskbar != IntPtr.Zero ? GetDpiForWindow(taskbar) : 0;
        if (dpi == 0) dpi = GetDpiForSystem();
        return GetSystemMetricsForDpi(49 /* SM_CXSMICON */, dpi);
    }

    [DllImport("user32.dll")] private static extern bool AllowSetForegroundWindow(uint processId);
    [DllImport("user32.dll")] private static extern int GetSystemMetricsForDpi(int index, uint dpi);
    [DllImport("user32.dll")] private static extern uint GetDpiForSystem();
    [DllImport("user32.dll")] private static extern uint GetDpiForWindow(IntPtr hwnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr FindWindow(string className, string? windowName);

    // ── IXamlMetadataProvider: WinUI's own control metadata, since there is no App.xaml ──
    public IXamlType GetXamlType(Type type) => _metadata.GetXamlType(type);
    public IXamlType GetXamlType(string fullName) => _metadata.GetXamlType(fullName);
    public XmlnsDefinition[] GetXmlnsDefinitions() => _metadata.GetXmlnsDefinitions();
}
