using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;

namespace Tenshu.TrayAgent;

/// <summary>
/// Entry point. There is no App.xaml (the UI is built in code), so this replaces the XAML-generated <c>Main</c>
/// (<c>DISABLE_XAML_GENERATED_MAIN</c> in the project).
/// </summary>
public static class Program
{
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

        // One tray per user session: Control may start the agent again while one is already running.
        using var single = new Mutex(initiallyOwned: true, @"Local\Tenshu.TrayAgent", out var first);
        if (!first) return 0;

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
