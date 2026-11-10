using System.Security;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;
using Windows.UI.Notifications;

namespace Tenshu.TrayAgent;

/// <summary>
/// The agent's four OS notifiers (desktop-windows-port.md §3.9 T2) — the Windows twins of the Swift agent's
/// <c>PendingRequestNotifier</c>, <c>PairingApprovalNotifier</c>, <c>SchemaFenceNotifier</c> and
/// <c>ReminderNotifier</c>. Posted here rather than by the JVM daemon for the reason macOS has: the toast belongs
/// to the package's app identity, which this agent shares with Control.
///
/// Every tap does one thing — raise Control — so there is one activation handler and no per-toast routing. The
/// toast activator is declared in Control's AppxManifest (<c>windows.toastNotificationActivation</c>, pointing at
/// this exe), so a click also works when the agent is not running: Windows starts it to take the click.
///
/// ⚠⚠ Copy is generic and PII-free, exactly as on macOS: a request's kind, never the grantor or a claimed name; a
/// pairing alert with no device name and no SAS, so it can never approve anything. Reminder text arrives
/// <b>already redacted</b> by the daemon (threat model A5) and is never enriched — once handed to the scheduler,
/// Windows holds the string for days and this process never sees it again.
///
/// ⚠ Each <c>Reconcile*</c> is fed only by a <b>successful</b> poll. A failed one must not be read as "nothing
/// pending": that would withdraw live alerts and cancel armed reminders every time Control restarted.
/// </summary>
internal sealed class Notifier
{
    private const string RequestGroup = "request";
    private const string PairingTag = "pairing";
    private const string FenceTag = "schemaFence";
    private const string ReminderGroup = "reminder";
    private const string OpenArgument = "action=openControl";

    private static Strings S => Strings.Current;

    private readonly bool _available;

    /// <summary>How late a reminder may first be seen and still be shown rather than dropped.</summary>
    private static readonly TimeSpan ImminentGrace = TimeSpan.FromMinutes(1);

    /// <summary>Reminder keys this process saw in, or put into, the OS schedule: the scheduler delivers those.</summary>
    private readonly HashSet<string> _everArmed = [];

    /// <summary>Reminder keys this process showed at once because they were already due.</summary>
    private readonly HashSet<string> _shownNow = [];
    private readonly HashSet<string> _requests = [];
    private string? _pairing;
    /// <summary>Null until this process's first known verdict: a previous agent's alert may still be on screen.</summary>
    private bool? _fenceAlerted;

    /// <param name="onActivate">Raises Control; called on a background thread.</param>
    public Notifier(Action onActivate)
    {
        // ⚠ Package identity only, like the Swift agent's bundle-id gate: an unpackaged dev run would register
        // itself under a made-up identity in the user's registry. The flyout still lists everything.
        _available = PeerPackage.OwnFamilyName() != null;
        if (!_available) return;
        try
        {
            AppNotificationManager.Default.NotificationInvoked += (_, _) => onActivate();
            AppNotificationManager.Default.Register();
        }
        catch (Exception)
        {
            _available = false;
            return;
        }
        // Seed from what is already on screen, so a restarted agent does not re-alert requests it posted before.
        try
        {
            foreach (var shown in AppNotificationManager.Default.GetAllAsync().AsTask().GetAwaiter().GetResult())
            {
                if (shown.Group == RequestGroup) _requests.Add(shown.Tag);
            }
        }
        catch (Exception) { }
    }

    /// <summary>True when Windows started this process to deliver a toast click.</summary>
    public static bool LaunchedByToast()
    {
        try
        {
            return Microsoft.Windows.AppLifecycle.AppInstance.GetCurrent().GetActivatedEventArgs().Kind
                == Microsoft.Windows.AppLifecycle.ExtendedActivationKind.AppNotification;
        }
        catch (Exception)
        {
            return false;
        }
    }

    public void Unregister()
    {
        if (_available) try { AppNotificationManager.Default.Unregister(); } catch (Exception) { }
    }

    /// <summary>Posts the newly arrived requests, withdraws the resolved ones.</summary>
    public void ReconcileRequests(JsonObject result)
    {
        var current = TrayState.Rows(result, "requests")
            .Select(r => (Tag: Key(TrayState.Str(r, "id") ?? ""), Kind: TrayState.Str(r, "kind")))
            .Where(r => r.Tag.Length > 0)
            .ToList();
        var currentTags = current.Select(r => r.Tag).ToHashSet();
        if (_available)
        {
            foreach (var (tag, kind) in current.Where(r => !_requests.Contains(r.Tag)))
            {
                Post(tag, RequestGroup, TrayState.RequestTitle(kind), S.RequestBody);
            }
            foreach (var resolved in _requests.Where(t => !currentTags.Contains(t))) Remove(resolved, RequestGroup);
        }
        _requests.Clear();
        _requests.UnionWith(currentTags);
    }

    /// <summary>One slot: the host pairing session is singular. The joiner id is a diff key only, never shown.</summary>
    public void ReconcilePairing(JsonObject result)
    {
        var id = TrayState.Bool(result, "pending") ? TrayState.Str(result, "id") ?? "" : null;
        if (_available)
        {
            if (id != null && id != _pairing)
            {
                Post(PairingTag, PairingTag, S.PairingTitle, S.PairingBody);
            }
            else if (id == null && _pairing != null)
            {
                Remove(PairingTag, PairingTag);
            }
        }
        _pairing = id;
    }

    /// <summary>
    /// Once per engagement: posted on the edge to fenced, withdrawn when the fence lifts. A <c>null</c> verdict (the
    /// daemon has not evaluated the fence since it started) is never an edge. No action button: there is no update
    /// route to point at (core-schema-skew-surface.md §8.5).
    /// </summary>
    public void ReconcileFence(JsonObject syncState)
    {
        if (syncState["schemaFenced"] is not JsonValue v || !v.TryGetValue<bool>(out var fenced)) return;
        if (fenced == _fenceAlerted) return;
        _fenceAlerted = fenced;
        if (!_available) return;
        if (fenced) Post(FenceTag, FenceTag, S.NeedsUpdate, S.FenceBody);
        else Remove(FenceTag, FenceTag);
    }

    /// <summary>
    /// Brings Windows' scheduled toasts in line with the daemon's plan, so a reminder fires whether or not Control
    /// (or this agent) is running (calendar-app.md §4.6(a)).
    ///
    /// ⚠⚠ Diffed against the OS's own schedule, not a per-process memory: a scheduled toast with a reused tag is
    /// <b>not</b> replaced, so a restarted agent working from memory would arm every reminder twice. Each scheduled
    /// toast's <c>Tag</c> is a hash of its content (planner id, text and time), so an unchanged reminder is left
    /// alone and a changed one is swapped. ⚠ Not <c>Id</c>: Windows rejects setting it (0x803E0120, 2026-11-10).
    /// ⚠ Pending only: a reminder already shown stays in the notification center — retracting an alert the user may
    /// not have read yet is worse than leaving one for an event that has since moved.
    /// ⚠ A reminder first seen when it is already due (within 5 s, or up to <see cref="ImminentGrace"/> late — Control
    /// just started, or the event was just created) cannot be scheduled any more; it is shown at once rather than
    /// dropped. Never twice: not if the notification center already has it, not if this process ever saw it armed
    /// (the scheduler then delivered it), and not if it was already shown here.
    /// </summary>
    public void ReconcileReminders(JsonObject result)
    {
        if (!_available) return;
        try
        {
            var notifier = ToastNotificationManager.CreateToastNotifier();
            var now = DateTimeOffset.Now;
            var desired = TrayState.Rows(result, "reminders")
                .Select(r => new
                {
                    Id = TrayState.Str(r, "id") ?? "",
                    Title = TrayState.Str(r, "title") ?? "",
                    Body = TrayState.Str(r, "body") ?? "",
                    At = DateTimeOffset.FromUnixTimeMilliseconds(TrayState.Long(r, "deliverAtEpochMs") ?? 0),
                })
                .Where(r => r.Id.Length > 0 && r.Title.Length > 0)
                .Select(r => (Row: r, ContentKey: Key($"{r.Id}\n{r.Title}\n{r.Body}\n{r.At.ToUnixTimeMilliseconds()}")))
                .ToList();
            var desiredKeys = desired.Select(d => d.ContentKey).ToHashSet();

            var scheduled = notifier.GetScheduledToastNotifications().Where(t => t.Group == ReminderGroup).ToList();
            foreach (var stale in scheduled.Where(t => !desiredKeys.Contains(t.Tag))) notifier.RemoveFromSchedule(stale);
            var armed = scheduled.Select(t => t.Tag).ToHashSet();
            _everArmed.UnionWith(armed);

            foreach (var (row, contentKey) in desired)
            {
                if (armed.Contains(contentKey)) continue;
                var xml = new Windows.Data.Xml.Dom.XmlDocument();
                xml.LoadXml(ToastXml(row.Title, row.Body));
                if (row.At > now.AddSeconds(5))
                {
                    notifier.AddToSchedule(new ScheduledToastNotification(xml, row.At)
                    {
                        Tag = contentKey,
                        Group = ReminderGroup,
                    });
                    _everArmed.Add(contentKey);
                    continue;
                }
                // Due now or just past: too late to schedule (it would throw or never fire), so show it now — once.
                if (row.At < now - ImminentGrace || _everArmed.Contains(contentKey) || !_shownNow.Add(contentKey)) continue;
                if (InNotificationCenter(contentKey)) continue;
                notifier.Show(new ToastNotification(xml) { Tag = contentKey, Group = ReminderGroup });
            }
        }
        catch (Exception)
        {
            // Best effort, like every other notifier: the next poll tries again.
        }
    }

    /// <summary>
    /// Developer aid (<c>tray-agent --test-notifications</c>, run inside the Control package): one toast now and one
    /// scheduled 30 s out, through the same code paths as the real notifiers. Returns what failed, or null.
    /// </summary>
    public static string? SelfTest()
    {
        try
        {
            Post("selftest", "selftest", "Tenshu Control test", "Shown now. Clicking opens Control.");
            var notifier = ToastNotificationManager.CreateToastNotifier();
            var xml = new Windows.Data.Xml.Dom.XmlDocument();
            xml.LoadXml(ToastXml("Tenshu Control test reminder", "Scheduled 30 s ahead."));
            notifier.AddToSchedule(new ScheduledToastNotification(xml, DateTimeOffset.Now.AddSeconds(30))
            {
                Tag = "selftest-r", Group = "selftest",
            });
            return null;
        }
        catch (Exception e)
        {
            return e.ToString();
        }
    }

    private static bool InNotificationCenter(string tag)
    {
        try { return ToastNotificationManager.History.GetHistory().Any(t => t.Tag == tag && t.Group == ReminderGroup); }
        catch (Exception) { return false; }
    }

    private static void Post(string tag, string group, string title, string body)
    {
        try
        {
            var notification = new AppNotificationBuilder()
                .AddArgument("action", "openControl")
                .AddText(title)
                .AddText(body)
                .BuildNotification();
            // Same tag + group ⇒ a re-post replaces rather than stacks, and a withdrawal removes exactly this one.
            notification.Tag = tag;
            notification.Group = group;
            AppNotificationManager.Default.Show(notification);
        }
        catch (Exception e) when (tag != "selftest") { _ = e; }
    }

    private static void Remove(string tag, string group)
    {
        try { _ = AppNotificationManager.Default.RemoveByTagAndGroupAsync(tag, group); } catch (Exception) { }
    }

    private static string ToastXml(string title, string body) =>
        $"<toast launch=\"{OpenArgument}\"><visual><binding template=\"ToastGeneric\">" +
        $"<text>{SecurityElement.Escape(title)}</text>" +
        (body.Length > 0 ? $"<text>{SecurityElement.Escape(body)}</text>" : "") +
        "</binding></visual></toast>";

    /// <summary>A 16-character key: Windows caps toast tags and scheduled ids, and the daemon's ids are opaque anyway.</summary>
    private static string Key(string value) =>
        value.Length == 0 ? "" : Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)), 0, 8).ToLowerInvariant();
}
