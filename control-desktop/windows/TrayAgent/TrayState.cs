using System.Text.Json.Nodes;

namespace Tenshu.TrayAgent;

/// <summary>
/// Everything the flyout shows, decoded from four bridge calls: <c>getSyncState</c>, <c>pendingRequests</c>,
/// <c>recentNotifications</c> and <c>listApps</c>. The C# twin of the Swift agent's <c>TrayModel</c> decoding —
/// the state code is authoritative (bgproc §5.3); this class only maps codes to this agent's strings.
/// </summary>
internal sealed record TrayState(
    bool Reachable,
    bool Paused,
    bool Enabled,
    string Label,
    bool Warning,
    string? Detail,
    IReadOnlyList<string> Requests,
    IReadOnlyList<string> Notifications,
    IReadOnlyList<AppRow> Apps)
{
    private static Strings S => Strings.Current;

    public static readonly TrayState Unknown = new(false, false, false, S.Connecting, false, null, [], [], []);

    /// <summary>The technical reason is kept for the debugger, never shown: a socket path tells a user nothing.</summary>
    public string Reason { get; init; } = "";

    public static TrayState Unreachable(string why) =>
        new(false, false, false, S.NotRunning, true, null, [], [], []) { Reason = why };

    public static TrayState From(
        JsonObject sync,
        JsonObject? requests,
        JsonObject? notifications,
        IReadOnlyList<AppRow> apps)
    {
        var (label, warning) = Display(Str(sync, "state"));
        return new TrayState(
            Reachable: true,
            Paused: Bool(sync, "paused"),
            Enabled: Bool(sync, "enabled"),
            Label: label,
            Warning: warning,
            Detail: DivergenceDetail(sync),
            Requests: Rows(requests, "requests").Select(r => RequestTitle(Str(r, "kind"))).ToList(),
            Notifications: Rows(notifications, "notifications").Select(NotificationText).ToList(),
            Apps: apps);
    }

    /// <summary>The headline for the daemon's state code; <c>Warning</c> tints it for the states to act on.</summary>
    private static (string, bool) Display(string? state) => state switch
    {
        "SYNCED" => (S.Synced, false),
        "SYNCING" => (S.Syncing, false),
        "OFFLINE" => (S.Offline, true),
        "DIVERGENCE" => (S.ConsistencyWarning, true),
        "NEEDS_UPDATE" => (S.NeedsUpdate, true),
        "PAUSED" => (S.Paused, false),
        "LOCKED" => (S.Locked, false),
        "DISABLED" => (S.SyncOff, false),
        _ => (S.Unknown, false),
    };

    /// <summary>
    /// Keyed off <c>divergenceWarning</c> (the raw signal), not <c>divergenceFresh</c>: an aged-out warning leaves the
    /// headline but stays findable here. The fallbacks get vaguer, never braver — naming the wrong device would be
    /// worse than naming none.
    /// </summary>
    private static string? DivergenceDetail(JsonObject s)
    {
        if (!Bool(s, "divergenceWarning")) return null;
        var name = Str(s, "divergencePeerName") ?? ShortDeviceId(Str(s, "divergencePeerDeviceId")) ?? S.AnotherDevice;
        var at = Long(s, "divergenceObservedAtMillis") is > 0 and var millis
            ? string.Format(S.AtTime, DateTimeOffset.FromUnixTimeMilliseconds(millis).ToLocalTime().ToString("t"))
            : "";
        return string.Format(Str(s, "divergenceKind") == "SIGNATURE_MISMATCH" ? S.SignatureMismatch : S.ClockRegression, name, at);
    }

    private static string? ShortDeviceId(string? id) =>
        string.IsNullOrEmpty(id) ? null : id.Length > 8 ? id[..8] + "…" : id;

    /// <summary>A PII-free title per request kind; the request id is a diff key, never copy.</summary>
    internal static string RequestTitle(string? kind) => kind switch
    {
        "membership_grant" => S.RequestMembershipGrant,
        "contact_invite" => S.RequestContactInvite,
        _ => S.RequestDefault,
    };

    private static string NotificationText(JsonObject row)
    {
        var name = Str(row, "name") ?? "";
        return Str(row, "kind") switch
        {
            "file_added" => string.Format(S.FileAdded, name),
            "folder_added" => string.Format(S.FolderAdded, name),
            "files_added" => string.Format(S.FilesAdded, name, Long(row, "otherCount") ?? 0),
            "file_deleted" => string.Format(S.FileDeleted, name),
            "folder_deleted" => string.Format(S.FolderDeleted, name),
            "change_lost" => string.Format(S.ChangeLost, name),
            "app_registered" => string.Format(S.AppRegistered, name),
            "app_removed" => string.Format(S.AppRemoved, name),
            _ => name,
        };
    }

    internal static IEnumerable<JsonObject> Rows(JsonObject? result, string key) =>
        (result?[key] as JsonArray)?.OfType<JsonObject>() ?? [];

    internal static string? Str(JsonObject o, string key) =>
        o[key] is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;

    internal static bool Bool(JsonObject o, string key, bool fallback = false) =>
        o[key] is JsonValue v && v.TryGetValue<bool>(out var b) ? b : fallback;

    internal static long? Long(JsonObject o, string key) =>
        o[key] is JsonValue v && v.TryGetValue<long>(out var l) ? l
        : o[key] is JsonValue d && d.TryGetValue<double>(out var x) ? (long)x
        : null;
}

/// <summary>One launchable app. <c>Aumid</c> is the installed package's app-list id, resolved by <see cref="AppLauncher"/>.</summary>
internal sealed record AppRow(string AppId, string Name, bool Registered, bool Verified, string Aumid);
