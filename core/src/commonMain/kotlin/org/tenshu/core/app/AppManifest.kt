package org.tenshu.core.app

/**
 * Permission-class taxonomy derived from an operation's IDL traits
 * (`docs/proposals/tenshu-rei.md` §2.2, Q43). Determines how Control's
 * permission widget renders and gates a permission:
 *
 *  - [Ambient]  — a standing capability the app holds once granted (e.g.
 *    "read all your files"); the most powerful class and the one consent
 *    surfaces care most about.
 *  - [Picker]   — access mediated by a user pick (the app only sees what the
 *    user explicitly selects through a system picker). The **safe default**
 *    when an operation declares no capability-class trait.
 *  - [Scoped]   — access bounded to a named scope (e.g. a single folder); the
 *    widget shows the scope alongside the grant.
 *
 * v1 reads these from the bundled [AppManifest]; the source-time IDL scan
 * (KSP / compiler plugin) that would populate them automatically is a later
 * Rei phase (`tenshu-rei.md` §2.2).
 */
enum class CapabilityClass {
    Ambient,
    Picker,
    Scoped,
}

/**
 * Gesture / confirmation requirement a permission carries
 * (`docs/proposals/tenshu-rei.md` §2.2). Orthogonal to [CapabilityClass]:
 *
 *  - [None]                        — invocable without an interactive gesture.
 *  - [RequiresUserGesture]         — must be triggered by a user action (the
 *    **safe default** when a trait is absent).
 *  - [RequiresExplicitConfirmation] — needs an explicit confirm step beyond a
 *    mere gesture (destructive / high-impact operations).
 */
enum class GestureRequirement {
    None,
    RequiresUserGesture,
    RequiresExplicitConfirmation,
}

/**
 * One declared permission of an app: the `(serviceId, operationId)` it can
 * invoke, plus the capability class, gesture requirement, and a human-readable
 * label Control renders in the permission list.
 *
 * This is the *declared* permission set (app identity, realm-wide — see
 * `docs/proposals/control-app-registration.md` §2). The user's grant state
 * (granted / denied / ask-each-time) lives separately in [AppGrant] and is
 * joined to this descriptor at the UI layer.
 */
data class AppPermission(
    val serviceId: String,
    val operationId: String,
    val capabilityClass: CapabilityClass = CapabilityClass.Picker,
    val gesture: GestureRequirement = GestureRequirement.RequiresUserGesture,
    val humanLabel: String,
)

/**
 * The realm-wide *identity* of an app (`control-app-registration.md` §2): name,
 * icon, version, description and the full declared permission set. Ships with
 * the signed app binary / Rei publication, so it is identical on every device.
 *
 * Control's Apps list renders an app's identity joined with its per-device
 * [AppRegistration] rows and its [AppGrant] state; it does not store the
 * identity itself.
 */
data class AppManifest(
    val appId: String,
    val name: String,
    val iconName: String,
    val version: String,
    val shortDescription: String,
    val permissions: List<AppPermission>,
)

/**
 * The `ReiSource`-shaped read seam for app identity. v1's implementation is a
 * compiled-in [AppManifest] table for first-party apps (e.g. Drive); the
 * future Rei-ledger implementation is a drop-in replacement that reads signed
 * publications instead (`control-app-registration.md` §4 / §7).
 *
 * Returns `null` for an unknown `appId` (e.g. a dev-mode build with no bundled
 * manifest) — Control falls back to the synced [AppRegistration] fields
 * (appId, install date, verified badge) for such apps.
 */
interface AppManifestSource {
    fun manifest(appId: String): AppManifest?
}
