package org.tenshu.core.common

/**
 * `:core`'s schema version — its slot in the `VersionVector`, and the version every persistent
 * local store records for the core-owned row shapes (reserved rows, sync metadata, the store's own
 * bookkeeping) and the core operation set.
 *
 * ## 1 is the release baseline
 *
 * At the release cut (`docs/proposals/pre-release-checklist.md` §4) this constant, every
 * `service(id, version = …)` and [RELAY_PROTOCOL_VERSION] are 1, with no development migration
 * chain behind them. The retired pre-release numbering and the lessons each step left are in
 * `docs/proposals/core-schema-evolution.md` **Appendix A**. ⚠⚠ That series is closed: nothing
 * may compare against a pre-release number.
 *
 * ## How a bump behaves at store open (`core-schema-evolution.md` §8.9)
 *
 * Every persistent store (`SqliteObjectStore`, the JS IndexedDB store) records the core version it
 * was written under and judges it with `coreVersionReachable` (the iOS shared replica judges its
 * own, `ios-shared-store-sqlite.md` §5.2):
 *
 *  - **within `[floor, current]` → migrate.** The per-service chains run and the rows survive.
 *  - **below the floor or above current → wipe** and rehydrate from the relay.
 *
 * The floor is **derived** from registered `schemaMigration(CORE_SERVICE_ID, fromVersion = …)`
 * steps; with no core chain it equals this constant.
 *
 * ## Rules (House Rule #9)
 *
 *  - ⚠⚠ **Every bump of this constant registers a `schemaMigration(CORE_SERVICE_ID,
 *    fromVersion = N-1)` step** — empty when nothing reshaped. A missing step makes the floor equal
 *    the new version, and every store at N-1 is **wiped**. Post-release that wipe is data loss on
 *    every installed device (Appendix A records a pre-release bump that did exactly that).
 *    `ServiceMigrationChainsReachTheirVersionTest` asserts the chain reaches this constant.
 *  - ⚠⚠ **A service-owned reshape does NOT bump this constant.** It bumps that service's
 *    `version` and ships its `SchemaMigration`. Bumping core for a service change is pointless at
 *    best, and without a core step it wipes everything.
 *  - Bump this for a `:core`-owned row reshape, or as a **fence** when a new `:core` operation can
 *    ride nested inside a callstack. An older proxy then fails at decode, which only the announce's
 *    core slot can turn into a typed refusal (Appendix A).
 *  - **The relay is owed nothing.** Its 426 gate reads [RELAY_PROTOCOL_VERSION], never this
 *    constant (House Rule #1: it stores ciphertext). Read-side skew is fenced client-side by the
 *    realm epoch vector (§7.3).
 */
const val TENSHU_STORAGE_FORMAT_VERSION: Int = 1

/**
 * Logcat tag every Tenshu app emits its storage-format version under at startup, so the Android
 * smoke gate (and a maintainer, against a real device) can assert **schema lockstep** — that Control
 * and every installed client were built from the same schema (HR #9). A mismatch is the exact bug
 * that surfaces as a cryptic `IPC Client -> Failure … error/internal` on a peer-broken read (e.g. a
 * stale Control serving a newer client's `MovementTrace`); this makes it a loud, named failure at
 * launch instead. Kept beside the version so the two can't drift.
 */
const val SCHEMA_LOCKSTEP_LOG_TAG: String = "TenshuSchemaLockstep"

/**
 * The line each app logs under [SCHEMA_LOCKSTEP_LOG_TAG]; parsed by the smoke gate.
 *
 * Emitted by every first-party Android app (`:fit-android` depends on `:core-common` directly
 * for it); the gate treats a missing beacon as a warn, not a failure.
 */
fun schemaLockstepLine(packageName: String): String =
    "$packageName storageFormatVersion=$TENSHU_STORAGE_FORMAT_VERSION"
