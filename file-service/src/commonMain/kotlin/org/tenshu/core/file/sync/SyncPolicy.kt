package org.tenshu.core.file.sync

import org.tenshu.core.TConstant
import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.constant

/**
 * The per-device sync policy for a file: keep its bytes on this device, or leave
 * it cloud-resident and fetch lazily on open. v3 of the offline/online model
 * (`docs/proposals/drive-offline-online.md` §4).
 *
 * Lives in `:file-service` (not an app module) because it governs **file-byte
 * retention/materialisation** — the file domain, consumed by both Drive (UI) and
 * Control (the cross-device "what each device keeps" view, §4.6).
 *
 * This is deliberately a **binary effective rule**, not three values. The
 * proposal names the per-device *default*'s keep value `OFFLINE_ALL` (§4.2.1) to
 * connote "keep everything, but evict LRU under a storage budget" (§4.4), while
 * an *override*'s keep value is `KEEP_OFFLINE` (§4.2.2). At the model level both
 * mean the same thing — *keep the bytes* — so they collapse to [KEEP_OFFLINE]
 * here. The "…with eviction" nuance of an `OFFLINE_ALL` default is a property of
 * the **keeper**, not of the resolved policy, so it does not need a distinct
 * member — and as of 2026-08-03 that keeper property exists
 * ([FileSyncPreferences.MaxLocalBytes] + the coldest-first sweep,
 * `retention-quota-model.md` Face B). ⚠ The old "gated on background-processing
 * Phase 6" note here was stale: the keeper has been registered at proxy init and
 * dispatched by the `ScheduleDrainLoop` on all three platforms since the
 * `PlatformScheduler` seam landed.
 *
 * A first-class [TConstant] (mirrors `FileCategory`), not a plain Kotlin enum: a
 * policy is now a self-describing `Data` that rides the schema directly — the
 * synced `file.sync.defaultPolicy` preference value is the constant itself (no
 * codec), and Control reads it field-by-field. The member names ([KEEP_OFFLINE] /
 * [ONLINE_ONLY]) are preserved as SCREAMING_CASE objects so every `when (policy)`
 * / `SyncPolicy.KEEP_OFFLINE` reference in the pure resolvers and UI is unchanged.
 */
sealed interface SyncPolicy : TConstant {

    /** Keep this file's bytes on this device (proactively, once the keeper exists). */
    object KEEP_OFFLINE : SyncPolicy {
        override val schema: ConstantSchema = constant("${ID}/keepOffline", Schema)
    }

    /** Leave the bytes cloud-resident; fetch lazily on open (v1/v2 behaviour). */
    object ONLINE_ONLY : SyncPolicy {
        override val schema: ConstantSchema = constant("${ID}/onlineOnly", Schema)
    }

    companion object {
        const val ID = "tenshu/file/sync/policy"
        val Schema: ConstantSchema = constant(ID)

        /**
         * All members, in declaration order (replaces the former enum `entries`).
         * **`by lazy`** so it is not evaluated during the companion's own
         * `<clinit>`: each member object's `schema` reads `Companion.Schema`, so
         * forcing the objects eagerly here would touch a not-yet-published
         * `Companion` and NPE.
         */
        val entries: List<SyncPolicy> by lazy { listOf(KEEP_OFFLINE, ONLINE_ONLY) }
    }
}

/**
 * A sparse, folder-granular, **bidirectional** sync-policy override for one
 * device: "files under [pathPrefix] follow [policy] regardless of the device
 * default" (`docs/proposals/drive-offline-online.md` §4.2). The exception, not
 * the rule — ~99% of files just follow the device default, so only the
 * deviations are stored.
 *
 * [pathPrefix] is matched against a file path folder-granularly (see
 * [effectiveSyncPolicy]): the prefix `/Photos` covers `/Photos` itself and
 * everything beneath it, but **not** a sibling like `/PhotosArchive`. A per-file
 * pin is simply the finest-grained override — its [pathPrefix] is the exact file
 * path, which is the longest possible match for that file and so always wins.
 *
 * **The value form of an override — the seam.** This stays a plain, context-free
 * `data class` (its [policy] is a `SyncPolicy` constant *singleton*, so no
 * `TContext` is needed to construct one). The pure resolvers ([effectiveSyncPolicy]
 * / [withFolderPolicy]) and the Drive/Control UI operate on this form. The
 * *persisted* representation is the registered [PathSyncOverrideEntry] composite;
 * conversion between the two happens only at the storage seam
 * (`PathSyncOverrideSetAdapter` + `FileSyncSetting`). Keeping a value form here is
 * what lets the context-free pure/UI layer stay untouched by the schema change
 * (`docs/proposals/drive-offline-online.md` §4.10).
 */
data class PathSyncOverride(
    val pathPrefix: String,
    val policy: SyncPolicy,
)

/**
 * The resolved set of sync rules in effect on one device: its [deviceDefault]
 * plus the sparse [overrides]. Immutable and free of IO — built once from the
 * synced preferences (`FileSynchronisationSettings`) + any legacy pin set, then
 * queried per row via [effectivePolicy].
 */
data class SyncRuleSet(
    val deviceDefault: SyncPolicy,
    val overrides: List<PathSyncOverride>,
) {
    /** The effective [SyncPolicy] for the file at [path] on this device. */
    fun effectivePolicy(path: String): SyncPolicy =
        effectiveSyncPolicy(path, deviceDefault, overrides)

    /**
     * Folds a set of exact-path "keep offline" pins (the legacy v1/v2 device pin
     * list) into this rule set as [SyncPolicy.KEEP_OFFLINE] overrides keyed on the
     * exact path (`docs/proposals/drive-offline-online.md` §4.9). Because an exact
     * path is the longest possible prefix for that file, a pin always wins over any
     * coarser folder rule — matching "a per-file toggle is the finest-grained
     * override" (§4.2).
     */
    fun withPinnedPaths(pins: Set<String>): SyncRuleSet =
        if (pins.isEmpty()) this
        else copy(overrides = overrides + pins.map { PathSyncOverride(it, SyncPolicy.KEEP_OFFLINE) })

    companion object {
        /**
         * The migration-safe baseline (`docs/proposals/drive-offline-online.md`
         * §4.9): existing devices default to [SyncPolicy.ONLINE_ONLY] with no
         * overrides, preserving the v1/v2 "online-only unless pinned" behaviour
         * until a user (or a platform seed) flips the default.
         */
        val Default = SyncRuleSet(SyncPolicy.ONLINE_ONLY, emptyList())
    }
}

/**
 * Pure effective-rule resolver (`docs/proposals/drive-offline-online.md` §4.2):
 * the **longest matching [PathSyncOverride.pathPrefix]** for [path] wins; if none
 * matches, the [deviceDefault] applies. No IO — a function of the device's
 * default + override set, both already in memory, so it is resolvable on any
 * device for any other device (§4.6).
 *
 * Prefix matching is folder-granular with a path-segment boundary: a prefix `P`
 * matches a path `X` iff `X == P` or `X` starts with `P` + `/`. This makes
 * `/Photos` cover `/Photos/a.jpg` without spuriously catching `/PhotosArchive`.
 */
fun effectiveSyncPolicy(
    path: String,
    deviceDefault: SyncPolicy,
    overrides: List<PathSyncOverride>,
): SyncPolicy =
    overrides
        .filter { matchesPrefix(path, it.pathPrefix) }
        .maxByOrNull { it.pathPrefix.length }
        ?.policy
        ?: deviceDefault

private fun matchesPrefix(path: String, prefix: String): Boolean {
    if (path == prefix) return true
    val boundary = if (prefix.endsWith("/")) prefix else "$prefix/"
    return path.startsWith(boundary)
}

/**
 * Returns this override list with [pathPrefix] set to [policy], keeping the set
 * **sparse** (`docs/proposals/drive-offline-online.md` §4.2 — "store the
 * exception, not the rule"):
 *
 *  - any existing override for the exact [pathPrefix] is dropped first, then
 *  - a new `(pathPrefix, policy)` is appended **only if [policy] differs from
 *    [deviceDefault]** — when it equals the default, the prefix simply follows
 *    the default and no row is stored.
 *
 * This is the edit behind the folder `…`-menu override toggle (§4.10 Phase 2b).
 * It clears redundancy against the *device default*, not against coarser ancestor
 * overrides, so an explicit row equal to an ancestor's policy can remain — correct,
 * just not maximally minimal.
 */
fun List<PathSyncOverride>.withFolderPolicy(
    pathPrefix: String,
    policy: SyncPolicy,
    deviceDefault: SyncPolicy,
): List<PathSyncOverride> {
    val without = filterNot { it.pathPrefix == pathPrefix }
    return if (policy == deviceDefault) without else without + PathSyncOverride(pathPrefix, policy)
}
