package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TArray
import org.tenshu.core.TResult
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.request.RequestChain
import org.tenshu.sync.ApprovePairingOffer
import org.tenshu.sync.CancelPairingOffer
import org.tenshu.sync.DenyPairingOffer
import org.tenshu.sync.GetOfferStatus
import org.tenshu.sync.OfferPairing
import org.tenshu.sync.RespondToPairingOffer
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Regression guard for the inverted-direction pairing ops
 * (`sync-pairing-inverted-direction.md` §3.3). The schemas + proxy implementations for the
 * offer flow all existed, but the client-side operation RO factories were never registered in
 * `syncServiceClientModule` — so `findOperation<OfferPairing>(OfferPairing.ID)` returned null
 * and the apex "Show a code" flow failed with `Operation tenshu/sync/service/offerPairing not
 * found`. `OfferPairingRoundTripTest` dispatches the implementations directly, bypassing this
 * client-RO resolution path, which is why it never caught the gap.
 *
 * Each op is dispatched through the real receiver-derived `findOperation` lookup — the same
 * path the SDK's `SyncService.offerPairing(...)` extension takes — over a registry wired with
 * both the common (schema) and client (RO factory) modules.
 */
class OfferPairingOperationResolutionTest {

    private val registry = createCoreTestRegistry().apply {
        registerConfig(syncServiceCommonModule)
        registerConfig(syncServiceClientModule)
    }

    private val stubChain = object : RequestChain {
        override fun addReference(id: String) = this
        override fun addArray(array: TArray) = this
        override fun addNestedArray(block: (RequestChain) -> RequestChain) = this
        override fun getCallstack(): List<Data> = emptyList()
        override suspend fun getResult(): TResult<Data> = error("stub")
    }

    private inline fun <reified T : Operation> assertResolves(operationId: String) {
        val resolved = registry.findOperation<T>(
            operationId = operationId,
            requestChain = stubChain,
            context = registry.tContext,
        )
        assertNotNull(
            resolved,
            "client module must register an RO factory for $operationId so the SDK can " +
                    "dispatch it — otherwise findOperation returns null (\"Operation … not found\")",
        )
    }

    @Test
    fun offerPairing_resolvesThroughClientModule() =
        assertResolves<OfferPairing>(OfferPairing.ID)

    @Test
    fun getOfferStatus_resolvesThroughClientModule() =
        assertResolves<GetOfferStatus>(GetOfferStatus.ID)

    @Test
    fun cancelPairingOffer_resolvesThroughClientModule() =
        assertResolves<CancelPairingOffer>(CancelPairingOffer.ID)

    @Test
    fun respondToPairingOffer_resolvesThroughClientModule() =
        assertResolves<RespondToPairingOffer>(RespondToPairingOffer.ID)

    @Test
    fun approvePairingOffer_resolvesThroughClientModule() =
        assertResolves<ApprovePairingOffer>(ApprovePairingOffer.ID)

    @Test
    fun denyPairingOffer_resolvesThroughClientModule() =
        assertResolves<DenyPairingOffer>(DenyPairingOffer.ID)
}
