package app.tenshu.control.data

/**
 * Every code-signing identifier a **first-party desktop peer** may legitimately present to
 * Control's IPC sockets (`control-app-catalogue.md` C2).
 *
 * This narrows the desktop peer-credential gate from *"any identifier signed by our team"* to
 * *"this identifier, for something we actually ship"*. The team pin was never an impersonation
 * hole — a binary's identifier is fixed by its signature, so claiming `app.tenshu.fit` requires a
 * binary signed by us — but the narrowing catches **our own** mistakes (a mis-set `bundleID`, a
 * mis-signed build) and makes the allowlist and the attestation agree by construction.
 *
 * ⚠⚠ **Not every first-party peer is an app.** Control's tray agent is an executable embedded in
 * Control's own bundle; it signs as `tray-agent`, carries no manifest, and never registers, yet
 * it drives `bridge.sock` on every poll. A policy derived from the *app* catalogue alone would
 * reject Control's own helper the moment the gate went strict — so peer *kind* is modelled here
 * rather than discovered in production.
 */
object FirstPartyDesktopPeers {

    /**
     * Executables shipped **inside** a Tenshu bundle that talk to a Tenshu socket.
     *
     * They are deliberately not catalogue entries: a catalogue entry pairs an identifier with a
     * *manifest*, and a helper has nothing to describe — no name, no permissions, no Apps-screen
     * presence. Modelling them as apps would mean inventing manifests for things the user should
     * never see.
     *
     * ⚠ Only helpers that open a socket belong here. `qr-scan-helper` and `image-decode-helper`
     * are spawned subprocesses that talk over stdin/stdout pipes, so they are never IPC peers and
     * must not be listed — an entry for them would widen the gate for no reason.
     *
     * ⚠ `app.tenshu.drive.ios.fileprovider` is the Drive File Provider `.appex` — its
     * `CFBundleIdentifier`, which is what `codesign` stamps on a bundle. Measured, not guessed:
     * without it the extension reaches `bridge.sock` and every Finder listing fails with
     * "socket closed mid-frame" (2026-10-04). `tenshu-domain`, the domain registrar beside it,
     * opens no socket and is deliberately absent.
     */
    val HELPER_IDENTIFIERS: Set<String> = setOf("tray-agent", "app.tenshu.drive.ios.fileprovider")

    /** Identifiers declared by apps in [BundledAppCatalogue] that ship a signed macOS bundle. */
    val APP_IDENTIFIERS: Set<String> get() = macIdentifiersOf(BundledAppCatalogue.apps)

    /** Everything the gate may admit: first-party apps plus first-party helpers. */
    val IDENTIFIERS: Set<String> get() = APP_IDENTIFIERS + HELPER_IDENTIFIERS

    /**
     * Control's own main-bundle identifier (D12 shares it with iOS). A second Control, refused by
     * the single-instance guard, connects to the running one's `bridge.sock` to ask it to show its
     * window (`desktop-macos-distribution.md` §2.11).
     *
     * ⚠ Bridge only, never `ipc.sock`: Control is not an app and must not register as one. Admitting
     * it to the bridge grants nothing new, because that copy can already open the data root.
     * ⚠ Copies signed before D12 (`app.tenshu.control`) are deliberately absent: they predate the
     * guard, so they never ask.
     */
    val CONTROL_IDENTIFIERS: Set<String> = setOf("app.tenshu.control.ios")

    /** What `bridge.sock` admits: everything [IDENTIFIERS] does, plus [CONTROL_IDENTIFIERS]. */
    val BRIDGE_IDENTIFIERS: Set<String> get() = IDENTIFIERS + CONTROL_IDENTIFIERS

    /**
     * Windows: the MSIX package names (`ClientBinding.WindowsPackage`) the gate may admit on
     * `ipc.sock` — apps only. There are no Windows helpers: the tray lives in Control's own process
     * (`desktop-windows-port.md` §3.4), so nothing else of ours opens a socket yet.
     */
    val WINDOWS_PACKAGES: Set<String> get() = windowsPackagesOf(BundledAppCatalogue.apps)

    /**
     * Control's own package name, for `bridge.sock` only — a second Control asking the first to show
     * its window, as on macOS. ⚠ Provisional until the Partner Center reservation.
     */
    val WINDOWS_CONTROL_PACKAGES: Set<String> = setOf("Tenshu.Control")

    /** What `bridge.sock` admits on Windows. */
    val WINDOWS_BRIDGE_PACKAGES: Set<String> get() = WINDOWS_PACKAGES + WINDOWS_CONTROL_PACKAGES
}

/** The `apps → admissible Windows package names` derivation; see [macIdentifiersOf] for why it takes its input. */
internal fun windowsPackagesOf(apps: List<FirstPartyApp>): Set<String> =
    apps.flatMap { app -> app.clients.filterIsInstance<ClientBinding.WindowsPackage>() }
        .map { it.packageName }
        .toSet()

/**
 * The `apps → admissible identifiers` derivation, taking its input rather than reading
 * [BundledAppCatalogue] directly.
 *
 * Extracted 2026-08-10 for the same reason [clientIndexOf] was: the rule *"an app with no Mac
 * binding is not admissible"* used to be checked against the real catalogue, which worked only
 * while some app happened to lack a binding. Once Maps/Timeline/Home gained theirs, that subject
 * disappeared and the guard would have had to be deleted — or, worse, kept as a vacuous loop over
 * an empty list, which passes no matter what the derivation does.
 */
internal fun macIdentifiersOf(apps: List<FirstPartyApp>): Set<String> =
    apps.flatMap { app -> app.clients.filterIsInstance<ClientBinding.MacCodeSignature>() }
        .map { it.identifier }
        .toSet()
