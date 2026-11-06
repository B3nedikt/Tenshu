package org.tenshu.core.common.schema.migration

/**
 * The store-open version rules, as pure functions every persistent store shares
 * (`core-schema-evolution.md` §8.9, E2j-3).
 *
 * ## Why this had to be extracted
 *
 * E2j-2 moved the JVM store off "any `TENSHU_STORAGE_FORMAT_VERSION` mismatch
 * wipes" and, with it, moved the **policy**: a service-owned reshape now bumps
 * that service's version and deliberately leaves the format version alone
 * (House Rule #9). ⚠⚠ That change is only safe for a store that *looks at* the
 * per-service versions. The iOS file store (retired 2026-11-05) and the JS
 * `IndexedDbObjectStore` gated on the format version alone — so after E2j-2 a
 * service reshape would have left them neither wiping **nor** migrating, reading
 * v-old rows with new code. Positional composites (§2 fact 1) make that silent
 * garbling: exactly the failure class the rule exists to prevent, re-opened on
 * two platforms by a change that made the third safer.
 *
 * So the rules live here, in one tested place, and each store's open path is a
 * thin call. A store that cannot migrate does not get a *weaker* gate — it gets
 * the same verdict and takes the wipe.
 */

/**
 * Whether a store recorded at core version [recorded] can still be opened
 * forward by this build.
 *
 * `:core`'s version is `TENSHU_STORAGE_FORMAT_VERSION` (the `VersionVector`'s
 * core slot), [floor] is its derived readable floor
 * ([StoreSchemaMigrations.coreReadableFloor]), and above [current] means the
 * store was written by newer code — a chain migrates forward, never backward,
 * so no step could read those rows.
 *
 * ⚠ `recorded == 0` (fresh store, or one that predates the format stamp) is
 * below every floor ≥ 1 and so reported unreachable; the caller's wipe is a
 * harmless no-op on an empty store and doubles as "stamp a new store".
 */
fun coreVersionReachable(recorded: Int, current: Int, floor: Int): Boolean =
    recorded in floor..current

/**
 * A human-readable reason for a core-version wipe, for the store's log line —
 * data loss is written down, never implied (§8.4). `null` when the core version
 * is reachable and there is nothing to report.
 *
 * ⚠ Only the **core** gate lives here. Per-service drift used to be judged
 * alongside it by a `serviceVersionDrift`/`storeMustWipe` pair, because the iOS
 * and JS stores had no migration seam and could only wipe. Both now run the real
 * [StoreSchemaMigrator] through [InMemoryStoreMigration], which decides
 * no-op / migrate / wipe per service and logs its own reasons — so that stopgap
 * was deleted rather than left as a second rule that could disagree with the
 * first. (Duplicated store rules are what let E2j-2 silently unmake the
 * invariant on the twins in the first place; see §8.9.)
 */
fun storeWipeReason(
    coreReachable: Boolean,
    recordedCore: Int,
    currentCore: Int,
    coreFloor: Int,
): String? =
    if (coreReachable) {
        null
    } else {
        "core recorded v$recordedCore is outside the readable range [$coreFloor, $currentCore]"
    }
