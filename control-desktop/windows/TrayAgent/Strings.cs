using System.Globalization;

namespace Tenshu.TrayAgent;

/// <summary>
/// The agent's display strings. The agent owns its copy (ADR 0016: each surface owns its strings) — the bridge sends
/// codes and names, never prose — so these are the Windows twins of the Swift agent's literals.
///
/// English and German, picked once from the Windows display language. ⚠ Keep the two tables in step: a key missing
/// from one fails to compile, which is the point of a record over a resource lookup.
/// </summary>
internal sealed record Strings
{
    public static readonly Strings Current = ForLanguage(CultureInfo.CurrentUICulture.TwoLetterISOLanguageName);

    /// <summary>The table for a two-letter language code; English for anything without one.</summary>
    public static Strings ForLanguage(string twoLetterCode) => twoLetterCode == "de" ? German() : English();

    public required string Title { get; init; }
    public required string Connecting { get; init; }
    public required string NotRunning { get; init; }
    public required string NotRunningBody { get; init; }

    public required string Synced { get; init; }
    public required string Syncing { get; init; }
    public required string Offline { get; init; }
    public required string ConsistencyWarning { get; init; }
    public required string NeedsUpdate { get; init; }
    public required string Paused { get; init; }
    public required string Locked { get; init; }
    public required string SyncOff { get; init; }
    public required string Unknown { get; init; }

    public required string AnotherDevice { get; init; }
    /// <summary>{0} device name, {1} " at 14:02" or "".</summary>
    public required string SignatureMismatch { get; init; }
    public required string ClockRegression { get; init; }
    public required string AtTime { get; init; }

    public required string NeedsAction { get; init; }
    public required string Recent { get; init; }
    public required string Apps { get; init; }
    public required string CouldNotOpen { get; init; }
    public required string Unverified { get; init; }

    public required string RequestMembershipGrant { get; init; }
    public required string RequestContactInvite { get; init; }
    public required string RequestDefault { get; init; }
    public required string RequestBody { get; init; }
    public required string PairingTitle { get; init; }
    public required string PairingBody { get; init; }
    public required string FenceBody { get; init; }

    public required string FileAdded { get; init; }
    public required string FolderAdded { get; init; }
    public required string FilesAdded { get; init; }
    public required string FileDeleted { get; init; }
    public required string FolderDeleted { get; init; }
    public required string ChangeLost { get; init; }
    public required string AppRegistered { get; init; }
    public required string AppRemoved { get; init; }

    public required string OpenControl { get; init; }
    public required string PauseSync { get; init; }
    public required string ResumeSync { get; init; }
    public required string Quit { get; init; }
    public required string QuitMenu { get; init; }

    private static Strings English() => new()
    {
        Title = "Tenshu Control",
        Connecting = "Connecting…",
        NotRunning = "Not running",
        NotRunningBody = "Control isn't running. Open it to start syncing.",

        Synced = "Synced",
        Syncing = "Syncing…",
        Offline = "Offline",
        // ⚠ Not "Sync issue detected": every other signal can be green. One peer's signed clock did not line up.
        ConsistencyWarning = "Consistency warning",
        // Names the app, never the service (core-schema-skew-surface.md §4).
        NeedsUpdate = "Update Control to keep syncing",
        Paused = "Paused",
        Locked = "Locked",
        SyncOff = "Sync off",
        Unknown = "Unknown",

        AnotherDevice = "Another device",
        SignatureMismatch = "Couldn't verify {0}'s sync signature{1}.",
        ClockRegression = "{0} reported an out-of-order sync clock{1}.",
        AtTime = " at {0}",

        NeedsAction = "Needs action",
        Recent = "Recent",
        Apps = "Apps",
        CouldNotOpen = "couldn't open",
        Unverified = "Not verified",

        RequestMembershipGrant = "Someone wants to share a folder with you",
        RequestContactInvite = "A contact accepted your invite",
        RequestDefault = "A request needs your review",
        RequestBody = "Open Control to review.",
        // No device name and no SAS: the toast can never approve; Control's modal shows the code to compare.
        PairingTitle = "A device wants to join",
        PairingBody = "Open Control to review and approve.",
        FenceBody = "Tenshu Control on this PC is older than on your other devices, so new changes aren't arriving. Your files here are safe.",

        FileAdded = "Added {0}",
        FolderAdded = "Added folder {0}",
        FilesAdded = "Added {0} + {1} more",
        FileDeleted = "Deleted {0}",
        FolderDeleted = "Deleted folder {0}",
        ChangeLost = "{0} was deleted on another device while your change was waiting",
        AppRegistered = "{0} connected",
        AppRemoved = "{0} removed",

        OpenControl = "Open Control",
        PauseSync = "Pause sync",
        ResumeSync = "Resume sync",
        Quit = "Quit",
        QuitMenu = "Quit Tenshu Control",
    };

    private static Strings German() => new()
    {
        Title = "Tenshu Control",
        Connecting = "Verbinde…",
        NotRunning = "Läuft nicht",
        NotRunningBody = "Control läuft nicht. Öffne es, um zu synchronisieren.",

        Synced = "Synchronisiert",
        Syncing = "Synchronisiere…",
        Offline = "Offline",
        ConsistencyWarning = "Konsistenzwarnung",
        NeedsUpdate = "Aktualisiere Control, um weiter zu synchronisieren",
        Paused = "Pausiert",
        Locked = "Gesperrt",
        SyncOff = "Sync aus",
        Unknown = "Unbekannt",

        AnotherDevice = "Ein anderes Gerät",
        SignatureMismatch = "Die Sync-Signatur von {0} konnte nicht geprüft werden{1}.",
        ClockRegression = "{0} hat eine Sync-Uhr außer der Reihe gemeldet{1}.",
        AtTime = " um {0}",

        NeedsAction = "Aktion erforderlich",
        Recent = "Zuletzt",
        Apps = "Apps",
        CouldNotOpen = "konnte nicht geöffnet werden",
        Unverified = "Nicht verifiziert",

        RequestMembershipGrant = "Jemand möchte einen Ordner mit dir teilen",
        RequestContactInvite = "Ein Kontakt hat deine Einladung angenommen",
        RequestDefault = "Eine Anfrage wartet auf dich",
        RequestBody = "Öffne Control, um sie zu prüfen.",
        PairingTitle = "Ein Gerät möchte beitreten",
        PairingBody = "Öffne Control, um es zu prüfen und zu bestätigen.",
        FenceBody = "Tenshu Control auf diesem PC ist älter als auf deinen anderen Geräten, deshalb kommen neue Änderungen nicht an. Deine Dateien hier sind sicher.",

        FileAdded = "{0} hinzugefügt",
        FolderAdded = "Ordner {0} hinzugefügt",
        FilesAdded = "{0} und {1} weitere hinzugefügt",
        FileDeleted = "{0} gelöscht",
        FolderDeleted = "Ordner {0} gelöscht",
        ChangeLost = "{0} wurde auf einem anderen Gerät gelöscht, während deine Änderung wartete",
        AppRegistered = "{0} verbunden",
        AppRemoved = "{0} entfernt",

        OpenControl = "Control öffnen",
        PauseSync = "Sync pausieren",
        ResumeSync = "Sync fortsetzen",
        Quit = "Beenden",
        QuitMenu = "Tenshu Control beenden",
    };
}
