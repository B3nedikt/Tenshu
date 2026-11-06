package app.tenshu.control.data

import kotlinx.coroutines.flow.Flow
import org.tenshu.core.app.AppGrant
import org.tenshu.core.app.CapabilityClass

/**
 * Data client behind Control's **Apps** section
 * ([`control-ia.md`](../../../../../../../docs/proposals/control-ia.md) §4,
 * [`control-app-registration.md`](../../../../../../../docs/proposals/control-app-registration.md)).
 * Joins the three layers of app state into UI-shaped views:
 *
 *  - **identity** — the [org.tenshu.core.app.AppManifest] from [AppManifestSource]
 *    (name, icon, version, declared permission set);
 *  - **`AppRegistration`** — the per-`(appId, deviceId)` *"installed here"* record;
 *  - **`AppGrant`** — the realm-wide tri-state permission decisions.
 *
 * Reads are reactive `Flow`s over the synced relations; writes are non-suspend
 * at the call site only in the sense of the SDK `IoOperation` contract (the
 * methods here are `suspend` because they await the operation result). Mirrors
 * the `PublisherClient` / `SyncRepository` data-client pattern in this package.
 *
 * The interface is shaped so the future Rei-ledger-backed implementation drops
 * in unchanged — only [AppManifestSource] swaps from bundled to ledger-read.
 */
interface AppRegistry {

    /** Installed apps, grouped by `appId` across devices, newest install first. */
    fun installedApps(): Flow<List<InstalledApp>>

    /** Full detail for one app: its identity, every declared permission joined with its
     *  current grant state, and the distinct services it consumes. `null` if the app has no
     *  active registration in the realm. */
    fun appDetail(appId: String): Flow<AppDetail?>

    /** Write the user's tri-state decision for one capability. */
    suspend fun setGrant(appId: String, serviceId: String, operationId: String, grant: GrantState)

    /** Reset every grant for the app back to [GrantState.AskEachTime]. */
    suspend fun revokeAllPermissions(appId: String)

    /**
     * Which app opens each data type the user could meaningfully choose about
     * (`control-cross-app-open.md` TT-4b), reactive over the realm preference.
     *
     * ⚠⚠ Only types with **more than one candidate installed on this device** appear: a choice
     * between one option is not a choice, and offering one would invite the user to express a
     * preference that can never change anything.
     */
    fun openHandlerChoices(): Flow<List<OpenHandlerChoice>>

    /**
     * Set the user's primary handler for [type], or clear it back to the built-in order when
     * [appId] is `null`.
     *
     * ⚠ Realm-scoped: *"I prefer Timeline for places"* is a statement about the user, not about a
     * device. A device without the chosen app falls through to the next candidate rather than
     * failing (`control-cross-app-open.md` §5j.2).
     */
    suspend fun setPrimaryHandler(type: String, appId: String?)

    /** Deregister the app (tombstone + grant purge). [UninstallScope.ThisDevice] removes only
     *  the current device's registration; [UninstallScope.AllDevices] removes every device's. */
    suspend fun uninstall(appId: String, scope: UninstallScope)
}

/** One app as shown in the Apps list — identity joined with its registration rows. */
data class InstalledApp(
    val appId: String,
    val name: String,
    val iconName: String,
    val version: String,
    val shortDescription: String,
    /** Earliest `createdAt` across this app's device registrations (the install date). */
    val installedAt: Long,
    /** `false` if any registration was admitted under the dev-mode handshake downgrade. */
    val verified: Boolean,
    /** Number of distinct devices this app is registered on. */
    val deviceCount: Int,
    /**
     * Whether this app has a registration for **the device we are running on**
     * (`control-app-catalogue.md` D5).
     *
     * ⚠ Reads as *"has connected here at least once"*, not *"is installed here"* — registration
     * fires on session establishment, so an app installed but never opened has no row, and an
     * app uninstalled at the OS level keeps the row it already wrote. Surfaces should not
     * promise more than that.
     *
     * The tray therefore does not decide on it alone: it asks LaunchServices where the bundle
     * is and uses this only to vouch for a copy outside an Applications folder. Control's Apps
     * list keeps every app in the realm and uses this to say *where* it lives.
     */
    val installedOnThisDevice: Boolean = true,
    /**
     * Whether this Control has a **manifest** for [appId] (`control-app-catalogue.md` C3).
     *
     * `false` means we hold a registration for an app we cannot describe. [name] then falls back
     * to the raw [appId], which used to happen *silently* and read as a naming bug — the
     * incident that opened the catalogue proposal.
     *
     * ⚠ Distinct from [verified], deliberately. `verified` answers *who is this* (was the peer's
     * identity attested); this answers *what do we know about it*. Folding them into one badge
     * would make a security signal ambiguous.
     *
     * Two reachable causes, and the surface should not pretend to tell them apart: this Control
     * predates the app (§3a — the observed case), or it is a third-party app served from Rei
     * rather than the bundled table (§3b, once that exists).
     */
    val recognised: Boolean = true,
)

/**
 * One row of the *"opens by default"* picker: a data type, the apps here that can show it, and the
 * user's pick.
 *
 * ⚠⚠ [candidates] is in **effective order** — the resolver's order, chosen app first — so what the
 * list shows and what a tap would actually do cannot disagree. A picker rendering the built-in
 * order while the resolver used another would be a settings screen that lies.
 */
data class OpenHandlerChoice(
    /** The `DataId` type, e.g. `tenshu/place/place`. The UI resolves its own label. */
    val type: String,
    /** Installed here and able to show [type], best first. Always at least two. */
    val candidates: List<InstalledApp>,
    /**
     * The user's pick, or `null` for *"whatever the platform prefers"*.
     *
     * ⚠ Null is a real state, not a missing value: it means the built-in order applies, and it is
     * what clearing the preference returns to.
     */
    val chosenAppId: String?,
)

/** Per-app detail view: identity + permissions (with grant state) + consumed services. */
data class AppDetail(
    val app: InstalledApp,
    val permissions: List<PermissionRow>,
    /** Distinct `serviceId`s the app's permissions touch — cross-link targets to
     *  `/control/services/:serviceId`. */
    val services: List<String>,
)

/** One permission row in the App-detail permission list. */
data class PermissionRow(
    val serviceId: String,
    val operationId: String,
    val humanLabel: String,
    val capabilityClass: CapabilityClass,
    /** Non-null only for [CapabilityClass.Scoped] permissions (the "limited to …" affordance). */
    val scopeLabel: String?,
    val grant: GrantState,
    /** Epoch-millis of the last invocation, `0L` if never used. */
    val lastUsedAt: Long,
)

/** UI-facing tri-state grant, mapped to/from [AppGrant.Grant] wire strings. */
enum class GrantState(val wire: String) {
    Granted(AppGrant.Grant.GRANTED),
    Denied(AppGrant.Grant.DENIED),
    AskEachTime(AppGrant.Grant.ASK_EACH_TIME);

    companion object {
        fun fromWire(value: String?): GrantState =
            entries.firstOrNull { it.wire == value } ?: AskEachTime
    }
}

/** Scope of an uninstall action (`control-app-registration.md` §5.2). */
enum class UninstallScope { ThisDevice, AllDevices }
