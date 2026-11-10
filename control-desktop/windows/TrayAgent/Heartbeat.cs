namespace Tenshu.TrayAgent;

/// <summary>
/// The agent's self-report beside the bridge socket — the Windows twin of <c>TrayHeartbeat.swift</c>
/// (desktop-tray-liveness.md §4.1, desktop-windows-port.md §3.9 T3).
///
/// ⚠⚠ Why: a running process is not a working tray. The single-instance mutex means an agent that is alive but
/// wedged, or alive with no icon, would otherwise block every replacement Control starts. Only the agent can say
/// both that its UI thread is turning (the timestamp — written from that thread) and that the shell accepted its
/// icon (the verdict). Control reads it with the same <c>TrayLiveness</c> table the macOS tray uses.
///
/// Format and file names are shared with macOS and pinned by <c>TrayLiveness</c>: <c>&lt;epochMillis&gt;
/// visible|hidden</c>, rewritten every <see cref="Interval"/>.
/// </summary>
internal static class Heartbeat
{
    public const string FileName = "tray-agent.state";
    public const string JournalFileName = "tray-agent.placement.log";
    public const int JournalMaxLines = 100;

    /// <summary>Mirrored in <c>TrayLiveness.HEARTBEAT_INTERVAL_SECONDS</c>; Control allows three missed ones.</summary>
    public static readonly TimeSpan Interval = TimeSpan.FromSeconds(10);

    public static string Line(bool visible, DateTimeOffset at) =>
        $"{at.ToUnixTimeMilliseconds()} {(visible ? "visible" : "hidden")}\n";

    /// <summary>
    /// Atomic (write a sibling, then move it over): the reader treats a malformed file as "no heartbeat", which would
    /// replace a healthy agent. Best effort otherwise — a tray that cannot report must still show its icon.
    /// </summary>
    public static void Publish(string dir, bool visible, DateTimeOffset at)
    {
        try
        {
            Directory.CreateDirectory(dir);
            var target = Path.Combine(dir, FileName);
            var temp = target + ".tmp";
            File.WriteAllText(temp, Line(visible, at));
            File.Move(temp, target, overwrite: true);
        }
        catch (Exception) { }
    }

    /// <summary>
    /// One line per <b>change</b> of verdict, append-only and capped: the level file above overwrites its own history,
    /// so an icon that failed at login and recovered ten seconds later would leave no trace there.
    /// </summary>
    public static void RecordTransition(string dir, bool visible, DateTimeOffset at)
    {
        try
        {
            var path = Path.Combine(dir, JournalFileName);
            var lines = File.Exists(path) ? File.ReadAllLines(path).Where(l => l.Length > 0).ToList() : [];
            lines.Add(Line(visible, at).TrimEnd('\n')); // one spelling of the format TrayLiveness parses
            File.WriteAllLines(path, lines.TakeLast(JournalMaxLines));
        }
        catch (Exception) { }
    }
}
