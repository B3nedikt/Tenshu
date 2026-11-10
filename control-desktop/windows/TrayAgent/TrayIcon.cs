using System.Runtime.InteropServices;

namespace Tenshu.TrayAgent;

/// <summary>
/// The notification-area icon: <c>Shell_NotifyIcon</c> (version 4) over P/Invoke, with a hidden window on the UI
/// thread to receive its callbacks — WinUI's dispatcher loop pumps that window's messages too.
///
/// ⚠⚠ A hidden <b>top-level</b> window, not a message-only one: message-only windows receive no broadcasts, and
/// <c>TaskbarCreated</c> is one — the icon would not come back after an Explorer restart.
///
/// Windows conventions, not macOS ones: a left click (<c>NIN_SELECT</c> / keyboard <c>NIN_KEYSELECT</c>)
/// opens the flyout; a right click (<c>WM_CONTEXTMENU</c>) shows a short native menu.
///
/// ⚠ Explorer can restart (or the taskbar be recreated after a DPI change); it then broadcasts the registered
/// <c>TaskbarCreated</c> message and every icon must be added again, or ours silently disappears.
/// </summary>
internal sealed class TrayIcon : IDisposable
{
    public sealed record MenuItem(string Label, Action OnClick, bool Enabled = true);

    private const uint WmApp = 0x8000;
    private const uint CallbackMessage = WmApp + 1;
    private const uint WmContextMenu = 0x007B;
    private const uint WmCommand = 0x0111;
    private const uint NinSelect = 0x0400;
    private const uint NinKeySelect = 0x0401;
    private const uint NimAdd = 0, NimModify = 1, NimDelete = 2, NimSetVersion = 4;
    private const uint NifMessage = 0x1, NifIcon = 0x2, NifTip = 0x4, NifShowTip = 0x80;
    private const uint NotifyIconVersion4 = 4;
    private const uint MfString = 0x0, MfGrayed = 0x1, MfSeparator = 0x800;
    private const uint TpmRightButton = 0x2, TpmBottomAlign = 0x20, TpmRightAlign = 0x8;
    private const uint WsExToolWindow = 0x80; // keeps the hidden window out of Alt+Tab

    private readonly WndProc _wndProc; // held: the delegate must outlive the window
    private readonly IntPtr _hwnd;
    private readonly uint _taskbarCreated;
    private readonly Action<int, int> _onSelect;
    private readonly Func<IReadOnlyList<MenuItem?>> _menu;
    private IntPtr _icon;
    private string _tooltip = "Tenshu Control";
    private List<MenuItem?> _shownMenu = [];

    /// <param name="onSelect">Left click, with the click's screen coordinates.</param>
    /// <param name="menu">The right-click menu, built fresh each time; <c>null</c> entries are separators.</param>
    public TrayIcon(Action<int, int> onSelect, Func<IReadOnlyList<MenuItem?>> menu)
    {
        _onSelect = onSelect;
        _menu = menu;
        _wndProc = Proc;
        var className = "TenshuTrayAgent." + Environment.ProcessId;
        var wc = new WndClassEx
        {
            cbSize = (uint)Marshal.SizeOf<WndClassEx>(),
            lpfnWndProc = Marshal.GetFunctionPointerForDelegate(_wndProc),
            hInstance = GetModuleHandle(null),
            lpszClassName = className,
        };
        if (RegisterClassEx(ref wc) == 0) throw new InvalidOperationException($"RegisterClassEx failed ({Marshal.GetLastWin32Error()})");
        _hwnd = CreateWindowEx(WsExToolWindow, className, "Tenshu tray", 0, 0, 0, 0, 0, IntPtr.Zero, IntPtr.Zero, wc.hInstance, IntPtr.Zero);
        if (_hwnd == IntPtr.Zero) throw new InvalidOperationException($"CreateWindowEx failed ({Marshal.GetLastWin32Error()})");
        _taskbarCreated = RegisterWindowMessage("TaskbarCreated");
        Add();
    }

    /// <summary>
    /// Whether the shell has the icon — the <c>visible</c> half of the heartbeat (<see cref="Heartbeat"/>). A running
    /// agent with no icon is the failure the heartbeat exists to expose.
    ///
    /// ⚠ Only after <see cref="FailuresBeforeHidden"/> failures in a row: <c>Shell_NotifyIcon</c> also returns FALSE
    /// when a busy Explorer times out on a call it then applies, and one such blip must not get a healthy agent
    /// replaced.
    /// </summary>
    public bool Placed => _failures < FailuresBeforeHidden;

    private const int FailuresBeforeHidden = 3;
    private int _failures = FailuresBeforeHidden; // not placed until the first add succeeds

    /// <summary>Replaces the icon (the caller's HICON is copied by the shell; this class destroys the previous one).</summary>
    public void Update(IntPtr icon, string tooltip)
    {
        var previous = _icon;
        _icon = icon;
        _tooltip = tooltip;
        Modify(NifIcon | NifTip | NifShowTip);
        if (previous != IntPtr.Zero) CatIcon.DestroyIcon(previous);
    }

    /// <summary>Refreshes only the tooltip; the icon stays as it is.</summary>
    public void UpdateTooltip(string tooltip)
    {
        _tooltip = tooltip;
        Modify(NifTip | NifShowTip);
    }

    private void Modify(uint flags)
    {
        var data = Data(flags);
        if (ShellNotifyIcon(NimModify, ref data)) { _failures = 0; return; }
        // The shell no longer knows the icon (a taskbar that came back without broadcasting TaskbarCreated, or an
        // add that failed at login because Explorer was not up yet): add it again rather than report it gone.
        Add();
    }

    private void Add()
    {
        var data = Data(NifMessage | NifIcon | NifTip | NifShowTip);
        if (ShellNotifyIcon(NimAdd, ref data)) _failures = 0;
        else _failures = Math.Min(_failures + 1, FailuresBeforeHidden);
        data.uTimeoutOrVersion = NotifyIconVersion4;
        ShellNotifyIcon(NimSetVersion, ref data);
    }

    private NotifyIconData Data(uint flags) => new()
    {
        cbSize = (uint)Marshal.SizeOf<NotifyIconData>(),
        hWnd = _hwnd,
        uID = 1,
        uFlags = flags,
        uCallbackMessage = CallbackMessage,
        hIcon = _icon,
        szTip = _tooltip,
    };

    private IntPtr Proc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == _taskbarCreated)
        {
            Add();
            return IntPtr.Zero;
        }
        if (msg == CallbackMessage)
        {
            // Version 4: LOWORD(lParam) is the event; wParam carries the anchor's screen coordinates.
            var ev = (uint)(lParam.ToInt64() & 0xFFFF);
            int x = (short)(wParam.ToInt64() & 0xFFFF), y = (short)((wParam.ToInt64() >> 16) & 0xFFFF);
            if (ev is NinSelect or NinKeySelect) _onSelect(x, y);
            else if (ev == WmContextMenu) ShowMenu(x, y);
            return IntPtr.Zero;
        }
        if (msg == WmCommand)
        {
            var index = (int)(wParam.ToInt64() & 0xFFFF) - 1;
            if (index >= 0 && index < _shownMenu.Count) _shownMenu[index]?.OnClick();
            return IntPtr.Zero;
        }
        return DefWindowProc(hwnd, msg, wParam, lParam);
    }

    private void ShowMenu(int x, int y)
    {
        _shownMenu = [.. _menu()];
        var menu = CreatePopupMenu();
        try
        {
            for (var i = 0; i < _shownMenu.Count; i++)
            {
                var item = _shownMenu[i];
                if (item == null) AppendMenu(menu, MfSeparator, UIntPtr.Zero, null);
                else AppendMenu(menu, MfString | (item.Enabled ? 0 : MfGrayed), (UIntPtr)(i + 1), item.Label);
            }
            // ⚠ Without this the menu does not close when the user clicks elsewhere (a documented quirk).
            SetForegroundWindow(_hwnd);
            TrackPopupMenuEx(menu, TpmRightButton | TpmBottomAlign | TpmRightAlign, x, y, _hwnd, IntPtr.Zero);
            // The other half of the same workaround: without a message after the menu, the next one may not open
            // or close properly.
            PostMessage(_hwnd, WmNull, IntPtr.Zero, IntPtr.Zero);
        }
        finally
        {
            DestroyMenu(menu);
        }
    }

    public void Dispose()
    {
        var data = Data(0);
        ShellNotifyIcon(NimDelete, ref data);
        if (_icon != IntPtr.Zero) CatIcon.DestroyIcon(_icon);
        DestroyWindow(_hwnd);
    }

    // ── Win32 ───────────────────────────────────────────────────────────────────────────────────────

    private delegate IntPtr WndProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WndClassEx
    {
        public uint cbSize, style;
        public IntPtr lpfnWndProc;
        public int cbClsExtra, cbWndExtra;
        public IntPtr hInstance, hIcon, hCursor, hbrBackground;
        public string? lpszMenuName;
        public string lpszClassName;
        public IntPtr hIconSm;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NotifyIconData
    {
        public uint cbSize;
        public IntPtr hWnd;
        public uint uID, uFlags, uCallbackMessage;
        public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string szTip;
        public uint dwState, dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string szInfo;
        public uint uTimeoutOrVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string szInfoTitle;
        public uint dwInfoFlags;
        public Guid guidItem;
        public IntPtr hBalloonIcon;
    }

    [DllImport("shell32.dll", EntryPoint = "Shell_NotifyIconW", CharSet = CharSet.Unicode)]
    private static extern bool ShellNotifyIcon(uint message, ref NotifyIconData data);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern ushort RegisterClassEx(ref WndClassEx wc);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr CreateWindowEx(uint exStyle, string className, string windowName, uint style,
        int x, int y, int w, int h, IntPtr parent, IntPtr menu, IntPtr instance, IntPtr param);

    [DllImport("user32.dll")] private static extern bool DestroyWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern IntPtr DefWindowProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern uint RegisterWindowMessage(string name);
    [DllImport("user32.dll")] private static extern IntPtr CreatePopupMenu();
    [DllImport("user32.dll")] private static extern bool DestroyMenu(IntPtr menu);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool AppendMenu(IntPtr menu, uint flags, UIntPtr id, string? text);
    [DllImport("user32.dll")] private static extern bool TrackPopupMenuEx(IntPtr menu, uint flags, int x, int y, IntPtr hwnd, IntPtr tpm);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);
    private const uint WmNull = 0x0000;
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr GetModuleHandle(string? name);
}
