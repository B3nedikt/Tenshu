package app.tenshu.control.data

import org.tenshu.core.app.AppManifest

/**
 * How a first-party app presents itself to the proxy on one platform — the value the IPC
 * handshake produces as its `clientId`.
 *
 * It is a sealed set rather than a bare string because *how the id is attested* differs per
 * platform, and that difference is the thing worth keeping legible
 * (`desktop-ipc-client-identity.md`, `control-app-catalogue.md` D1).
 */
sealed interface ClientBinding {

    /** The literal id the proxy will see for this binding. */
    val clientId: String

    /**
     * Android's `applicationId`, resolved by `ProxyService` from `Binder.getCallingUid()` via
     * the `PackageManager` — the caller cannot state it.
     */
    data class AndroidPackage(val packageName: String) : ClientBinding {
        override val clientId: String get() = packageName
    }

    /**
     * A macOS bundle's **code-signing identifier**, attested against a Team-ID-pinned
     * requirement by the desktop peer-credential gate.
     *
     * ⚠ Adding one is not just a table edit: the bundle must actually carry that identifier
     * (`bundleID` pinned in its `nativeDistributions.macOS` block) and be signed
     * (`signDesktopApp`). An unsigned build attests as nothing, lands under an `unattested-…`
     * id, and silently never registers.
     */
    data class MacCodeSignature(val identifier: String) : ClientBinding {
        override val clientId: String get() = identifier
    }

    /**
     * A Windows MSIX package's identity **Name** — the half of its package family name before the
     * `_`. Resolved by Control's Windows peer gate from the connecting process
     * (`GetPackageFamilyName`), and admitted only when the other half, the publisher id, is in the
     * compiled-in anchor (`docs/proposals/desktop-windows-port.md` §3.2, D1).
     *
     * ⚠⚠ **The Store assigns these names** when the app name is reserved in Partner Center (they
     * look like `<publisher>.<App>`). The values in [BundledAppCatalogue] are provisional until that
     * reservation and must then be replaced by the reserved ones — a mismatch admits nothing, it
     * does not widen anything.
     */
    data class WindowsPackage(val packageName: String) : ClientBinding {
        override val clientId: String get() = packageName
    }

    // iOS route-1 contributes no binding on purpose: a first-party app there hosts its own
    // in-process proxy, so there is no IPC peer to identify. Third-party iOS rides EPC and will
    // bring its own binding kind when that lands.
}

/**
 * One first-party app, declared once: what it is ([manifest]) and how it presents itself on each
 * platform ([clients]).
 *
 * The point of pairing them here is that a manifest and a client binding **cannot drift apart**.
 * They used to live in two tables joined by an `appId` string, and a miss degraded silently —
 * `TenshuAppRegistry` falls back to `name = manifest?.name ?: appId`, which is how three apps
 * came to render as raw ids (`control-app-catalogue.md` §1).
 */
data class FirstPartyApp(
    val manifest: AppManifest,
    val clients: List<ClientBinding>,
) {
    val appId: String get() = manifest.appId
}

/**
 * The single declaration of the first-party apps Tenshu ships
 * (`control-app-catalogue.md` D1 / C1).
 *
 * `BundledAppIdentityResolver` (*who is calling*) and `BundledAppManifestSource` (*what is it*)
 * are both **derived** from this list, so the old "allowlist entry with no manifest" failure
 * (§3c) is not a bug to be tested for — it is unrepresentable. Adding an app is one entry here
 * rather than edits to two tables in two files.
 *
 * ⚠ The manifests themselves still live in `BundledAppManifestSource`'s companion; only the
 * *pairing* moved. Initialisation order is safe because that companion holds plain values and
 * never reads this object back — the dependency runs one way, from catalogue to manifests.
 *
 * This is the `ReiSource`-shaped seam the resolver's KDoc has always predicted: third-party apps
 * arrive as the same shape from a different source, with their own signing identity as the trust
 * root rather than ours.
 */
object BundledAppCatalogue {

    val apps: List<FirstPartyApp> = listOf(
        FirstPartyApp(
            manifest = BundledAppManifestSource.DRIVE_MANIFEST,
            clients = listOf(
                ClientBinding.AndroidPackage("app.tenshu.drive.android"),
                ClientBinding.MacCodeSignature("app.tenshu.drive.ios"),
                // ⚠ Provisional until the Partner Center name reservation (WindowsPackage KDoc).
                ClientBinding.WindowsPackage("Tenshu.Drive"),
            ),
        ),
        FirstPartyApp(
            manifest = BundledAppManifestSource.FIT_MANIFEST,
            clients = listOf(
                ClientBinding.AndroidPackage("app.tenshu.fit.android"),
                ClientBinding.MacCodeSignature("app.tenshu.fit.ios"),
            ),
        ),
        FirstPartyApp(
            manifest = BundledAppManifestSource.MAPS_MANIFEST,
            clients = listOf(
                ClientBinding.AndroidPackage("app.tenshu.maps.android"),
                ClientBinding.MacCodeSignature("app.tenshu.maps.ios"),
            ),
        ),
        FirstPartyApp(
            manifest = BundledAppManifestSource.TIMELINE_MANIFEST,
            clients = listOf(
                ClientBinding.AndroidPackage("app.tenshu.timeline.android"),
                ClientBinding.MacCodeSignature("app.tenshu.timeline.ios"),
            ),
        ),
        FirstPartyApp(
            manifest = BundledAppManifestSource.HOME_MANIFEST,
            clients = listOf(
                ClientBinding.AndroidPackage("app.tenshu.home.android"),
                ClientBinding.MacCodeSignature("app.tenshu.home.ios"),
            ),
        ),
        // Mia joined on 2026-08-11 with MQ-1, which gave it the client module the note
        // here used to wait for: `miaClientModule` calls `wireSchemaAnnounce`, so there is
        // now a session handshake for the registration trigger to fire on.
        FirstPartyApp(
            manifest = BundledAppManifestSource.MIA_MANIFEST,
            clients = listOf(
                ClientBinding.AndroidPackage("app.tenshu.mia.android"),
                ClientBinding.MacCodeSignature("app.tenshu.mia.ios"),
            ),
        ),
        // Calendar and Contacts joined on 2026-08-11, when they finally got the manifests this
        // note used to wait for. ⚠ Until then the desktop apps could not work at all: the
        // peer-credential gate derives its allowlist from this list, so `app.tenshu.calendar`
        // and `app.tenshu.contacts` were rejected at the socket on every attempt
        // (`no team may vouch for claimed identifier …`) and the UI spun forever on a session
        // that could never open. Both bundles were correctly signed the whole time — being
        // *unknown* to the gate is refused exactly as hard as being untrusted, which is the
        // intended behaviour and why the absence was safe but invisible.
        FirstPartyApp(
            manifest = BundledAppManifestSource.CALENDAR_MANIFEST,
            clients = listOf(
                ClientBinding.AndroidPackage("app.tenshu.calendar.android"),
                ClientBinding.MacCodeSignature("app.tenshu.calendar.ios"),
            ),
        ),
        FirstPartyApp(
            manifest = BundledAppManifestSource.CONTACTS_MANIFEST,
            clients = listOf(
                ClientBinding.AndroidPackage("app.tenshu.contacts.android"),
                ClientBinding.MacCodeSignature("app.tenshu.contacts.ios"),
            ),
        ),
        // Photos joined the day its modules landed (`photos-app.md` PH-1) — deliberately in the
        // same commit, because the Calendar/Contacts note above is the warning: an app missing
        // from this list is refused at the desktop socket exactly as hard as an untrusted one,
        // and the only symptom is a UI that spins forever on a session that can never open.
        FirstPartyApp(
            manifest = BundledAppManifestSource.PHOTOS_MANIFEST,
            clients = listOf(
                ClientBinding.AndroidPackage("app.tenshu.photos.android"),
                ClientBinding.MacCodeSignature("app.tenshu.photos.ios"),
            ),
        ),
        //
        // Maps/Timeline/Home gained their `MacCodeSignature` bindings on 2026-08-10, once
        // `packageDesktopApp` made a real-identity signature the default rather than something the
        // Control build alone did (docs/tech-debt.md "A locally rebuilt desktop bundle is ad-hoc
        // signed"). ⚠ The pinned value is the **code-signing Identifier** (`codesign -dv` →
        // `Identifier=`), which is *not* the Android package: `app.tenshu.maps.ios`, not
        // `app.tenshu.maps.android`.
        //
        // ⚠⚠ Every macOS bundle id is the app's **iOS** bundle id (`app.tenshu.<app>.ios`), in every
        // channel (`desktop-macos-distribution.md` D12 for Control and Drive, C4 for the other eight,
        // 2026-11-05). A Mac App Store copy must carry its iOS record's id, and a store copy and a
        // Developer ID copy with different ids are two different apps to macOS and to the IPC gate.
        // Never change one after a public desktop release: every install becomes another app.
    )

    /** `appId` → manifest. Backs [BundledAppManifestSource]. */
    val manifestsByAppId: Map<String, AppManifest> = manifestIndexOf(apps)

    /** `clientId` → `appId`. Backs [BundledAppIdentityResolver]. */
    val appIdByClientId: Map<String, String> = clientIndexOf(apps)
}

/**
 * Resolves the macOS bundle id LaunchServices can open for a Tenshu [appId].
 *
 * Internal app ids are platform-neutral. The catalogue is the source of truth for the Apple
 * code-signing identifier, including shared iOS/macOS identifiers used for Universal Purchase.
 * Unknown ids are retained when they are already plausible bundle ids so registered third-party
 * apps continue to use their own bundle identifiers.
 */
fun macOsBundleIdFor(appId: String): String? {
    val bundleId = when (appId) {
        // Control is the host rather than a row in BundledAppCatalogue, but its signed bundle
        // also shares the iOS App Store identifier.
        "app.tenshu.control" -> "app.tenshu.control.ios"
        else -> BundledAppCatalogue.apps
            .firstOrNull { it.appId == appId }
            ?.clients
            ?.filterIsInstance<ClientBinding.MacCodeSignature>()
            ?.singleOrNull()
            ?.identifier
            ?: appId
    }
    return bundleId.takeIf {
        it.isNotBlank() && it.contains('.') && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' }
    }
}

/**
 * Builds the `appId` → manifest index, rejecting a duplicated app.
 *
 * Extracted from [BundledAppCatalogue] so the guard is *testable*: a check that only ever runs
 * inside an object initialiser, against a list that is correct today, is asserted rather than
 * verified — and would quietly stop working without anyone noticing.
 */
internal fun manifestIndexOf(apps: List<FirstPartyApp>): Map<String, AppManifest> {
    val duplicates = apps.groupBy { it.appId }.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) {
        "Duplicate appId in the app catalogue: ${duplicates.joinToString()}"
    }
    return apps.associate { it.appId to it.manifest }
}

/**
 * Builds the `clientId` → `appId` index, rejecting two apps that claim one id.
 *
 * ⚠ Validated rather than merged. `associate` keeps the last writer, so a collision would
 * silently hand every one of an app's calls to a different app — the quietest possible way to
 * get identity wrong, and the exact failure class this track has been closing.
 */
internal fun clientIndexOf(apps: List<FirstPartyApp>): Map<String, String> {
    val pairs = apps.flatMap { app -> app.clients.map { it.clientId to app.appId } }
    val duplicates = pairs.groupBy { it.first }.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) {
        "Two apps in the catalogue declare the same clientId: ${duplicates.joinToString()}. " +
            "A client id identifies exactly one app."
    }
    return pairs.toMap()
}
