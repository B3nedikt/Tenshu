using System.Runtime.InteropServices;
using Microsoft.UI;
using Microsoft.UI.Text;
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
/// Content mirrors the macOS popover (desktop-windows-port.md §3.9 T1): the state headline and its divergence
/// detail, then <i>Needs action</i>, <i>Recent</i> and <i>Apps</i> — each shown only when it has rows — then
/// Pause/Resume, Open Control and Quit.
///
/// ⚠ A thin renderer: everything shown comes from the bridge (<see cref="TrayState"/>); this class owns only
/// layout and placement.
/// </summary>
internal sealed class FlyoutWindow
{
    private const int WidthDip = 340;

    private static Strings S => Strings.Current;

    private readonly Window _window = new();
    private readonly StackPanel _root;
    private readonly StackPanel _body = new() { Spacing = 4 };
    private readonly TextBlock _label = new() { FontSize = 12, VerticalAlignment = VerticalAlignment.Center, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Right };
    private readonly Button _pause = ListButton();
    private TrayState _current = TrayState.Unknown;
    private string? _launchFailure;
    private (int X, int Y)? _anchor;

    public event Action? OpenControl;
    public event Action<bool>? SetPaused;
    public event Action<AppRow>? LaunchApp;
    public event Action? Quit;

    public FlyoutWindow()
    {
        _window.Title = S.Title;
        _window.SystemBackdrop = new DesktopAcrylicBackdrop();

        _pause.Click += (_, _) => SetPaused?.Invoke(!_current.Paused);
        var open = new Button { Content = S.OpenControl, Style = Resource<Style>("AccentButtonStyle") };
        open.Click += (_, _) => { Hide(); OpenControl?.Invoke(); };
        var quit = new Button { Content = S.Quit };
        quit.Click += (_, _) => { Hide(); Quit?.Invoke(); };

        var header = new Grid { ColumnSpacing = 12 };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var title = new TextBlock { Text = S.Title, FontSize = 16, FontWeight = FontWeights.SemiBold };
        Grid.SetColumn(_label, 1);
        header.Children.Add(title);
        header.Children.Add(_label);

        var actions = new Grid { ColumnSpacing = 8 };
        actions.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        actions.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        open.HorizontalAlignment = HorizontalAlignment.Stretch;
        Grid.SetColumn(quit, 1);
        actions.Children.Add(open);
        actions.Children.Add(quit);

        _window.Content = _root = new StackPanel
        {
            Padding = new Thickness(16),
            Spacing = 8,
            Children = { header, _body, Divider(), _pause, actions },
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
        _label.Text = state.Label;
        _label.Foreground = !state.Reachable ? Critical() : state.Warning ? Caution() : Secondary();
        _pause.Content = state.Paused ? S.ResumeSync : S.PauseSync;
        _pause.Visibility = state.Reachable && state.Enabled ? Visibility.Visible : Visibility.Collapsed;

        _body.Children.Clear();
        if (state.Reachable && state.Detail != null) _body.Children.Add(Caption(state.Detail, wrap: true));
        if (!state.Reachable)
        {
            if (state != TrayState.Unknown) _body.Children.Add(Caption(S.NotRunningBody, wrap: true));
        }
        else
        {
            if (state.Requests.Count > 0)
            {
                Section(S.NeedsAction);
                foreach (var title in state.Requests)
                {
                    var row = ListButton(Glyph("", Caution()), new TextBlock { Text = title, TextTrimming = TextTrimming.CharacterEllipsis });
                    row.Click += (_, _) => { Hide(); OpenControl?.Invoke(); };
                    _body.Children.Add(row);
                }
            }
            if (state.Notifications.Count > 0)
            {
                Section(S.Recent);
                foreach (var text in state.Notifications)
                {
                    _body.Children.Add(new TextBlock
                    {
                        Text = text,
                        TextTrimming = TextTrimming.CharacterEllipsis,
                        Margin = new Thickness(8, 2, 8, 2),
                    });
                }
            }
            if (state.Apps.Count > 0)
            {
                Section(S.Apps);
                foreach (var app in state.Apps) _body.Children.Add(AppButton(app));
            }
        }

        // The content height changed; a visible flyout refits at once instead of clipping or floating.
        if (IsVisible && _anchor is { } anchor) Place(Hwnd, anchor.X, anchor.Y);
    }

    /// <summary>Marks a launch that Windows refused, shown on the row until the flyout is next opened.</summary>
    public void MarkLaunchFailed(string appId)
    {
        _launchFailure = appId;
        Render(_current);
    }

    private Button AppButton(AppRow app)
    {
        var parts = new List<UIElement> { new TextBlock { Text = app.Name, TextTrimming = TextTrimming.CharacterEllipsis } };
        // Only a registered row can be unverified: a never-connected candidate was never attested — nor failed to be.
        if (app.Registered && !app.Verified)
        {
            var warning = Glyph("", Caution());
            ToolTipService.SetToolTip(warning, S.Unverified);
            parts.Add(warning);
        }
        if (_launchFailure == app.AppId) parts.Add(Caption(S.CouldNotOpen, wrap: false));
        var button = ListButton(parts.ToArray());
        button.Click += (_, _) => { Hide(); LaunchApp?.Invoke(app); };
        return button;
    }

    private void Section(string header)
    {
        _body.Children.Add(Divider());
        var caption = Caption(header, wrap: false);
        caption.Margin = new Thickness(0, 2, 0, 0);
        _body.Children.Add(caption);
    }

    /// <summary>Shows the flyout beside the tray anchor at screen point (<paramref name="x"/>, <paramref name="y"/>).</summary>
    public void ShowAt(int x, int y)
    {
        _anchor = (x, y);
        if (_launchFailure != null)
        {
            _launchFailure = null;
            Render(_current);
        }
        var hwnd = Hwnd;
        Place(hwnd, x, y);
        _window.AppWindow.Show();
        _window.Activate();
        SetForegroundWindow(hwnd);
        // ⚠ Before the first show the content has no styles applied and measures short; fit again once laid out.
        _window.DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () => Place(hwnd, x, y));
    }

    private IntPtr Hwnd => WinRT.Interop.WindowNative.GetWindowHandle(_window);

    /// <summary>
    /// Sizes the window to its content and anchors it beside (<paramref name="x"/>, <paramref name="y"/>) inside the
    /// work area. ⚠ Sized to the content, never to a guessed constant: a fixed 230 DIP clipped the last button.
    /// </summary>
    private void Place(IntPtr hwnd, int x, int y)
    {
        var scale = GetDpiForWindow(hwnd) / 96.0;
        _root.Measure(new Windows.Foundation.Size(WidthDip, double.PositiveInfinity));
        var heightDip = Math.Max(_root.DesiredSize.Height, 120) + 2; // + the window border
        int width = (int)(WidthDip * scale), height = (int)Math.Ceiling(heightDip * scale), margin = (int)(12 * scale);
        var work = DisplayArea.GetFromPoint(new PointInt32(x, y), DisplayAreaFallback.Nearest).WorkArea;
        height = Math.Min(height, work.Height - 2 * margin);
        // Up and to the left of the anchor (the taskbar is usually at the bottom right), clamped inside the work area.
        var left = Math.Clamp(x - width + margin, work.X + margin, work.X + work.Width - width - margin);
        var top = Math.Clamp(y - height - margin, work.Y + margin, work.Y + work.Height - height - margin);
        _window.AppWindow.MoveAndResize(new RectInt32(left, top, width, height));
    }

    public void Hide()
    {
        if (_window.AppWindow.IsVisible) _window.AppWindow.Hide();
    }

    // ── building blocks ──

    /// <summary>A flat, full-width, left-aligned row button — the look of a list entry in a Windows 11 flyout.</summary>
    private static Button ListButton(params UIElement[] content)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var part in content) row.Children.Add(part);
        return new Button
        {
            Content = content.Length == 0 ? null : row,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Left,
            Background = new SolidColorBrush(Colors.Transparent),
            BorderThickness = new Thickness(0),
            Padding = new Thickness(8, 5, 8, 5),
        };
    }

    private static TextBlock Caption(string text, bool wrap) => new()
    {
        Text = text,
        FontSize = 12,
        Foreground = Secondary(),
        TextWrapping = wrap ? TextWrapping.Wrap : TextWrapping.NoWrap,
        VerticalAlignment = VerticalAlignment.Center,
    };

    private static FontIcon Glyph(string glyph, Brush brush) => new()
    {
        Glyph = glyph,
        FontSize = 14,
        Foreground = brush,
        VerticalAlignment = VerticalAlignment.Center,
    };

    private static Border Divider() => new()
    {
        Height = 1,
        Margin = new Thickness(0, 4, 0, 0),
        Background = Resource<Brush>("DividerStrokeColorDefaultBrush") ?? new SolidColorBrush(ColorHelper.FromArgb(0x30, 0x80, 0x80, 0x80)),
    };

    private static Brush Secondary() =>
        Resource<Brush>("TextFillColorSecondaryBrush") ?? new SolidColorBrush(ColorHelper.FromArgb(0xB0, 0x80, 0x80, 0x80));

    private static Brush Caution() =>
        Resource<Brush>("SystemFillColorCautionBrush") ?? new SolidColorBrush(ColorHelper.FromArgb(0xFF, 0xF7, 0x63, 0x0C));

    private static Brush Critical() =>
        Resource<Brush>("SystemFillColorCriticalBrush") ?? new SolidColorBrush(ColorHelper.FromArgb(0xFF, 0xC4, 0x2B, 0x1C));

    private static T? Resource<T>(string key) where T : class =>
        Application.Current.Resources.TryGetValue(key, out var value) ? value as T : null;

    [DllImport("user32.dll")] private static extern uint GetDpiForWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hwnd);
}
