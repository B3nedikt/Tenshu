using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;

namespace Tenshu.TrayAgent;

/// <summary>
/// The tray flyout: a WinUI 3 window shaped like the Windows 11 system flyouts — no title bar, acrylic backdrop,
/// rounded border, kept out of Alt+Tab and the taskbar, anchored beside the tray icon inside the work area, and
/// hidden as soon as it loses focus.
///
/// T0 content: the sync state and three actions. The full macOS popover content (needs-action, recent
/// notifications, app launcher) is T1 (desktop-windows-port.md §3.9).
///
/// ⚠ A thin renderer: everything shown here comes from the bridge (<see cref="TrayState"/>); this class owns only
/// layout and placement.
/// </summary>
internal sealed class FlyoutWindow
{
    private const int WidthDip = 340;

    private readonly Window _window = new();
    private readonly StackPanel _root;
    private readonly TextBlock _state = new() { FontSize = 15 };
    private readonly TextBlock _detail = new() { Opacity = 0.7, TextWrapping = TextWrapping.Wrap };
    private readonly Button _pause = new() { HorizontalAlignment = HorizontalAlignment.Stretch };
    private TrayState _current = TrayState.Unknown;

    public event Action? OpenControl;
    public event Action<bool>? SetPaused;
    public event Action? Quit;

    public FlyoutWindow()
    {
        _window.Title = "Tenshu Control";
        _window.SystemBackdrop = new DesktopAcrylicBackdrop();

        var open = new Button { Content = "Open Control", HorizontalAlignment = HorizontalAlignment.Stretch, Style = AccentStyle() };
        open.Click += (_, _) => { Hide(); OpenControl?.Invoke(); };
        _pause.Click += (_, _) => SetPaused?.Invoke(!_current.Paused);
        var quit = new Button { Content = "Quit Tenshu Control", HorizontalAlignment = HorizontalAlignment.Stretch };
        quit.Click += (_, _) => { Hide(); Quit?.Invoke(); };

        _window.Content = _root = new StackPanel
        {
            Padding = new Thickness(20),
            Spacing = 10,
            Children =
            {
                new TextBlock { Text = "Tenshu Control", FontSize = 20, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold },
                _state,
                _detail,
                open,
                _pause,
                quit,
            },
        };

        var presenter = OverlappedPresenter.Create();
        presenter.SetBorderAndTitleBar(hasBorder: true, hasTitleBar: false);
        presenter.IsResizable = false;
        presenter.IsMaximizable = false;
        presenter.IsMinimizable = false;
        presenter.IsAlwaysOnTop = true;
        _window.AppWindow.SetPresenter(presenter);
        _window.AppWindow.IsShownInSwitchers = false;

        _window.Activated += (_, e) =>
        {
            if (e.WindowActivationState == WindowActivationState.Deactivated) Hide();
        };
        Render(_current);
    }

    public bool IsVisible => _window.AppWindow.IsVisible;

    public void Render(TrayState state)
    {
        _current = state;
        _state.Text = state.Headline;
        _detail.Text = state.Detail;
        _pause.Content = state.Paused ? "Resume sync" : "Pause sync";
        _pause.IsEnabled = state.Reachable;
    }

    /// <summary>Shows the flyout beside the tray anchor at screen point (<paramref name="x"/>, <paramref name="y"/>).</summary>
    public void ShowAt(int x, int y)
    {
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(_window);
        Place(hwnd, x, y);
        _window.AppWindow.Show();
        _window.Activate();
        SetForegroundWindow(hwnd);
        // ⚠ Before the first show the content has no styles applied and measures short; fit again once laid out.
        _window.DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () => Place(hwnd, x, y));
    }

    /// <summary>
    /// Sizes the window to its content and anchors it beside (<paramref name="x"/>, <paramref name="y"/>) inside the
    /// work area. ⚠ Sized to the content, never to a guessed constant: a fixed 230 DIP clipped the last button.
    /// </summary>
    private void Place(IntPtr hwnd, int x, int y)
    {
        var scale = GetDpiForWindow(hwnd) / 96.0;
        _root.Measure(new Windows.Foundation.Size(WidthDip, double.PositiveInfinity));
        var heightDip = Math.Max(_root.DesiredSize.Height, 160) + 2; // + the window border
        int width = (int)(WidthDip * scale), height = (int)Math.Ceiling(heightDip * scale), margin = (int)(12 * scale);
        var work = DisplayArea.GetFromPoint(new PointInt32(x, y), DisplayAreaFallback.Nearest).WorkArea;
        // Up and to the left of the anchor (the taskbar is usually at the bottom right), clamped inside the work area.
        var left = Math.Clamp(x - width + margin, work.X + margin, work.X + work.Width - width - margin);
        var top = Math.Clamp(y - height - margin, work.Y + margin, work.Y + work.Height - height - margin);
        _window.AppWindow.MoveAndResize(new RectInt32(left, top, width, height));
    }

    public void Hide()
    {
        if (_window.AppWindow.IsVisible) _window.AppWindow.Hide();
    }

    private static Style? AccentStyle() =>
        Application.Current.Resources.TryGetValue("AccentButtonStyle", out var style) ? style as Style : null;

    [DllImport("user32.dll")] private static extern uint GetDpiForWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hwnd);
}

/// <summary>What the tray shows, decoded from the bridge's <c>getSyncState</c> reply.</summary>
internal sealed record TrayState(bool Reachable, bool Paused, string Headline, string Detail)
{
    public static readonly TrayState Unknown = new(false, false, "Connecting…", "");

    /// <summary>
    /// ⚠ The technical reason (<paramref name="why"/>) is not shown: "connection refused at …\bridge.sock" tells a user
    /// nothing. It is kept for the debugger.
    /// </summary>
    public static TrayState Unreachable(string why) =>
        new(false, false, "Control is not running", "Open Control to start syncing.") { Reason = why };

    public string Reason { get; init; } = "";

    /// <summary>
    /// ⚠ English only in T0. The agent owns its display strings (ADR 0016, as the Swift agent does); localisation
    /// lands with the T1 flyout.
    /// </summary>
    public static TrayState From(System.Text.Json.Nodes.JsonObject result)
    {
        var state = result["state"]?.GetValue<string>() ?? "";
        var paused = result["paused"]?.GetValue<bool>() ?? false;
        var headline = paused ? "Sync paused" : state switch
        {
            "ACTIVE" => "Syncing",
            "LOCKED" => "Locked",
            "DISABLED" => (result["setUp"]?.GetValue<bool>() ?? false) ? "Sync is off" : "Not set up yet",
            _ => state,
        };
        var last = result["lastSyncTimestamp"]?.GetValue<long?>();
        var detail = last is > 0
            ? "Last sync " + DateTimeOffset.FromUnixTimeMilliseconds(last.Value).ToLocalTime().ToString("g")
            : "";
        return new TrayState(true, paused, headline, detail);
    }
}
