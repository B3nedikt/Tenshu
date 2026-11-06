package org.tenshu.core.common.app

import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.schema.SessionAdmission

/**
 * A refused [SessionAdmission], distilled to the two things a surface actually needs: **which
 * binary the user must update**, and which services disagreed
 * (`core-schema-skew-surface.md` SK-1).
 *
 * ⚠⚠ **Plain Kotlin, deliberately not a composite.** This never crosses the wire — it is derived
 * client-side from the admission that already did. Making it a schema type would owe a version
 * bump and a migration (House Rule #9) to carry information the client already holds.
 *
 * ## Why the direction is the answer
 *
 * `AnnounceSessionImplementation` compares two vectors it holds *simultaneously*, so it never has
 * to know its own deployment topology to decide who is stale — and that is what makes this work
 * across platforms without a platform branch. Under iOS route-1 the app links its own proxy, so
 * both vectors come from one binary and can never disagree: this verdict is **inert on iOS
 * route-1 and load-bearing on Android/desktop**, which is exactly right.
 */
data class SchemaSkew(
    /** The announcing app, as passed to [SchemaAnnouncer]. */
    val appId: String,
    val direction: Direction,
    /** The disagreeing service ids, sorted. Diagnostic detail — never the headline (§4). */
    val serviceIds: List<String>,
) {
    enum class Direction {
        /**
         * `clientRequires > proxyCurrent` — the client needs a newer proxy. **Update Control.**
         * ⚠ On Android/desktop this is the case where the app the user is looking at is
         * innocent, and telling them to update *it* is a dead end.
         */
        PROXY_BEHIND,

        /** `clientCurrent < proxyFloor` — the client is too old for the proxy. **Update this app.** */
        CLIENT_BEHIND,

        /**
         * Both, across different services — the two binaries are from genuinely divergent
         * builds, not one lagging release wave.
         *
         * ⚠ Rare but reachable, and the reason this enum is not a `Boolean`: collapsing it
         * either way names one app and leaves the user stuck after they obey the banner.
         */
        BOTH,
    }
}

/**
 * Distil a [SessionAdmission] into a [SchemaSkew], or `null` when it was admitted.
 *
 * The direction is the proxy's own verdict, [SessionAdmission.updateProxy] /
 * [SessionAdmission.updateClient], read rather than re-derived: since the 2026-10-28 reshape the
 * proxy judges each direction on a different client number (`requires` vs `current`,
 * `core-version-compatibility.md` §4.7), and a client re-deriving from the mismatch rows would be a
 * second copy of that rule to drift.
 *
 * ⚠ A refusal that sets *neither* flag cannot occur — `admitted` is defined as neither — but is
 * folded into [SchemaSkew.Direction.BOTH] rather than dropped, so a refused session can never
 * render as an admitted-looking `null`.
 */
fun SessionAdmission.toSchemaSkew(appId: String): SchemaSkew? {
    if (admitted.get()) return null
    val proxyBehind = updateProxy.get()
    val clientBehind = updateClient.get()
    val direction = when {
        proxyBehind && !clientBehind -> SchemaSkew.Direction.PROXY_BEHIND
        clientBehind && !proxyBehind -> SchemaSkew.Direction.CLIENT_BEHIND
        else -> SchemaSkew.Direction.BOTH
    }
    return SchemaSkew(
        appId = appId,
        direction = direction,
        serviceIds = mismatches.toList().map { it.serviceId.get() }.sorted(),
    )
}
