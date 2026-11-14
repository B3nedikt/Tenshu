package org.tenshu.sync.proxy

import org.tenshu.core.common.SYNC_CHANGES_PAGE_LIMIT
import kotlinx.coroutines.delay
import org.tenshu.core.Data
import org.tenshu.core.common.content.ContentEdge
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncLogResponse
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.common.SyncRelay

/**
 * An in-memory relay for tests.
 *
 * [simulateLatency] pauses each call for a fixed time, standing in for network latency. ⚠ That
 * time is real unless the call runs on `runTest`'s scheduler: a rig whose proxy runs on
 * `Dispatchers.Default` pays it in wall-clock time (`docs/testing-flakes.md` §6). Prefer putting
 * the rig on the test's scope. Turn the latency off only where the harness cannot run on virtual
 * time, and only where no assertion depends on a call taking time.
 */
class MockSyncRelay(
    private val context: TContext,
    private val simulateLatency: Boolean = true,
) : SyncRelay {
    // Mirror the real relay's arrival-time cursor: every push gets a monotonic seq, reads
    // filter by `seq > since`, and responses carry `nextCursor`. Lets MockSyncRelay-backed
    // tests exercise the same cursor semantics RestSyncRelay sees in production.
    private var arrivalSeq = 0L
    private val blocks = mutableListOf<Pair<Long, SyncBlock>>()
    private val tombstones = mutableListOf<Pair<Long, DeleteBlock>>()

    // Per-owner per-device latest tip — mirrors `HlcTipStore` on the real relay.
    private val tipsByOwner = mutableMapOf<String, MutableMap<String, SignedHlcTip>>()

    // CR-2 B3: what each push carried beside its block, by `blockId` — the wire's edges and cascades.
    private val edgesByBlock = mutableMapOf<String, List<ContentEdge>>()
    private val cascadeByBlock = mutableMapOf<String, List<String>>()

    override suspend fun pushBlock(block: SyncBlock, edges: List<ContentEdge>): TResult<Data?> {
        latency(500) // Simulate network latency
        blocks.add(++arrivalSeq to block)
        edgesByBlock[block.blockId.get()] = edges
        return TResult.Success.create(context, value = null)
    }

    /** Test accessor: the blocks this relay has received, in arrival order. */
    fun pushedBlocks(): List<SyncBlock> = blocks.map { it.second }

    /** Test accessor: the edges the push of [blockId] carried (CR-2 B3), or null if none arrived. */
    fun pushedEdgesOf(blockId: String): List<ContentEdge>? = edgesByBlock[blockId]

    /** Test accessor: the cascade the tombstone [blockId] carried (CR-2 B3), or null if none arrived. */
    fun pushedCascadeOf(blockId: String): List<String>? = cascadeByBlock[blockId]

    override suspend fun getChanges(
        sinceTimestamp: Long,
        tip: SignedHlcTip
    ): TResult<ChangesResponse> {
        latency(500) // Simulate network latency
        // Record the requester's tip BEFORE assembling peer tips — mirrors the relay.
        val ownerId = tip.ownerId.get()
        val deviceId = tip.deviceId.get()
        val perDevice = tipsByOwner.getOrPut(ownerId) { mutableMapOf() }
        perDevice[deviceId] = tip
        val matched = blocks.filter { it.first > sinceTimestamp }
        // Empty stays at `sinceTimestamp` (don't skip past peer-side traffic) — see
        // SyncMailbox.getChanges for the rationale.
        val nextCursor = matched.maxOfOrNull { it.first } ?: sinceTimestamp
        val peerTips = perDevice.entries
            .filter { it.key != deviceId }
            .map { it.value }
        return TResult.Success.create(
            context,
            value = ChangesResponse.create(
                context = context,
                blocks = matched.map { it.second },
                peerTips = peerTips,
                nextCursor = nextCursor
            )
        )
    }

    override suspend fun pushTombstone(block: DeleteBlock, cascade: List<String>): TResult<Data?> {
        latency(500)
        tombstones.add(++arrivalSeq to block)
        cascadeByBlock[block.blockId.get()] = cascade
        return TResult.Success.create(context, value = null)
    }

    override suspend fun getTombstones(sinceTimestamp: Long): TResult<TombstonesResponse> {
        latency(500)
        val matched = tombstones.filter { it.first > sinceTimestamp }
        val nextCursor = matched.maxOfOrNull { it.first } ?: sinceTimestamp
        return TResult.Success.create(
            context,
            value = TombstonesResponse.create(
                context = context,
                tombstones = matched.map { it.second },
                nextCursor = nextCursor
            )
        )
    }

    /**
     * The merged lane (`sync-lane-cursor-split.md` L3), implemented for real rather than left
     * on the "unsupported" default — otherwise every in-process harness would silently take
     * the two-lane fallback and the merged path would ship with no end-to-end coverage.
     *
     * Mirrors `SyncMailbox.getSyncLog`: merge both logs by the **shared** arrival seq, cut ONE
     * page across both kinds, and report the max seq of that prefix. Cutting the page across
     * the union is the whole property — it is what makes a single cursor sound for both kinds.
     */
    override suspend fun getSyncLog(
        sinceTimestamp: Long,
        tip: SignedHlcTip
    ): SyncRelay.SyncLogFetch {
        latency(500) // Simulate network latency
        // Record the requester's tip BEFORE assembling peer tips — mirrors the relay.
        val ownerId = tip.ownerId.get()
        val deviceId = tip.deviceId.get()
        val perDevice = tipsByOwner.getOrPut(ownerId) { mutableMapOf() }
        perDevice[deviceId] = tip

        val matchedBlocks = blocks.filter { it.first > sinceTimestamp }
        val matchedTombstones = tombstones.filter { it.first > sinceTimestamp }
        val pageSeqs = (matchedBlocks.map { it.first } + matchedTombstones.map { it.first })
            .sorted()
            .take(SYNC_CHANGES_PAGE_LIMIT)
        val cutoff = pageSeqs.lastOrNull()

        val pagedBlocks = if (cutoff == null) emptyList() else matchedBlocks.filter { it.first <= cutoff }
        val pagedTombstones = if (cutoff == null) emptyList() else matchedTombstones.filter { it.first <= cutoff }
        val hasMore = cutoff != null &&
            (matchedBlocks.any { it.first > cutoff } || matchedTombstones.any { it.first > cutoff })

        return SyncRelay.SyncLogFetch.Found(
            SyncLogResponse.create(
                context = context,
                blocks = pagedBlocks.sortedBy { it.first }.map { it.second },
                tombstones = pagedTombstones.sortedBy { it.first }.map { it.second },
                peerTips = perDevice.entries.filter { it.key != deviceId }.map { it.value },
                // Empty stays at `sinceTimestamp` (don't skip past peer-side traffic) — see
                // SyncMailbox.getChanges for the rationale.
                nextCursor = cutoff ?: sinceTimestamp,
                hasMore = hasMore
            )
        )
    }

    /**
     * The backfill page (C2) — implemented for real, like [getSyncLog], so the in-process harnesses
     * exercise the newest-first path rather than silently declining it.
     */
    override suspend fun getSyncLogBefore(
        beforeSeq: Long,
        tip: SignedHlcTip
    ): SyncRelay.SyncLogFetch {
        latency(500) // Simulate network latency
        val ownerId = tip.ownerId.get()
        val deviceId = tip.deviceId.get()
        val perDevice = tipsByOwner.getOrPut(ownerId) { mutableMapOf() }
        perDevice[deviceId] = tip

        val matchedBlocks = blocks.filter { it.first < beforeSeq }
        val matchedTombstones = tombstones.filter { it.first < beforeSeq }
        val pageSeqs = (matchedBlocks.map { it.first } + matchedTombstones.map { it.first })
            .sortedDescending()
            .take(SYNC_CHANGES_PAGE_LIMIT)
        val floor = pageSeqs.lastOrNull()

        val pagedBlocks = if (floor == null) emptyList() else matchedBlocks.filter { it.first >= floor }
        val pagedTombs = if (floor == null) emptyList() else matchedTombstones.filter { it.first >= floor }
        val hasMore = floor != null &&
            (matchedBlocks.any { it.first < floor } || matchedTombstones.any { it.first < floor })

        return SyncRelay.SyncLogFetch.Found(
            SyncLogResponse.create(
                context = context,
                blocks = pagedBlocks.sortedBy { it.first }.map { it.second },
                tombstones = pagedTombs.sortedBy { it.first }.map { it.second },
                peerTips = perDevice.entries.filter { it.key != deviceId }.map { it.value },
                nextCursor = floor ?: beforeSeq,
                hasMore = hasMore
            )
        )
    }

    /** Blocks an entity-blocks fetch should return (seed via [seedEntityBlocks]). */
    private val entityBlocks = mutableListOf<SyncBlock>()

    /**
     * Seed the blocks [fetchEntityBlocks] returns (e.g. a file's `FileContentManifestPayload`
     * for the OnDemand read / D2b streaming path). Returned for every entity-blocks fetch — the
     * mock doesn't index by entityId hash; tests scope to one entity.
     */
    fun seedEntityBlocks(vararg blocks: SyncBlock) {
        entityBlocks += blocks
    }

    override suspend fun fetchEntityBlocks(
        entityWireId: String
    ): TResult<TList<SyncBlock>> {
        latency(1000) // Fetching might be slower
        return TResult.Success.create(
            context,
            value = entityBlocks.toList().toTList(context)
        )
    }

    private val pairingRequests = mutableMapOf<String, SyncRelay.RelayPairingRequest>()
    private val pairingResponses = mutableMapOf<String, ByteArray>()

    override suspend fun postPairingRequest(
        ownerId: String,
        pairingSecretHash: String,
        deviceId: String,
        sealedDeviceName: ByteArray,
        publicKey: ByteArray,
        signingPublicKey: ByteArray
    ): TResult<Data?> {
        latency(500)
        refuseUnderivedId(deviceId, signingPublicKey)?.let { return it }
        pairingRequests[pairingSecretHash] = SyncRelay.RelayPairingRequest(
            deviceId = deviceId,
            sealedDeviceName = sealedDeviceName,
            publicKey = publicKey,
            signingPublicKey = signingPublicKey,
            pairingSecretHash = pairingSecretHash,
        )
        return TResult.Success.create(context, value = null)
    }

    override suspend fun getPairingRequests(ownerId: String): SyncRelay.PairingRequestsFetch {
        latency(500)
        return SyncRelay.PairingRequestsFetch.Found(pairingRequests.values.toList())
    }

    override suspend fun postPairingResponse(
        pairingSecretHash: String,
        encryptedMasterKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        masterDelegation: ByteArray,
        epoch: Long,
        admissionCertChain: ByteArray?,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        latency(500)
        refuseUnderivedId(newDeviceId, newDevicePublicKey)?.let { return it }
        pairingResponses[pairingSecretHash] = encryptedMasterKey
        return TResult.Success.create(context, value = null)
    }

    override suspend fun ackPairingResponse(pairingSecretHash: String): TResult<Data?> {
        latency(100)
        pairingResponses.remove(pairingSecretHash)
        return TResult.Success.create(context, value = null)
    }

    override suspend fun getPairingResponse(pairingSecretHash: String): TResult<Data?> {
        latency(500)
        val response = pairingResponses[pairingSecretHash]
        return TResult.Success.create(
            context,
            value = response?.let { org.tenshu.core.common.impl.TByteArrayDto(it) }
        )
    }

    private val offerResponses = mutableMapOf<String, SyncRelay.OfferResponseFetch.Found>()

    override suspend fun postOfferResponse(
        ownerId: String,
        responderDeviceId: String,
        pairingSecretHash: String,
        status: String,
        publicKeyA: ByteArray,
        payload: ByteArray?,
        hostSignature: ByteArray,
        offererDeviceId: String?,
        offererSigningKey: ByteArray?,
        masterDelegation: ByteArray?,
        epoch: Long?,
        admissionCertChain: ByteArray?,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        latency(100)
        if (offererDeviceId != null && offererSigningKey != null) {
            refuseUnderivedId(offererDeviceId, offererSigningKey)?.let { return it }
        }
        if (offerResponses.containsKey(pairingSecretHash)) {
            return TResult.Failure.create(
                context,
                org.tenshu.core.GenericError.create(context, "pairingSecretHash already consumed"),
            )
        }
        offerResponses[pairingSecretHash] = SyncRelay.OfferResponseFetch.Found(
            ownerId = ownerId,
            status = status,
            publicKeyA = publicKeyA,
            payload = payload,
        )
        return TResult.Success.create(context, value = null)
    }

    override suspend fun getOfferResponse(pairingSecretHash: String): SyncRelay.OfferResponseFetch {
        latency(100)
        return offerResponses[pairingSecretHash] ?: SyncRelay.OfferResponseFetch.NotFound
    }

    private val createdRealms = mutableListOf<String>()

    /** Test accessor: the ownerIds bootstrapped via [createRealm], in call order. */
    fun createdRealms(): List<String> = createdRealms.toList()

    private val admittedDevices = mutableListOf<Triple<String, String, ByteArray>>()

    /** Test accessor: the `(ownerId, deviceId, devicePublicKey)` admitted via [admitDevice]. */
    fun admittedDevices(): List<Triple<String, String, ByteArray>> = admittedDevices.toList()

    override suspend fun admitDevice(
        ownerId: String,
        newDeviceId: String,
        newDevicePublicKey: ByteArray,
        masterDelegation: ByteArray,
        epoch: Long,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        latency(500)
        refuseUnderivedId(newDeviceId, newDevicePublicKey)?.let { return it }
        admittedDevices.add(Triple(ownerId, newDeviceId, newDevicePublicKey))
        return TResult.Success.create(context, value = null)
    }

    override suspend fun createRealm(
        ownerId: String,
        masterPublicKey: ByteArray,
        firstDevicePublicKey: ByteArray,
        firstDeviceId: String,
        masterSelfSignature: ByteArray,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        latency(500)
        refuseUnderivedId(firstDeviceId, firstDevicePublicKey)?.let { return it }
        // Mock relay just acks + records the ownerId; tests that exercise the real
        // bootstrap-verification flow use the Ktor relay.
        createdRealms.add(ownerId)
        return TResult.Success.create(context, value = null)
    }

    override suspend fun recoveryChallenge(): TResult<org.tenshu.core.TByteArray> {
        latency(500)
        return TResult.Success.create(
            context,
            org.tenshu.core.common.impl.TByteArrayDto(byteArrayOf(0x01, 0x02))
        )
    }

    override suspend fun recoveryClaim(
        masterPublicKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        challenge: ByteArray,
        masterSignature: ByteArray
    ): TResult<Data?> {
        latency(500)
        refuseUnderivedId(newDeviceId, newDevicePublicKey)?.let { return it }
        return TResult.Success.create(context, value = null)
    }

    private val refusedUnderivedIds = mutableListOf<String>()

    /** Test accessor: ids a registration was refused for because they are not their key's derivation. */
    fun refusedUnderivedIds(): List<String> = refusedUnderivedIds.toList()

    /**
     * ⚠⚠ The real relay's workstream-B rule: a registration whose id is not `deviceIdForPublicKey` of
     * its key is refused. Mirrored here so a client path that pairs an id with the WRONG key (the A4 id
     * with a per-realm key, or the reverse) fails in unit tests, not only against a real relay.
     */
    private fun refuseUnderivedId(deviceId: String, publicKey: ByteArray): TResult<Data?>? =
        if (org.tenshu.sync.common.deviceIdMatchesKey(deviceId, publicKey)) {
            null
        } else {
            refusedUnderivedIds.add(deviceId)
            TResult.Failure.create(
                context,
                org.tenshu.core.GenericError.create(context, "device-id-not-key-derived: $deviceId"),
            )
        }

    override suspend fun fetchRealmEpoch(
        ownerId: String
    ): TResult<org.tenshu.core.number.TInteger> {
        latency(100)
        // Mirror the relay's authoritative source: count Device-targeted tombstones the
        // mock has accepted via [pushTombstone]. Lets `RevokeDevicePropagationTest`-style
        // multi-proxy fixtures observe the same OOB epoch view a recovering device would
        // see in production.
        val count = tombstones.count {
            it.second.targetEntityType.get() == org.tenshu.identity.Device.ID
        }.toLong()
        return TResult.Success.create(context, count.toTInteger(context))
    }

    private suspend fun latency(ms: Long) {
        if (simulateLatency) delay(ms)
    }
}
