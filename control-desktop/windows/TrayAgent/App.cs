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
    private Notifier _notifier = null!;
    private readonly object _notifierLock = new();
    private DispatcherQueueTimer _poll = null!;
    private TrayState _state = TrayState.Unknown;
    private bool? _lastPlaced;

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        Resources.MergedDictionaries.Add(new XamlControlsResources());
        _dispatcher = DispatcherQueue.GetForCurrentThread();

        _flyout = new FlyoutWindow();
        _flyout.OpenControl += OpenControl;
        _flyout.SetPaused += SetPaused;
        _flyout.LaunchApp += LaunchApp;
        _flyout.Quit += QuitControl;

        _tray = new TrayIcon(
            onSelect: (x, y) => { if (_flyout.IsVisible) _flyout.Hide(); else { _flyout.ShowAt(x, y); Refresh(); } },
            menu: () =>
            [
                new TrayIcon.MenuItem(Strings.Current.OpenControl, OpenControl),
                new TrayIcon.MenuItem(
                    _state.Paused ? Strings.Current.ResumeSync : Strings.Current.PauseSync,
                    () => SetPaused(!_state.Paused),
                    _state.Reachable && _state.Enabled),
                null,
                new TrayIcon.MenuItem(Strings.Current.QuitMenu, QuitControl),
            ]);
        UpdateIcon();

        // ⚠ Registered before the activation check: Windows delivers a click that started this process through the
        // activation arguments, not through NotificationInvoked.
        _notifier = new Notifier(onActivate: OpenControl);
        if (Notifier.LaunchedByToast()) OpenControl();
        // At login the icon is already up; Control follows, hidden (LoginStart).
        if (LoginStart.LaunchedAtLogin()) LoginStart.StartControlHidden();

        _poll = _dispatcher.CreateTimer();
        _poll.Interval = Heartbeat.Interval;
        _poll.Tick += (_, _) => { PublishHeartbeat(); Refresh(); };
        _poll.Start();
        PublishHeartbeat();
        Refresh();
    }

    /// <summary>
    /// Reads the bridge off the UI thread, then renders on the UI thread. Only <c>getSyncState</c> decides whether
    /// Control is reachable; the three section calls each fall back to empty, so a daemon without one of them
    /// still gets the rest of the flyout.
    /// </summary>
    private void Refresh()
    {
        Task.Run(() =>
        {
            TrayState next;
            try
            {
                var sync = _bridge.Call("getSyncState");
                var requests = TryCall("pendingRequests");
                next = TrayState.From(
                    sync,
                    requests,
                    TryCall("recentNotifications", new Dictionary<string, string> { ["limit"] = "4" }),
                    AppLauncher.Launchable(TryCall("listApps")));
                // The notifiers ride the same poll, so they alert with the flyout and Control's window closed. A
                // failed call feeds nothing — never an empty list (see Notifier).
                var pairing = TryCall("pairingApproval");
                var reminders = TryCall("calendarReminders");
                lock (_notifierLock)
                {
                    _notifier.ReconcileFence(sync);
                    if (requests != null) _notifier.ReconcileRequests(requests);
                    if (pairing != null) _notifier.ReconcilePairing(pairing);
                    if (reminders != null) _notifier.ReconcileReminders(reminders);
                }
            }
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

    /// <summary>
    /// On the UI thread on purpose: the timestamp then proves the thread that owns the icon and the flyout is turning,
    /// not merely that some thread of this process is.
    /// </summary>
    private void PublishHeartbeat()
    {
        var now = DateTimeOffset.Now;
        var placed = _tray.Placed;
        var dir = BridgeClient.DefaultSocketDir();
        Heartbeat.Publish(dir, placed, now);
        if (placed != _lastPlaced) Heartbeat.RecordTransition(dir, placed, now);
        _lastPlaced = placed;
    }

    private System.Text.Json.Nodes.JsonObject? TryCall(string method, IDictionary<string, string>? parameters = null)
    {
        try { return _bridge.Call(method, parameters); }
        catch (BridgeException) { return null; }
    }

    /// <summary>Launches off the UI thread; a refusal marks the row and re-polls, since it usually means the row is stale.</summary>
    private void LaunchApp(AppRow app)
    {
        Task.Run(() =>
        {
            if (AppLauncher.Launch(app.Aumid)) return;
            _dispatcher.TryEnqueue(() => _flyout.MarkLaunchFailed(app.AppId));
            Refresh();
        });
    }

    private void UpdateIcon() => _tray.Update(CatIcon.CreateHicon(IconSize()), Tooltip());

    private string Tooltip() => $"{Strings.Current.Title} — {_state.Label}";

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
                _notifier.Unregister();
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
