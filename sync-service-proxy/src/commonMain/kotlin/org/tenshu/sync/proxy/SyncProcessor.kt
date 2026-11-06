package org.tenshu.sync.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.schema.migration.PayloadUpcaster
import org.tenshu.core.common.util.HLCManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.externalPropertyWriter
import org.tenshu.core.common.util.compare
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.DataId.StringDataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.Syncable
import org.tenshu.identity.Device
import org.tenshu.identity.EntitlementSignature
import org.tenshu.identity.EntitlementUnverifiedError
import org.tenshu.core.collection.list.toList
import org.tenshu.sync.DeltaBatchPayload
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.FileContentManifestPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.RetiredRealmKeys
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.SubtreeReapOrdering
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncMetadata

/**
 * Pseudo-operation id for errors raised during *ingest*, which has no invoking operation.
 * ⚠ Named rather than blank so the error stream stays greppable by origin — a blank id would
 * collapse ingest failures in with everything else that has no operation.
 */
private const val ENTITLEMENT_VERIFY_OPERATION_ID = "tenshu/sync/ingest/entitlementVerify"

/**
 * D-c skew allowance (`sync-envelope-authentication.md` §3.5): a retired-key block is accepted when
 * its HLC physical time is below `retiredAt` plus this. Covers writes queued by a member's clock
 * slightly ahead of the rotating device's, at the cost of five minutes of backdating headroom.
 */
internal const val RETIRED_KEY_SKEW_MS: Long = 5 * 60 * 1000L

/**
 * Component responsible for decrypting, parsing, and saving SyncBlocks to the local database.
 *
 * Tombstones (DeleteBlock) take a separate ingest path via [processDeleteBlock]. Entities that
 * have been tombstoned at HLC ≥ an incoming SyncBlock's HLC are short-circuited in
 * [resolveConflictAndSave] before the merger runs.
 */
class SyncProcessor(
    private val executionContext: ExecutionContext,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val mergerRegistry: SemanticMergerRegistry = SemanticMergerRegistry(),
    /**
     * EI-1: the ONE resolver the whole sync layer names entities with.
     *
     * ⚠⚠ Injected rather than built here, because two independently-built resolvers gave one entity
     * two names: this one saw `realmRegistry` and `SyncProcessor`'s did not, so a shared-realm
     * entity was named under the shared key by the emit and the personal key by the ingest.
     */
    /**
     * The ONE resolver the whole sync layer names entities with.
     *
     * ⚠⚠ **Required, with no default.** A nullable one lets a caller omit it and get a realm-BLIND
     * rebuild — which names a shared-realm entity under the personal key while the emit side uses
     * the shared one. The registry below provably closes over a resolver the caller supplied.
     */
    private val wireIdResolver: WireIdResolver,
    private val tombstoneRegistry: TombstoneRegistry = TombstoneRegistry(
        executionContext,
        // ⚠⚠ The default registry MUST name entities the way this processor does. Building one
        // without a naming is what let an index and its reader disagree, and the failure was
        // resurrection rather than an assertion.
        // ⚠⚠ ONE resolver, captured — not `{ WireIdResolver(...).ofOrNull(it) }`, which built a new
        // one per call and so paid an HKDF for every indexed row. That is the object store's hot
        // path: it was slow enough to take the test JVM down.
        wireIdFor = wireIdResolver::ofOrNull,
    ),
    /**
     * Two-plane file-content read path (`docs/adr/0017-file-content-two-plane-channel.md`
     * P2). Null in legacy/test wirings without a content store — a
     * received [FileContentManifestPayload] then stays a clean no-op
     * (the documented P1→P2 gap; the legacy single-block path, where
     * still wired, is unaffected). Non-null wirings apply the manifest
     * **only** on the suspend OnDemand path ([processSyncBlockAsync]);
     * the eager non-suspend drain ([processSyncBlock]) never fetches
     * chunk bytes — that is what keeps selective-fetch intact.
     */
    private val manifestApplier: FileContentManifestApplier? = null,
    /**
     * SR-M.1b-pull (`sync-multi-realm-runtime.md`): when non-null, this processor
     * decrypts inbound blocks under a *fixed* realm's symmetric keys instead of
     * the device's personal realm (`syncManager.currentRealmContext()`).
     * [RealmRegistry] builds one such processor per shared realm so each realm's
     * ingest is structurally isolated — there is no per-block key selection in the
     * crypto hot path; a processor only ever holds one realm's key. `null` (the
     * default) = the personal processor, byte-identical to the single-realm world.
     */
    private val realmKeys: SyncCryptoManager.DerivedKeys? = null,
    /**
     * GR-7 retained keyring (`shared-realm-membership-grant.md` §13.4): this shared
     * realm's *retired* keys, each with its retirement time. After a member-removal
     * rotation, blocks written under the old `Kr` stay encrypted under it (ADR-0017,
     * no bulk re-encryption), so [decryptAndParse] tries [realmKeys] first and falls
     * back across these on AEAD failure. Empty for the personal realm (never rotates)
     * and a freshly-registered shared realm; the per-realm processor factory supplies
     * `RealmContext.retiredKeys`.
     *
     * ⭐ D-c (`sync-envelope-authentication.md` §3.5): a block that opens only under a retired
     * key is accepted only if its HLC predates that key's retirement (+[RETIRED_KEY_SKEW_MS]) —
     * otherwise a removed member would keep authoring power for ever.
     */
    private val realmRetiredKeys: List<RetiredRealmKeys> = emptyList(),
    /**
     * SR-M.1b-pull: the HLC this processor advances on every ingested block. A
     * shared realm keeps its own clock so a shared-realm block never bumps the
     * personal HLC (which would leak shared-realm activity into the personal
     * realm's tip). `null` (the default) ⇒ the personal `syncManager.hlcManager`.
     */
    private val realmHlcManager: HLCManager? = null,
    /**
     * Puts back content whose blobs the relay has freed, when this device still has the plaintext
     * (`drive-upload-stop-and-reclamation.md` §4.18). `null` — the default — leaves a dangling
     * reference as it is: the file does not surface and the resolve retries.
     */
    private val contentRepair: ContentRepair? = null,

) {

    /**
     * The tombstones and reap points this processor records, read by `SliceSnapshotReader` so a slice
     * never recreates a row the drain already deleted (`sync-device-catch-up.md` §11.4.4).
     */
    internal val deletionPoints: SubtreeReapOrdering get() = tombstoneRegistry

    /**
     * EI-1: an entity's relay-visible name. ⚠⚠ Every row keyed here is compared against a value
     * that arrived from the relay — `SyncMetadata`'s HLC, `LocalWriteMetadata`'s protection, a
     * tombstone's target — so all of them use the wire name. Keying some locally and some on the
     * wire is the bug class this seam removes: the two never match, and nothing says so.
     */
    private val wireIds = wireIdResolver
    private val parser = BinaryParser(
        executionContext.schemaProvider,
        executionContext.context.dataFactory()
    )

    /**
     * E2c history upcast (`core-schema-evolution.md` §8.2): built lazily, and
     * ONLY when the registered module graph carries migration chains — with
     * none registered the stamp is never even parsed and ingest is byte-for-byte
     * the pre-E2c path. ⚠ Production graphs do register chains (core, sync,
     * calendar, health, mia), so that branch is the exception. Requires the
     * schema provider to be a [SchemaRegistry] (every production wiring;
     * chains ride module registration, so a bare provider cannot have any).
     *
     * deviceId for [org.tenshu.core.common.schema.migration.MigrationContext]:
     * the realm context's device id, read per migration invocation — the
     * honest source this processor can reach (`SyncManager.currentRealmContext`;
     * a shared-realm processor shares the device identity). Empty only before
     * the realm is enabled, when nothing decrypts anyway.
     */
    private val payloadUpcaster: PayloadUpcaster? by lazy {
        val schemas = executionContext.schemaProvider as? SchemaRegistry ?: return@lazy null
        if (schemas.schemaMigrations().isEmpty()) return@lazy null
        PayloadUpcaster(
            live = schemas,
            context = executionContext.context,
            deviceIdProvider = { syncManager.currentRealmContext()?.deviceId ?: "" },
        )
    }

    /** The HLC this processor advances — the realm's own clock, else the personal one. */
    private fun hlcManager(): HLCManager = realmHlcManager ?: syncManager.hlcManager

    /**
     * Roster "last seen" ([DeviceLastSeenRecorder]) — fed from the same block HLCs the clock is
     * advanced with, so it costs no wire field and no extra read.
     */
    private val lastSeenRecorder = DeviceLastSeenRecorder(
        executionContext = executionContext,
        ownDeviceId = { syncManager.currentRealmContext()?.deviceId ?: "" },
    )

    /**
     * Advance the local clock for an ingested block **and** record that we heard from its author.
     * The two always happen together — every path that accepts a foreign HLC is by definition a
     * sighting of the device that minted it — so pairing them here keeps a future ingest path from
     * silently omitting the sighting.
     */
    private fun observe(hlc: HybridLogicalClock) {
        hlcManager().update(hlc)
        lastSeenRecorder.record(hlc)
    }

    /**
     * The current HLC of this processor's realm — read without advancing. Used by
     * [SyncScheduler.pullSharedRealms] to build the signed tip a shared realm's
     * `getChanges` poll piggybacks (the shared realm's clock, not the personal one).
     */
    fun currentRealmHlc(): HybridLogicalClock = hlcManager().current()

    /**
     * Polymorphic ingest entry point. Routes [SyncBlock]s through the existing merge
     * pipeline and [DeleteBlock]s through the tombstone path.
     */
    fun process(block: Composite) {
        when (block) {
            is DeleteBlock -> processDeleteBlock(block)
            is SyncBlock -> processSyncBlock(block)
            else -> error("SyncProcessor.process: unknown block kind ${block.schema.id.get()}")
        }
    }

    /**
     * Ingest a tombstone: record it in the registry and, if the entity is locally known,
     * delete it from the database. Subsequent SyncBlocks at HLC < the tombstone's HLC will
     * be short-circuited in [resolveConflictAndSave]; SyncBlocks at HLC > the tombstone's
     * HLC are accepted and treat the entity as recreated (standard CRDT tombstone semantics).
     */
    fun processDeleteBlock(block: DeleteBlock) {
        // ⭐⭐ FIRST, before anything reads the block (`sync-envelope-authentication.md` §3.4): a forged
        // tombstone must move neither the clock (`observe`) nor the realm epoch (`record` counts Device
        // tombstones), and must delete nothing. Tombstones travel only on the personal stream, so they
        // verify under exactly one key — there is no retired keyring to trial (§3.6).
        val identityKey = (realmKeys ?: syncManager.currentRealmContext()?.derivedKeys)?.sharedIdentityKey
        if (identityKey == null || !EnvelopeAuth.verifyTombstone(block, identityKey)) {
            EnvelopeRejections.record(EnvelopeRejections.Kind.TOMBSTONE_TAG, block.blockId.get())
            return
        }
        applyVerifiedDeleteBlock(block)
    }

    private fun applyVerifiedDeleteBlock(block: DeleteBlock): Unit = executionContext.database.transaction {
        observe(block.hlc)
        tombstoneRegistry.record(block)

        val idHash = block.targetEntityIdHash.get()
        val dataId = tombstoneRegistry.resolveDataId(idHash) ?: return@transaction

        // Order-independence (`sync-lane-cursor-split.md` L1a). The block path already
        // handles "tombstone first, newer recreate second" — [resolveConflictAndSave]'s
        // short-circuit explicitly lets `hlc > tombstone.hlc` fall through as a recreate.
        // This is the missing mirror: "recreate first, older tombstone second". Without it
        // a delete applied out of order silently destroys a *newer* entity.
        //
        // ⚠ Why it matters: the two inbound lanes are pulled and applied separately —
        // every block in a tick is applied before any tombstone in that tick, whatever the
        // relay_seq order — and with per-lane cursors (L1b) the lanes may drift arbitrarily
        // far apart. Ordering can therefore never be relied on; the guard has to be here.
        //
        // ⚠⚠ The comparison is against the entity's last write of ANY origin, which takes two
        // records. [SyncMetadata] covers only the last *ingested* write — reading it alone is
        // the "ghost file" data loss: a local write after the last ingest is invisible there,
        // so a tombstone newer than that stale HLC deletes a strictly newer row.
        //
        // ⚠ Two shapes, one comparison. The device pulls its own pushed tombstone back from the
        // relay and applies it here (the block's own `hlc.deviceId` shows it), which is what
        // makes a single device destroy a folder it just recreated. That echo is not filtered, deliberately: a
        // device that wipes its store rehydrates from its own blocks, and dropping its own
        // tombstones on that path would resurrect everything it ever deleted. The HLC guard is
        // the right instrument, and it also covers the case with no echo at all — peer B's
        // delete racing device A's local recreate.
        //
        // ⚠ Absence still means "unprotected", correctly: an entity this device has neither
        // ingested nor written since [LocalWriteMetadata] existed has no HLC to compare, and a
        // tombstone for it deletes.
        val idHashString = wireIds.of(dataId)
        val metadataId = StringDataId.create(
            executionContext.context,
            SyncMetadata.ID,
            idHashString
        )
        val ingestedHlc = (executionContext.database.getData(metadataId) as? SyncMetadata)?.hlc
        val localHlc = tombstoneRegistry.localWriteHlc(idHashString)
        val existingHlc = when {
            ingestedHlc == null -> localHlc
            localHlc == null -> ingestedHlc
            else -> if (localHlc.compare(ingestedHlc) > 0) localHlc else ingestedHlc
        }
        if (existingHlc != null && existingHlc.compare(block.hlc) > 0) return@transaction

        executionContext.deleteData(dataId, origin = WriteOrigin.SYNC_INGEST)
    }

    /**
     * Decrypt + parse [block]'s payload. Both the entity/property
     * variant ([org.tenshu.sync.DataBlockPayload]) and the
     * file-content-manifest variant ([FileContentManifestPayload])
     * decrypt under the shared identity key and parse from the same
     * serialized bytes — the concrete composite type is recovered from
     * the wire tag (P1 made the manifest a *distinct* type so the
     * `as?` casts at the call sites discriminate them). Returns the
     * parsed payload `Data`, or `null` if keys are missing / decrypt
     * or parse fails (the caller then drops the block, as before).
     *
     * Also advances local HLC state — kept here so both the sync and
     * async ingest paths update the clock exactly once per block.
     */
    private fun decryptAndParse(block: SyncBlock): Data? {
        // ⭐⭐ Open FIRST, observe after (`sync-envelope-authentication.md` §3.4). The envelope — and
        // so `block.hlc` — is authenticated only by a successful bound open; observing before it let a
        // relay drag every device's clock with a block that never decrypted.
        val opened = openBoundPayload(block) ?: return null
        observe(block.hlc)
        return opened
    }

    /**
     * [block]'s payload, opened with its envelope bound and parsed — or `null` (dropped). Trial order:
     * the current identity key, then each retired one; a retired-key open is accepted only under the
     * D-c rule. A wrong-key candidate that yields garbage is rejected at the stamp or parse and the
     * next is tried, exactly as before.
     */
    private fun openBoundPayload(block: SyncBlock): Data? {
        if (block.encryptedPayload.get().isEmpty()) return null // body-dropped by the relay: not a forgery
        val keys = realmKeys ?: (syncManager.currentRealmContext()?.derivedKeys ?: return null)
        var anyOpened = false
        fun tryKey(identityKey: ByteArray): Data? {
            val plain = EnvelopeAuth.openSyncBlock(cryptoManager, block, identityKey) ?: return null
            anyOpened = true
            return openAndParse(plain)
        }
        tryKey(keys.sharedIdentityKey)?.let { return it }
        for (retired in realmRetiredKeys) {
            val parsed = tryKey(retired.keys.sharedIdentityKey) ?: continue
            val cutoff = retired.retiredAtMs + RETIRED_KEY_SKEW_MS
            // ⚠⚠ Every clock the payload will be merged at, not just the envelope's: a `DeltaBatchPayload`
            // applies each delta at its OWN inner HLC (`applyDeltaBatch`), so a removed member could
            // otherwise backdate the container and carry post-retirement deltas inside it — overwriting
            // newer writes and dragging the clock.
            val innerClocks = (parsed as? DeltaBatchPayload)?.deltas?.toList()?.map { it.hlc }.orEmpty()
            if ((listOf(block.hlc) + innerClocks).any { it.physicalTime.get() >= cutoff }) {
                EnvelopeRejections.record(
                    EnvelopeRejections.Kind.RETIRED_KEY_AFTER_RETIREMENT,
                    block.blockId.get(),
                )
                return null
            }
            return parsed
        }
        if (!anyOpened) EnvelopeRejections.record(EnvelopeRejections.Kind.BLOCK_ENVELOPE, block.blockId.get())
        return null
    }

    /**
     * The row a queued **whole-row** block of this device carries, decrypted — the only
     * way back from a refused block's wire name to the local holder it names. Null for a property block,
     * a batch, or anything unreadable (`content-plane-cr2-b4-spec.md` §2.2).
     */
    fun rowOfOwnBlock(block: SyncBlock): Data? {
        val payload = runCatching { openBoundPayload(block) }.getOrNull()
            as? DataBlockPayload ?: return null
        if (payload.propertyId != null) return null
        return payload.value
    }

    /**
     * Decrypt + parse a raw identity-key-encrypted payload — the block-independent core
     * of [decryptAndParse], reused by the content-plane manifest read path
     * ([resolveContentManifest]) where the ciphertext arrives as a content blob, not a
     * [SyncBlock], and there is no HLC to advance.
     *
     * SR-M.1a/1b: decrypt under this processor's realm keys. A shared-realm processor is
     * constructed with fixed [realmKeys] (SR-M.1b-pull); the personal processor falls
     * back to the device's single `currentRealmContext()`. Trial-decrypt (GR-7): the
     * current identity key first, then each retired key — a payload written before a
     * rotation stays under the old Kr. A candidate wins only if decrypt *and* envelope-
     * open *and* parse all succeed: a wrong key that yields garbage (the AEAD tag rejects
     * in production, but a non-throwing failure must not be treated as the answer) is
     * rejected at the stamp or at parse and the next key is tried.
     *
     * E0 (`core-schema-evolution.md` §5): the decrypted plaintext begins with the
     * producing format version ([BlockPayloadEnvelope]); the composite bytes follow.
     *
     * E2c (§8.2): when migration chains are registered ([payloadUpcaster]), the stamp
     * routes the payload through the version-keyed upcast — a below-current block
     * decodes under the *producing* schemas and is upcast to the current shape before
     * materialization; a below-floor stamp, or a migration deciding to drop a value,
     * drops BY DECISION with a named log line rather than a silent parse-to-null. With
     * no chains registered the stamp is not even parsed. A payload with no readable stamp is
     * still dropped like any other parse failure.
     */
    private fun decryptAndParseCipher(cipher: ByteArray): Data? {
        val keys = realmKeys ?: (syncManager.currentRealmContext()?.derivedKeys ?: return null)
        // ⚠ Content-plane manifest blobs only (no envelope, no HLC): the retired keyring is tried
        // without the D-c rule, which needs a block HLC to judge by.
        val candidateIdentityKeys = if (realmRetiredKeys.isEmpty()) {
            listOf(keys.sharedIdentityKey)
        } else {
            listOf(keys.sharedIdentityKey) + realmRetiredKeys.map { it.keys.sharedIdentityKey }
        }
        return candidateIdentityKeys.firstNotNullOfOrNull { identityKey ->
            val decryptedData = try {
                cryptoManager.decrypt(cipher, identityKey)
            } catch (_: Exception) {
                null
            } ?: return@firstNotNullOfOrNull null
            openAndParse(decryptedData)
        }
    }

    /** Envelope-open + parse a decrypted payload; `null` when either fails under this candidate key. */
    private fun openAndParse(decryptedData: ByteArray): Data? {
        val opened = BlockPayloadEnvelope.open(decryptedData) ?: return null
        return try {
            parsePayload(opened)
        } catch (_: Exception) {
            // Malformed under this candidate key (wrong-key garbage on the
            // GR-7 trial-decrypt path, or genuinely corrupt) — try the next.
            null
        }
    }

    /**
     * The one parse funnel behind the envelope: routes through the E2c
     * [PayloadUpcaster] when chains exist, else today's live parse. Dropped-BY-
     * DECISION verdicts are logged here and yield `null` (block dropped);
     * nested per-value drops (e.g. a retired-type delta removed from a
     * surviving batch) are logged and the rest of the payload applies.
     */
    private fun parsePayload(opened: BlockPayloadEnvelope.Opened): Data? {
        val upcaster = payloadUpcaster ?: return parser.parse<Data>(opened.payloadBytes)
        return when (val result = upcaster.upcast(opened.payloadBytes, opened.stampBytes)) {
            is PayloadUpcaster.UpcastResult.Current -> {
                result.droppedValues.forEach { reason ->
                    println("WARN SyncProcessor: payload value dropped BY DECISION — $reason")
                }
                result.payload
            }
            is PayloadUpcaster.UpcastResult.Dropped -> {
                println(
                    "WARN SyncProcessor: block payload (stamped format v${opened.formatVersion}) " +
                        "dropped BY DECISION — ${result.reason}"
                )
                null
            }
        }
    }

    /**
     * The chunk refs [contentId] owns, or `null` if they cannot be named.
     *
     * For the reclamation sweep (`drive-upload-stop-and-reclamation.md` §4.17), which has to
     * tell the relay *which* blobs to drop, and a manifest is the only thing that lists them. This
     * is the first half of [resolveContentManifest] — fetch, decrypt, parse — stopping short of the
     * `apply` that writes bytes, because the sweep wants the names and not the file.
     *
     * ⚠⚠ **`null` means "cannot name them", never "there are none".** The sweep drops a candidate it
     * cannot resolve, leaking those bytes deliberately rather than deleting on a guess. An empty
     * list is a manifest that genuinely lists no chunks, and the two must not collapse.
     *
     * ⚠ Read-only: advances no clock, records no sighting, writes nothing. Unlike
     * [resolveContentManifest] there is no entity to apply against — asking what a content owns is
     * not the same as fetching it, and the sweep must be able to ask about content this device
     * never held.
     */
    suspend fun contentChunkRefs(contentId: String): List<String>? {
        val applier = manifestApplier ?: return null
        val cipher = applier.fetchManifestCiphertext(contentId) ?: return null
        val manifest = decryptAndParseCipher(cipher) as? FileContentManifestPayload ?: return null
        return runCatching { manifest.chunks.toList().map { it.ref.get() } }.getOrNull()
    }

    /**
     * Content-addressed read path (`file-content-addressing.md` §8.3): fetch
     * the file's manifest from the content plane by its stable [contentId], decrypt +
     * parse it, and reassemble the bytes via the same [FileContentManifestApplier.apply]
     * the legacy mailbox-block path uses. The counterpart to
     * [SyncObserver.emitFileContent]'s content-plane manifest PUT. [entityId]'s hash is
     * the local reverse-index key the applier resolves to this file's on-disk path
     * (seeded by `OnDemandResolutionImpl.resolve`'s `rememberEntity`), exactly as
     * `block.targetEntityIdHash` was for a manifest block. A clean no-op if no applier is
     * wired, the manifest isn't on the relay yet, or keys are missing — the resolve retries.
     */
    suspend fun resolveContentManifest(
        entityId: org.tenshu.core.entity.DataId,
        contentId: String,
        propertyId: String? = null,
    ) {
        val applier = manifestApplier ?: return
        val cipher = applier.fetchManifestCiphertext(contentId)
        if (cipher == null) {
            // `drive-upload-stop-and-reclamation.md` §4.18: the manifest is the only thing
            // that names a content's chunks, so its absence is what a **dangling reference** looks
            // like from here — a live row pointing at content the relay no longer holds.
            //
            // ⚠ Absent is not always dangling: an upload still in flight has no manifest yet
            // either. The repair does not try to tell them apart, because it does not need to — it
            // acts only when *this* device holds the plaintext, and a device that never had the
            // file cannot mistake one for the other. See [ContentRepair].
            if (propertyId != null) contentRepair?.repair(entityId, propertyId, contentId)
            return
        }
        val manifest = decryptAndParseCipher(cipher) as? FileContentManifestPayload ?: return
        applier.apply(
            executionContext = executionContext,
            manifest = manifest,
            targetEntityHash = wireIds.of(entityId),
            tombstoneRegistry = tombstoneRegistry,
        )
    }

    /**
     * Streaming counterpart to [resolveContentManifest] (D2b): fetch + decrypt the
     * content-plane manifest by [contentId] and open a lazy [StreamingContentSession]
     * over it instead of reassembling to disk. `null` if the property doesn't match, no
     * applier/keys, or the manifest isn't synced.
     */
    internal suspend fun openContentStreamByContentId(
        contentId: String,
        propertyId: String,
    ): StreamingContentSession? {
        val applier = manifestApplier ?: return null
        val cipher = applier.fetchManifestCiphertext(contentId) ?: return null
        val manifest = decryptAndParseCipher(cipher) as? FileContentManifestPayload ?: return null
        if (manifest.propertyId.get() != propertyId) return null
        return applier.openStreamingSession(manifest)
    }

    /**
     * Synchronous ingest (the eager scheduler drain via
     * [SyncScheduler.pullChanges], and the polymorphic [process]
     * entry). A [org.tenshu.sync.DataBlockPayload] is applied as before.
     * A [FileContentManifestPayload] is **intentionally a no-op here**:
     * applying it means fetching that file's chunk bytes, which on the
     * eager all-blocks drain would re-introduce exactly the
     * "drain every chunk of every file" cost the two-plane split
     * removes (`docs/adr/0017-file-content-two-plane-channel.md` selective-fetch). The
     * manifest itself is small and already rode the ordered drain;
     * the *bytes* are pulled lazily on the OnDemand suspend path
     * ([processSyncBlockAsync]) when the file is actually opened. This
     * also preserves the documented P1→P2 receiver-safety: an
     * unhandled manifest never crashes / misapplies.
     */
    fun processSyncBlock(block: SyncBlock) {
        val payload = decryptAndParse(block) ?: return
        when (payload) {
            is org.tenshu.sync.DataBlockPayload -> applyDataBlockPayload(payload, block)
            is DeltaBatchPayload -> applyDeltaBatch(payload, block)
            is FileContentManifestPayload -> {
                // Eager-drain no-op by design — see KDoc. Selective
                // fetch happens on the OnDemand suspend path.
            }
            else -> Unit
        }
    }

    /**
     * Suspend ingest used by the OnDemand resolve path
     * (`OnDemandResolutionImpl.resolve` ← `Maybe.get()`/`.flow()` on
     * the `File.Relation.Data` OnDemand property). Identical to
     * [processSyncBlock] for [org.tenshu.sync.DataBlockPayload]s, but a
     * [FileContentManifestPayload] is **applied** here: only the
     * actually-opened file's chunks are fetched (the resolver scoped
     * `fetchEntityBlocks` to that one entity), one at a time, decrypted
     * + integrity-gated + reassembled into `FileDatabase`. This is the
     * mirror of the P1 emit and the point at which a synced file
     * becomes readable.
     */
    /**
     * Seed this processor's reverse index ([TombstoneRegistry.rememberDataId]) with
     * [entityId] so a subsequent [FileContentManifestApplier.apply] for it can resolve
     * the local DataId even when the mapping was never written via `onDataSaved` this
     * session — e.g. the entity was hydrated from disk at startup, which doesn't fire the
     * save observer. Called from the OnDemand byte-fetch path, the one place a reader holds
     * the entity's DataId. Routes to the right registry automatically: a shared realm uses
     * its own per-realm processor (and thus its own registry), the personal one otherwise.
     */
    fun rememberEntity(entityId: org.tenshu.core.entity.DataId) =
        tombstoneRegistry.rememberDataId(entityId)

    /**
     * [entityId]'s EI-1 wire id under this processor's realm — the name `fetchEntityBlocks` must ask
     * the relay for (B3 wire spec §11.3), from the same resolver that names this realm's ingest. Null
     * when there is no realm key yet: then nothing can be fetched, and no `DataId` is ever sent instead.
     */
    fun wireIdOf(entityId: org.tenshu.core.entity.DataId): String? = wireIds.ofOrNull(entityId)

    /**
     * D2b streaming (`drive-streaming-preview.md` §3.5): open a [StreamingContentSession] over
     * the file addressed by [propertyId] among [blocks] (the entity's blocks the resolver
     * fetched). The streaming counterpart to [processSyncBlockAsync]'s reassemble branch —
     * it finds this property's [FileContentManifestPayload] and opens a lazy session via the
     * manifest applier instead of fetching + reassembling every chunk to disk. Returns `null`
     * if no applier is wired or no matching manifest is present (entity/bytes unsynced).
     *
     * Decrypts blocks in order until the manifest is found (advancing HLC on those it touches,
     * exactly as ingesting them would); it does **not** apply `DataBlockPayload`s — the streamed
     * file's metadata entity is already local (the user is previewing a listed file), and the
     * session reads chunks by ref straight from the content store, not via the entity.
     */
    internal fun openContentStream(blocks: List<SyncBlock>, propertyId: String): StreamingContentSession? {
        val applier = manifestApplier ?: return null
        for (block in blocks) {
            val payload = decryptAndParse(block)
            if (payload is FileContentManifestPayload && payload.propertyId.get() == propertyId) {
                return applier.openStreamingSession(payload)
            }
        }
        return null
    }

    suspend fun processSyncBlockAsync(block: SyncBlock) {
        val payload = decryptAndParse(block) ?: return
        when (payload) {
            is org.tenshu.sync.DataBlockPayload -> applyDataBlockPayload(payload, block)
            is DeltaBatchPayload -> applyDeltaBatch(payload, block)
            is FileContentManifestPayload -> {
                val applier = manifestApplier ?: return
                val targetEntityHash = block.targetEntityIdHash?.get() ?: return
                applier.apply(
                    executionContext = executionContext,
                    manifest = payload,
                    targetEntityHash = targetEntityHash,
                    tombstoneRegistry = tombstoneRegistry
                )
            }
            else -> Unit
        }
    }

    private fun applyDataBlockPayload(
        payload: org.tenshu.sync.DataBlockPayload,
        block: SyncBlock
    ) {
        val propertyId = payload.propertyId?.get()
        val data = payload.value

        // Save to local database with conflict resolution.
        if (!propertyId.isNullOrEmpty()) {
            val targetEntityHash = block.targetEntityIdHash?.get() ?: return
            attachPropertyAndSave(data, block.hlc, targetEntityHash, propertyId)
        } else {
            resolveConflictAndSave(data, block.hlc, blockLabel = block.blockId.get())
        }
    }

    /**
     * Task-scoped sync lane ingest (`core-background-processing.md` §10.2,
     * TS-1): apply a checkpoint container by running each delta through the
     * SAME per-entity cycle a standalone block runs — HLC advance,
     * tombstone/epoch/entitlement gates, `SyncMetadata` compare, LWW/Merge —
     * with the delta's OWN inner HLC. Batching, not semantic fusion: a
     * concurrent organic edit to one contained entity resolves exactly as it
     * would against a standalone block. Per-delta transactionality (§10.5
     * D7): a delta that drops affects only itself, identical to N standalone
     * blocks. Property deltas resolve their parent via the in-ciphertext
     * [org.tenshu.sync.SyncDelta.targetEntityIdHash] (the container
     * block's wire hash is null — D2); one arriving without it is dropped
     * with a log, matching the standalone path's missing-hash drop.
     */
    private fun applyDeltaBatch(payload: DeltaBatchPayload, block: SyncBlock) {
        for (delta in payload.deltas.toList()) {
            // Same author as the container block (already observed in `decryptAndParse`), so this
            // is redundant by construction — routed through `observe` anyway so the invariant
            // "every accepted foreign HLC is a sighting" holds at every site rather than by
            // reasoning about call order. The recorder's throttle makes the repeat free.
            observe(delta.hlc)
            val propertyId = delta.propertyId?.get()
            if (!propertyId.isNullOrEmpty()) {
                val targetEntityHash = delta.targetEntityIdHash?.get()
                if (targetEntityHash == null) {
                    println(
                        "WARN SyncProcessor: dropping checkpoint property delta without " +
                            "targetEntityIdHash (blockId=${block.blockId.get()}, propertyId=$propertyId)"
                    )
                    continue
                }
                attachPropertyAndSave(delta.value, delta.hlc, targetEntityHash, propertyId)
            } else {
                resolveConflictAndSave(delta.value, delta.hlc, blockLabel = block.blockId.get())
            }
        }
    }

    private fun attachPropertyAndSave(
        propertyData: Data,
        hlc: HybridLogicalClock,
        targetEntityHash: String,
        propertyId: String
    ) {
        // Externally-stored property (e.g. file bytes routed through
        // `FileDatabase` rather than `Entity.properties`): dispatch to the
        // registered `ExternalPropertyWriter`. The hash→DataId reverse
        // index is maintained by `TombstoneRegistry.onDataSaved` for every
        // saved entity, so by the time an OnDemand-fetched property block
        // arrives, the parent entity has already been ingested in this
        // same `processSyncBlock` loop and the lookup succeeds.
        val writer = executionContext.context.externalPropertyWriter()
        if (writer != null && writer.supports(propertyId)) {
            val dataId = tombstoneRegistry.resolveDataId(targetEntityHash) ?: return
            writer.write(
                executionContext = executionContext,
                targetEntityId = dataId,
                propertyId = propertyId,
                value = propertyData
            )
            // Bump the per-property sync metadata so subsequent fetches
            // can short-circuit on HLC if needed. Keyed by
            // "entityHash/propertyId" — distinct from the entity-level
            // metadata so a property write doesn't shadow an entity update.
            executionContext.saveData(
                SyncMetadata.create(
                    context = executionContext.context,
                    hlc = hlc,
                    targetDataIdHash = "$targetEntityHash/$propertyId",
                    isConflicted = false
                ),
                origin = WriteOrigin.SYNC_INGEST
            )
            return
        }

        // No writer registered for this property: this is currently a
        // dead branch in production (the only `Syncable(OnDemand)` property
        // is `File.Relation.Data`, handled by `FileBytesPropertyWriter`).
        // Logged so unexpected property blocks are visible.
        println("SyncProcessor -> Received on-demand property $propertyId for entity hash $targetEntityHash (no writer registered)")
    }

    /**
     * Ingest one entity block: tombstone gate → conflict resolution → save.
     *
     * Runs as ONE transaction because the gate is a check-then-act against state a
     * *concurrent local delete* is changing. Ingest runs on the scheduler's coroutine while
     * the user's delete runs on the request's; evaluating the tombstone unlocked lets this
     * pass the gate (no tombstone yet), the delete then remove the row and stamp it, and
     * this re-save it afterwards — the deleted entity comes back, and the next write at
     * that key fails with `AlreadyExistsError`. `ExecutionContext.deleteData` holds the same
     * lock across its own fanout, so the two orders that remain are both correct: ingest
     * fully before the delete (its save is removed by it), or fully after (the gate fires).
     */
    private fun resolveConflictAndSave(
        incomingData: Data,
        hlc: HybridLogicalClock,
        blockLabel: String
    ): Unit = executionContext.database.transaction {
        val dataId = executionContext.getDataId(incomingData) ?: return@transaction
        val dataIdHash = wireIds.of(dataId)

        // Tombstone short-circuit: if the entity is tombstoned at an HLC ≥ this update's HLC,
        // drop the update — the entity is dead at this point in time. A future update with
        // hlc > tombstone.hlc is treated as a recreate and falls through to the normal path.
        val tombstoneHlc = tombstoneRegistry.tombstoneHlcFor(dataIdHash.toString())
        if (tombstoneHlc != null && tombstoneHlc.compare(hlc) >= 0) return@transaction

        // Stale-epoch rejection (T-K-02 in `docs/sync/threat-model.md`): a `Device` entity
        // block whose `admittedAtEpoch` is older than this receiver's `currentRealmEpoch`
        // was issued under an admission record the realm has since rotated past. Drop it.
        // Note: a freshly-recovered receiver has `currentRealmEpoch == 0` until tombstones
        // catch up, so this check is conservative on cold start and only sharpens once
        // tombstones land — see `docs/adr/0007-sync-device-management.md` "Recovery race against
        // revocation" carve-out.
        if (isStaleDeviceBlock(incomingData, blockLabel)) return@transaction

        // Publisher-signature check (`entitlement-trust-anchor-distribution.md` D1): an incoming
        // `Entitlement` whose signature doesn't verify is REPORTED, not dropped — see
        // [reportUnverifiedEntitlementBlock] for why retention is the safer behaviour.
        reportUnverifiedEntitlementBlock(incomingData)

        val metadataId = StringDataId.create(
            executionContext.context,
            SyncMetadata.ID,
            dataIdHash.toString()
        )

        val existingMetadata = executionContext.database.getData(metadataId) as? SyncMetadata
        val existingData = executionContext.database.getData(dataId)

        val isNewer = existingMetadata == null || hlc.compare(existingMetadata.hlc) > 0

        // Apply conflict resolution based on trait
        val underlyingTypeId = if (incomingData is Entity<*>) {
            incomingData.type.get()
        } else {
            incomingData.id
        }
        val trait = executionContext.traitResolver.getTrait<Syncable>(underlyingTypeId, Syncable.ID)
        val strategy = trait?.conflictStrategy ?: ConflictStrategy.LastWriterWins

        if (existingData == null || isNewer) {
            when (strategy) {
                ConflictStrategy.Merge -> {
                    // ⚠ Purge AFTER the merge, never before: the dead member can arrive on
                    // either side. `incoming` carries it when the sender had not yet learned
                    // of the delete; `existing` can still carry it when this device applied
                    // the parent's block before the child's tombstone — which is the normal
                    // order, since a tick applies every block before any tombstone.
                    val resolved =
                        if (existingData != null) mergeData(existingData, incomingData) else incomingData
                    executionContext.saveData(
                        purgeDeadMembers(resolved),
                        origin = WriteOrigin.SYNC_INGEST,
                    )
                }
                else -> {
                    // LWW or Manual (on newer update, we just overwrite)
                    executionContext.saveData(incomingData, origin = WriteOrigin.SYNC_INGEST)
                }
            }

            // Update sync metadata
            executionContext.saveData(
                SyncMetadata.create(
                    context = executionContext.context,
                    hlc = hlc,
                    targetDataIdHash = dataIdHash,
                    isConflicted = false
                ),
                origin = WriteOrigin.SYNC_INGEST
            )
        } else {
            // Incoming is older or equal
            if (strategy == ConflictStrategy.Manual) {
                println("SyncProcessor -> MANUAL CONFLICT detected for $dataId")
                executionContext.saveData(
                    SyncMetadata.create(
                        context = executionContext.context,
                        hlc = existingMetadata.hlc,
                        targetDataIdHash = dataIdHash,
                        isConflicted = true
                    ),
                    origin = WriteOrigin.SYNC_INGEST
                )
            }
        }
    }

    /**
     * T-K-02 merge-time epoch check. Returns `true` if [incomingData] is a `Device` entity
     * block whose `admittedAtEpoch` is strictly older than the receiver's
     * [SyncManager.currentRealmEpoch] — in which case the caller should drop the block.
     *
     * Only applies to full-entity Device blocks; property-targeted blocks
     * (`DataBlockPayload.propertyId != null`) flow through [attachPropertyAndSave] and never
     * reach this code path. Future-epoch blocks (`admittedAtEpoch > currentRealmEpoch`) are
     * accepted on purpose — they're either legitimate (the sender is ahead on tombstones)
     * or they'll be invalidated later by a stale-epoch check on a downstream merge.
     */
    private fun isStaleDeviceBlock(incomingData: Data, blockLabel: String): Boolean {
        if (incomingData !is Entity<*>) return false
        if (incomingData.type.get() != Device.ID) return false

        val admittedAtEpoch = incomingData
            .getFieldValue<TInteger>(Device.Fields.ADMITTED_AT_EPOCH)
            .get()
        val currentEpoch = syncManager.currentRealmEpoch

        if (admittedAtEpoch >= currentEpoch) return false

        val deviceId = runCatching {
            incomingData.getFieldValue<org.tenshu.core.TString>(Device.Fields.DEVICE_ID).get()
        }.getOrNull()
        println(
            "WARN SyncProcessor: dropping stale Device block " +
                    "(blockId=$blockLabel, deviceId=$deviceId, " +
                    "claimedEpoch=$admittedAtEpoch, currentEpoch=$currentEpoch) — " +
                    "T-K-02 merge-time enforcement"
        )
        return true
    }

    /**
     * Report — but do **not** drop — an incoming `Entitlement` whose `publisherSignature` does not
     * verify against any key this client trusts for its `publisherId`.
     *
     * ⚠⚠ Not dropped, although a forged record must not let a peer mint itself a paid tier:
     * enforcement lives elsewhere (`entitlement-trust-anchor-distribution.md` §2). The **relay**
     * re-verifies every record against its own configured key before honouring it, so a record this
     * client cannot verify buys its holder nothing. Dropping would protect nothing and cost a great
     * deal:
     *
     *  - it is **silent** — a `println` the packaged app discards — and indistinguishable from
     *    "no entitlement yet", so an issuer-key rotation presents as a Services card stuck on
     *    "Setting up your storage";
     *  - it **strands paired devices**: the dropped row is what
     *    `EntitlementRecordCredentialProvider` forwards as `X-Entitlement-Proof`, so a
     *    session-less second device is left write-dead on `402` — the exact failure
     *    [EntitlementRecovery] exists to prevent;
     *  - and it cannot deliver its own invariant anyway, because the local writer stores records
     *    **without** verifying. The two halves disagreeing about the same record makes the
     *    symptom oscillate rather than fail cleanly.
     *
     * So the record is retained and forwarded, and the *reader* declines to derive figures from it
     * (`initEntitlementStorageReader`). The client is cautious about what it **displays**; the
     * relay decides what it **accepts**.
     *
     * ⚠ Verification here is purely diagnostic — it exists to raise
     * [EntitlementUnverifiedError], not to gate anything.
     */
    private fun reportUnverifiedEntitlementBlock(incomingData: Data) {
        val fields = EntitlementSignature.fieldsOf(incomingData) ?: return
        val publicKeys = EntitlementSignature.publicKeysFor(fields.publisherId)
        // ⚠ ANY trusted key for this publisher may have signed it: one issuer legitimately has
        // several (dev seed on a DEV_MODE stack, ops `ISSUER_SEED` in production). Verifying
        // against only the first is what made a real-seed promotion reject every record it minted.
        val valid = publicKeys.isNotEmpty() &&
            publicKeys.any { cryptoManager.verifyEd25519(it, fields.message(), fields.signature) }
        if (valid) return

        val reason = if (publicKeys.isEmpty()) "unknown publisher" else "signature mismatch"
        println(
            "WARN SyncProcessor: retaining UNVERIFIED Entitlement ($reason) " +
                "(ownerId=${fields.ownerId}, publisher=${fields.publisherId}) — " +
                "it will not drive storage figures; the relay re-verifies before honouring it"
        )
        executionContext.errorEventRecorder?.record(
            error = EntitlementUnverifiedError.create(
                context = executionContext.context,
                publisherId = fields.publisherId,
            ),
            operationId = ENTITLEMENT_VERIFY_OPERATION_ID,
            callstackDepth = 0,
        )
    }

    /**
     * Strip references to dead entities out of [data] before it is stored — see
     * [SemanticMerger.purgeDeadMembers] for why a union merge cannot do this itself.
     *
     * **Dead** is *tombstoned AND absent from the store*, evaluated here because this is
     * where [tombstoneRegistry] lives. The second half is what keeps a legitimate
     * re-create (a higher-HLC create after a tombstone — Drive's auto-backup re-creating
     * a destination folder a peer deleted) from being purged as though it were the
     * original dead row.
     */
    private fun purgeDeadMembers(data: Data): Data {
        val typeId = if (data is Entity<*>) data.type.get() else data.id
        val merger = mergerRegistry.getMerger(typeId) ?: return data
        return merger.purgeDeadMembers(executionContext, data) { memberId ->
            tombstoneRegistry.tombstoneHlcFor(wireIds.of(memberId)) != null &&
                executionContext.database.getData(memberId) == null
        }
    }

    private fun mergeData(existing: Data, incoming: Data): Data {
        val typeId = if (existing is Entity<*>) {
            existing.type.get()
        } else {
            existing.id
        }
        val merger = mergerRegistry.getMerger(typeId)
        return merger?.merge(executionContext, existing, incoming) ?: incoming
    }
}
