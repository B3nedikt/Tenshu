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
    public static int Main()
    {
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
