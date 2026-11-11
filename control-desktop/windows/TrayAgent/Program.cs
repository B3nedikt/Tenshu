using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;

namespace Tenshu.TrayAgent;

/// <summary>
/// Entry point. There is no App.xaml (the UI is built in code), so this replaces the XAML-generated <c>Main</c>
/// (<c>DISABLE_XAML_GENERATED_MAIN</c> in the project).
/// </summary>
public static class Program
{
    /// <summary>The argument Control's AppxManifest gives the thumbnail ExeServer (desktop-msix.gradle.kts).</summary>
    public const string ThumbnailServerArgument = "--thumbnail-server";

    [STAThread]
    public static int Main(string[] args)
    {
        // Developer aid: `tray-agent --render-icon <out.png> <size> [light|dark]` writes the icon exactly as the
        // tray would draw it, so the mark can be checked without squinting at a 16-pixel notification area.
        if (args.Length >= 3 && args[0] == "--render-icon")
        {
            var light = args.Length >= 4 && args[3] == "light";
            using var bitmap = CatIcon.Render(int.Parse(args[2]), light ? System.Drawing.Color.Black : System.Drawing.Color.White, alpha: 255);
            bitmap.Save(args[1], System.Drawing.Imaging.ImageFormat.Png);
            return 0;
        }

        // Developer aid: `tray-agent --test-notifications [out.txt]`, run inside the Control package
        // (Invoke-CommandInDesktopPackage), posts a toast now and schedules one 30 s out.
        if (args.Length >= 1 && args[0] == "--test-notifications")
        {
            WinRT.ComWrappersSupport.InitializeComWrappers();
            var failure = Notifier.SelfTest();
            if (args.Length >= 2) File.WriteAllText(args[1], failure ?? "ok");
            return failure == null ? 0 : 1;
        }

        // `tray-agent --startup-task status|enable|disable`: Control's start-at-login switch (LoginStart).
        if (args.Length >= 2 && args[0] == "--startup-task")
        {
            WinRT.ComWrappersSupport.InitializeComWrappers();
            return LoginStart.RunCli(args[1], Console.Out);
        }

        // Started by COM to draw Explorer thumbnails (the manifest's ExeServer for ThumbnailProvider) while no tray is
        // running: serve them, show nothing, exit once idle. A running tray serves them itself (App.OnLaunched).
        if (args.Contains(ThumbnailServerArgument))
        {
            WinRT.ComWrappersSupport.InitializeComWrappers();
            ThumbnailServer.RunUntilIdle(TimeSpan.FromMinutes(2));
            return 0;
        }

        // One tray per user session: Control may start the agent again while one is already running.
        using var single = new Mutex(initiallyOwned: true, @"Local\Tenshu.TrayAgent", out var first);
        if (!first) return 0;
        // A thumbnail server that died left its pid behind; were it this process's pid now, Control's supervisor would
        // pass over the real tray (ThumbnailServer.PidFile).
        ThumbnailServer.ForgetStalePid();

        // Packaged (a child of the Control MSIX): the Windows App Runtime comes from the package graph, through the
        // dependency Control's manifest declares. Standalone (a dev run): attach to the installed runtime by hand.
        if (PeerPackage.OwnFamilyName() == null)
        {
            Microsoft.Windows.ApplicationModel.DynamicDependency.Bootstrap.Initialize(0x00010008); // 1.8
        }

        WinRT.ComWrappersSupport.InitializeComWrappers();
        Application.Start(p =>
        {
            SynchronizationContext.SetSynchronizationContext(
                new DispatcherQueueSynchronizationContext(DispatcherQueue.GetForCurrentThread()));
            _ = new App();
        });
        return 0;
    }
}
