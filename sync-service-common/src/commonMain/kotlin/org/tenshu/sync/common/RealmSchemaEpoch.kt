package org.tenshu.sync.common

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * The realm's **schema epoch vector** (`core-schema-evolution.md` §7.1): for
 * every service that has ever written data in this realm, the highest schema
 * version any of the realm's devices has emitted under. One row per realm,
 * keyed on the constant [SINGLETON_KEY].
 *
 * ## ⚠ FROZEN FOREVER — normative, not decorative
 *
 * This composite's own wire shape must never change, for the same bootstrap
 * reason the announce envelope is frozen (§6.2/§7.1): **every version that will
 * ever fence on the epoch has to be able to read it.** A reshape here would
 * garble the very record a lagging peer needs in order to discover that it is
 * lagging — the fence would fail exactly in the case it exists for. Positional
 * composites make even an appended optional field peer-breaking, so additions
 * go in a NEW composite id, never as fields here.
 *
 * The shape is deliberately as small as a shape can be — a key and a byte
 * string — and the meaning lives in [versionsBytes]: the **serialized frozen
 * [VersionVector]**, byte-for-byte the same encoding the block stamp carries
 * (`BlockPayloadEnvelope`). Two payoffs: the vector has exactly
 * one definition across announce, stamp and epoch, and it stays extensible
 * *within* its own frozen contract (a new service is a list element, not a
 * field).
 *
 * ⚠ **Why a byte string and not a `composite(VERSIONS, type = VersionVector.Schema)`
 * field** (the shape §7.1 sketches): the local store persists a composite field's
 * value as its own row, and every element of a `TList` field must therefore carry
 * a `DataIdProvider` — `VersionVector.services` is a `TList<ServiceVersion>` and
 * `ServiceVersion` has none (it is a pure wire type; `MomentSource` is the repo's
 * precedent for a *storable* list member, content-addressed on purpose). The
 * alternatives are adding storage traits to a frozen wire type, or introducing a
 * storable twin of the vector — a second representation of the same concept, free
 * to drift. Carrying the frozen encoding verbatim avoids both.
 *
 * ## Why it is E2E, never relay-visible
 *
 * The vector rides an ordinary encrypted `SyncBlock` like any other row — the
 * relay never sees a schema version (House Rule #1). This is a security
 * property, not an accident: a relay-visible *per-service* vector would leak
 * **which apps the user runs** (a Fit entry reveals a fitness-app user). The
 * relay's one plaintext compatibility number is `RELAY_PROTOCOL_VERSION` — its
 * own wire contract, carrying no information about the user at all (§7.5/§7.8:
 * no payload format version is relay-visible, since even one would leak "this
 * user upgraded"). See `docs/sync/threat-model.md` A37 / T-SE-01.
 *
 * ## Bump + merge semantics
 *
 * - **Bumped on the first local write at a new version** (§7.2, not at install
 *   and not at announce), following *contained* types rather than the written
 *   entity's nominal owner (§4.1.3) — see `RealmSchemaEpochWriter`. Self-scoping
 *   falls out: a realm holding no Fit data never grows a Fit entry.
 * - **Merged per entry by MAX** (§12 open question 3) — a join-semilattice, so
 *   racing bumps from two devices converge regardless of arrival order. The
 *   merge lives in `RealmSchemaEpochMerger`, which is why the [Syncable] trait
 *   declares [ConflictStrategy.Merge] rather than last-writer-wins: LWW would
 *   let a device that bumped only service A erase another device's bump of
 *   service B.
 *
 * ## One row per realm, keyed by realm
 *
 * The personal realm's row keeps the bare [SINGLETON_KEY]; a **shared** realm's
 * row is keyed `"$ownerId::$SINGLETON_KEY"` — the same `"$realmId::"` DataId
 * prefix every other shared-realm entity carries, so the row routes to its own
 * realm's keys, queue and relay by the ordinary emit path with no special case
 * (`RealmRegistry.epochKeyFor` composes it; this module deliberately does not
 * know the separator). Each realm therefore has an epoch that describes *its*
 * data, and a device can be fenced on one realm while syncing another normally.
 *
 * ## If a client read is ever added
 *
 * There is deliberately no client extension or request object here — the epoch
 * is proxy-internal (the proxy writes it, the proxy fences on it). A reactive
 * read added for a UI surface must register its request object in the **client**
 * module and take a generic `Data` request (§6.10), or every select projecting
 * it dies as an opaque `error/internal`.
 *
 * ## Why this lives in `:sync-service-common`, not `:sync-service`
 *
 * Its one meaningful field is the frozen [VersionVector], which lives in
 * `:core-common` — and `:sync-service` depends only on `:core` +
 * `:identity-service`. Rather than widen that module's dependency surface, the
 * type sits beside [RekeyMigrationMarker] (the existing precedent for a
 * sync-domain composite declared in `-common`).
 */
interface RealmSchemaEpoch : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** Stable single-row key; always [SINGLETON_KEY]. */
    val epochId: TString
        get() = getField(Fields.EPOCH_ID)

    /**
     * The realm's [VersionVector] as serialized by [BinarySerializer] under its
     * frozen schema: highest emitted version per service, plus the core floor.
     * Absent services simply have no entry — "no device has ever written this
     * service's data in this realm" (§7.2's self-scoping). Decode with
     * [decodeVersions].
     */
    val versionsBytes: TByteArray
        get() = getField(Fields.VERSIONS_BYTES)

    companion object {
        const val ID = "tenshu/sync/realmSchemaEpoch"

        /** The realm's single epoch row's key. */
        const val SINGLETON_KEY = "realm-schema-epoch"

        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.EPOCH_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(
                DataIdProviderSchema,
                // Merge (not LWW): the vector is a join-semilattice merged per
                // entry by MAX — see the class KDoc's merge section.
                Syncable(SyncStrategy.Eager, ConflictStrategy.Merge),
            ),
            fields = {
                string(Fields.EPOCH_ID)
                byteArray(Fields.VERSIONS_BYTES)
            },
        )

        fun create(
            context: TContext,
            versions: VersionVector,
            epochId: String = SINGLETON_KEY,
        ): RealmSchemaEpoch = create(context, BinarySerializer().serialize(versions), epochId)

        /**
         * [epochId] is the row's key, and therefore *which realm's* epoch this is:
         * the bare [SINGLETON_KEY] for the personal realm, or a shared realm's
         * namespaced `"$ownerId::$SINGLETON_KEY"`. The namespacing rule itself lives
         * with realm routing (`RealmRegistry.epochKeyFor`) — this module deliberately
         * does not know the separator, it only carries whatever key it is handed.
         */
        fun create(
            context: TContext,
            versionsBytes: ByteArray,
            epochId: String = SINGLETON_KEY,
        ): RealmSchemaEpoch = context.create(
            type = ID,
            fields = listOf(
                epochId.toTString(context),
                versionsBytes.toTByteArray(context),
            ),
        )
    }

    object Fields {
        const val EPOCH_ID = "$ID/epochId"
        const val VERSIONS_BYTES = "$ID/versions"
    }
}

/**
 * Decode [RealmSchemaEpoch.versionsBytes] under the frozen `VersionVector`
 * schema. Throws if the bytes are malformed — which, for a frozen schema, means
 * a corrupted row rather than a version skew; callers that must survive it wrap
 * in `runCatching`.
 */
fun RealmSchemaEpoch.decodeVersions(parser: BinaryParser): VersionVector =
    parser.parse(versionsBytes.get())

/** [decodeVersions] over [context]'s own registry — the convenience most callers want. */
fun RealmSchemaEpoch.decodeVersions(context: TContext): VersionVector =
    decodeVersions(BinaryParser(context.schemaProvider(), context.dataFactory()))
