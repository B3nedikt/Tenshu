package org.tenshu.sync.proxy

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.schema.toMap
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.sync.SchemaFenceStatus
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.RealmSchemaEpoch
import org.tenshu.sync.common.decodeVersions

/**
 * The **realm schema pull fence** (`core-schema-evolution.md` §7.3, E2d-2).
 *
 * Evaluated once per [SyncScheduler] tick, immediately before the personal realm's
 * pull. A service **fences** iff the realm's [RealmSchemaEpoch] entry for it exceeds
 * this device's declared version for that service — meaning some device in the realm
 * has already emitted blocks this device's positional decoders would garble
 * mid-parse rather than error on (§2's ground fact). The verdict is published as the
 * single-row [SchemaFenceStatus] relation, the typed signal the "update to keep
 * syncing" banners read, and handed to [onVerdictChanged] for Control's own surfaces
 * (`core-schema-skew-surface.md` §8.8).
 *
 * ## Pull-fenced, push-allowed — and the cursor stands still
 *
 * A fenced tick **does not fetch** (rather than fetch-and-discard: cheaper, and a
 * pull that never runs cannot half-apply a batch), and therefore never advances
 * `SyncManager.lastSyncTimestamp` — a fenced device must not look "caught up", or
 * the blocks it skipped would be lost for good once it updates. Pushes are
 * deliberately untouched: new code reads old formats (the §3 asymmetric contract) so
 * an upgraded peer upcasts this device's old blocks like any other history (§8.2),
 * which is what makes push-allow strand nothing. Local reads keep working too — the
 * device is read-only-stale, not bricked.
 *
 * ## What it deliberately does NOT fence on
 *
 *  - **A service this device does not know.** An epoch entry for, say, Fit on a
 *    device without the Fit module cannot garble anything here: nothing can
 *    materialize a type this runtime has no schema for, and blocks for unknown types
 *    are already dropped as unparseable by the ordinary ingest path. Fencing on it
 *    would stop *all* sync — including the personal-realm rows this device does
 *    care about — over data it was never going to read. Only services present in
 *    this device's [currentVersionVector] can fence.
 *
 * ## ⚠ Core version DOES fence (§7.11)
 *
 * The relay's 426 gate cannot cover core skew: it reads `RELAY_PROTOCOL_VERSION` only
 * and never sees payload/core versions (§7.8). Without this fence a device behind on
 * `TENSHU_STORAGE_FORMAT_VERSION` is fenced by **nothing**, on either side: it is
 * admitted, syncs "successfully", and silently drops every block
 * (`decryptAndParse → null`).
 *
 * So `coreVersion` is judged exactly like a service entry: recorded above this
 * device's [TENSHU_STORAGE_FORMAT_VERSION] ⇒ fenced. Core is deliberately **coarse** —
 * it is the global store/wire floor, so a rise means the shared primitives moved and
 * the hazard is not attributable to any one service. That coarseness is the point:
 * core is precisely the residue the per-service vector cannot describe.
 *
 * It rides the published signal under the reserved
 * [SchemaFenceStatus.CORE_SERVICE_ID], which is not a real service id — see there.
 *
 * ⚠ **This cannot rescue a device that is already stale.** The fence lives in the
 * *reader*, so it protects every build that has it; a build without the reader-side
 * fence has no fence at all and nothing on the new side can give it one. For
 * those, reinstall is the only remedy — see §7.11.
 *
 * ## Per realm, independently
 *
 * Every realm has its own epoch row (keyed by realm — `RealmRegistry.epochKeyFor`),
 * so every realm is fenced on **its own** data: the personal pull gates on the
 * personal epoch, and each shared realm's pull gates on that realm's. A device can
 * therefore be fenced on one shared folder — whose owner ships an app this device
 * lacks — while its personal realm and every other shared folder keep syncing
 * normally. Fencing a shared realm on *another* realm's epoch would be a false
 * positive halting an invited member's collaboration for reasons outside it; not
 * fencing it at all would be the converse gap.
 *
 * The published [SchemaFenceStatus] is deliberately the **aggregate** over all
 * realms — see [publish].
 */
internal class SchemaPullFence(
    private val executionContext: ExecutionContext,
    /**
     * The shared realms to evaluate alongside the personal one, by `ownerId`. Read
     * once per [evaluate] (a realm can be registered mid-session). `null`/empty —
     * direct-construction tests, single-realm wirings — evaluates the personal realm
     * only.
     */
    private val sharedRealmIds: () -> List<String> = { emptyList() },
    /**
     * Told the aggregate verdict (sorted fenced service ids, empty when clear) each time it
     * **changes** — the same once-per-transition cadence as [logTransition]. Production forwards
     * it to [SyncHealthHolder.onSchemaFence], which is how Control's own surfaces (tray, OS
     * notification, desktop log) learn of an engagement — a `println` alone reaches nowhere in a
     * packaged desktop app.
     */
    private val onVerdictChanged: (List<String>) -> Unit = {},
) {

    /** One evaluation's outcome. [fencedServiceIds] is sorted and empty iff not fenced. */
    internal data class Verdict(val fencedServiceIds: List<String>) {
        val fenced: Boolean get() = fencedServiceIds.isNotEmpty()
    }

    /**
     * This device's declared per-service versions. The registered schema set is
     * fixed for the process, so it is walked once — the tick path must stay cheap.
     */
    private val declaredVersions: Map<String, Int> by lazy {
        executionContext.schemaProvider
            .currentVersionVector(executionContext.context)
            .toMap()
    }

    private val parser: BinaryParser by lazy {
        BinaryParser(
            executionContext.context.schemaProvider(),
            executionContext.context.dataFactory(),
        )
    }

    /** realmId (`null` = personal) → that realm's epoch-row DataId. Tick-thread confined. */
    private val epochDataIds = HashMap<String?, DataId.StringDataId>()

    private fun epochDataId(realmId: String?): DataId.StringDataId =
        epochDataIds.getOrPut(realmId) {
            DataId.StringDataId.create(
                context = executionContext.context,
                type = RealmSchemaEpoch.ID,
                keyValue = RealmRegistry.epochKeyFor(realmId),
            )
        }

    private val statusRef: ArrayDatabaseReference by lazy {
        ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = TList.ID,
                relationId = SyncService.Relations.SCHEMA_FENCE,
            ),
            isEntity = false,
            elementSchemaId = SchemaFenceStatus.ID,
        )
    }

    private val statusDataId: DataId.StringDataId by lazy {
        DataId.StringDataId.create(
            context = executionContext.context,
            type = SchemaFenceStatus.ID,
            keyValue = SchemaFenceStatus.SINGLETON_KEY,
        )
    }

    /** Last published verdict; `null` until the first evaluation. Tick-thread confined. */
    private var published: Verdict? = null

    /**
     * How many times the status row has actually been upserted — the observable half
     * of the write-on-change guard (and a cheap diagnosis handle: a number that tracks
     * the tick count means the guard has regressed). Tick-thread confined.
     */
    internal var statusWrites: Int = 0
        private set

    /**
     * Evaluate **every** realm's fence — the personal one and each registered shared
     * realm — and publish the aggregate [SchemaFenceStatus] if it changed. Returns
     * `true` when the caller must skip the **personal** pull; each shared realm's
     * verdict is then read back by [isFenced] within the same tick.
     *
     * Evaluated together, and in this order, on purpose: the published signal must
     * describe the device as a whole (a device fenced only on a shared realm still
     * needs the "update this device" banner — its shared folder has silently stopped
     * updating), and the shared pull runs later in the same tick, so caching here
     * costs one pass instead of re-reading every epoch row per realm.
     */
    fun evaluate(): Boolean {
        val personal = verdict(realmId = null)
        val shared = sharedRealmIds().associateWith { verdict(realmId = it) }
        sharedVerdicts = shared
        publish(personal, shared)
        return personal.fenced
    }

    /**
     * Whether the shared realm [realmId] is fenced, per the verdicts [evaluate]
     * computed this tick. `false` for a realm that was not registered when
     * [evaluate] ran — a realm registered mid-tick simply syncs unfenced until the
     * next one, which is the same freshness every other per-tick gate has.
     */
    fun isFenced(realmId: String): Boolean = sharedVerdicts[realmId]?.fenced == true

    /** Shared-realm verdicts from the last [evaluate]. Tick-thread confined. */
    private var sharedVerdicts: Map<String, Verdict> = emptyMap()

    private fun verdict(realmId: String?): Verdict {
        // No epoch row ⇒ no device in this realm has ever written at a version worth
        // recording (§7.2's self-scoping) ⇒ nothing to fence on.
        val epoch = executionContext.database.getData(epochDataId(realmId)) as? RealmSchemaEpoch
            ?: return Verdict(emptyList())
        // Unreadable bytes mean a corrupted row, not a skew (the shape is frozen) —
        // treated as absent, exactly as `RealmSchemaEpochWriter` does, so a corrupt
        // row can neither fence the device nor block its own overwrite.
        val recorded: VersionVector = runCatching { epoch.decodeVersions(parser) }.getOrNull()
            ?: return Verdict(emptyList())

        val offending = recorded.toMap().mapNotNull { (serviceId, epochVersion) ->
            // Unknown service ⇒ not fencing (see the class KDoc): absent from this
            // device's vector means no schema here can materialize its types.
            val declared = declaredVersions[serviceId] ?: return@mapNotNull null
            serviceId.takeIf { epochVersion > declared }
        }.toMutableList()

        // Core skew fences too (§7.11) — the same rule as a service entry, against the
        // global store/wire floor. Nothing else refuses it any more: E2e removed payload
        // versions from the relay, and the 426 gate this once deferred to is only ever
        // reached at realm genesis anyway.
        if (recorded.coreVersion.get().toInt() > TENSHU_STORAGE_FORMAT_VERSION) {
            offending += SchemaFenceStatus.CORE_SERVICE_ID
        }
        return Verdict(offending.sorted())
    }

    /**
     * Upsert the single status row — **only on a verdict change**. The row is not
     * `Syncable` so a rewrite emits no block, but a per-tick rewrite would still be
     * pointless store churn (and, at the hot 1.5 s cadence, a pointless stream
     * notification to every client observing it).
     *
     * **One row, aggregated across realms.** The signal answers "does this device need
     * updating", which is a property of the *device*, not of a realm — and the banner
     * it drives says exactly that. Per-realm detail (which folder is stale) would be a
     * genuinely different signal and, per the §7.7 rule, belongs in a **new composite
     * id**: `SchemaFenceStatus` is consumed by version-skewed peers over IPC, so its
     * positional shape can never gain a field.
     */
    private fun publish(personal: Verdict, shared: Map<String, Verdict>) {
        val verdict = Verdict(
            (personal.fencedServiceIds + shared.values.flatMap { it.fencedServiceIds })
                .distinct()
                .sorted()
        )
        if (verdict == published) return
        val previous = published
        published = verdict
        statusWrites++
        executionContext.database.transaction {
            executionContext.database.removeFromArray(statusRef, statusDataId)
            executionContext.database.addToArray(
                statusRef,
                SchemaFenceStatus.create(
                    context = executionContext.context,
                    fenced = verdict.fenced,
                    fencedServiceIds = verdict.fencedServiceIds,
                ),
            )
        }
        logTransition(previous, verdict)
        onVerdictChanged(verdict.fencedServiceIds)
    }

    /**
     * One line per **transition**, never per cycle — the [DrainFailureLog] discipline
     * (and the drain-spam collapse of `core-logging-facade.md` Phase 0): at the hot
     * cadence a per-tick line would be ~40 lines a minute for the whole time a device
     * is behind, which is precisely the state that lasts until the user updates.
     * Interim `println` sink, like its neighbours; moves behind `Log.warn` when the
     * facade lands.
     */
    private fun logTransition(previous: Verdict?, verdict: Verdict) {
        when {
            verdict.fenced -> println(
                "SyncScheduler -> schema pull fence ENGAGED: this device is behind a realm " +
                    "epoch for ${verdict.fencedServiceIds.joinToString(", ")}. Pull is paused " +
                    "on the affected realm(s) (pushes continue); update this device to resume."
            )
            previous?.fenced == true -> println(
                "SyncScheduler -> schema pull fence CLEARED: this device is current with every " +
                    "realm epoch; resuming pulls."
            )
        }
    }
}
