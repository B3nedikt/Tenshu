# TrayAgent — Control's Windows tray agent (C# / WinUI 3)

The Windows twin of `../../macos/TrayAgent/` (decision D5 and §3.9 of
`docs/proposals/desktop-windows-port.md`): the Tenshu Control **cat** in the notification area, a
Windows 11-style **flyout** on a left click, a short native **menu** on a right click — all over Control's
JSON native bridge (`docs/protocol/desktop-ipc.md` §6.2).

## Files

- `BridgeClient.cs` — one request/response per connection over `bridge.sock`, framed
  `[4-byte BE length][type 0][UTF-8 JSON]`, exactly as the Swift client.
- `PeerPackage.cs` — the agent's half of the IPC gate: the bridge server must run in **this agent's own
  package family** (both are executables of the Control MSIX). A dev run without package identity needs
  `TENSHU_TRAY_DEV=1`. ⚠ The Swift peers do not check the server at all; this one does.
- `CatIcon.cs` — renders the cat at run time from the **embedded Android themed-icon vector**
  (`control/src/main/res/drawable/ic_launcher_monochrome.xml`): one source of truth, white on a dark taskbar,
  black on a light one, tight-cropped like the macOS template image, dimmed while paused or unreachable.
- `TrayIcon.cs` — `Shell_NotifyIcon` v4 over P/Invoke, a hidden top-level window for its callbacks
  (⚠ not message-only: those receive no `TaskbarCreated` broadcast after an Explorer restart), the native menu.
- `FlyoutWindow.cs` — the WinUI 3 flyout, built in code (no XAML compiler step), acrylic backdrop, anchored
  to the click inside the work area, hidden on focus loss. `TrayState` decodes `getSyncState`.
- `App.cs` / `Program.cs` — the XAML-less WinUI application (it provides `IXamlMetadataProvider` itself so the
  Fluent control styles load), a 10 s state poll, a single-instance mutex.

## Build

```bat
set PATH=C:\Program Files\dotnet;%PATH%
dotnet build TrayAgent.csproj -c Release
```

Needs the .NET 10 SDK. The Windows App SDK (pinned in the project) comes from NuGet. The output is
self-contained (`WindowsAppSDKSelfContained`), so it runs without the Windows App Runtime installed.

## Run standalone (dev)

```bat
set TENSHU_TRAY_DEV=1
bin\Release\net10.0-windows10.0.19041.0\win-x64\tray-agent.exe
```

⚠ Standalone, the agent has no package identity, so a **packaged** (strict) Control refuses it on the
bridge — the flyout then reads "Control is not running". It is reachable from a dev `:control-desktop:run`
Control, whose developer mode admits it. Inside the Control MSIX (T0's packaging step) neither caveat applies.
