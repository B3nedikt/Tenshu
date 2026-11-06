package org.tenshu.core.common.app.proxy

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.app.AnnounceSession
import org.tenshu.core.common.schema.ServiceVersionMismatch
import org.tenshu.core.common.schema.SessionAdmission
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.currentVersionFloors
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.schema.proxyHostIdentity
import org.tenshu.core.common.schema.toMap
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read

/**
 * Session admission (`core-schema-evolution.md` §6.8, `core-version-compatibility.md` §4.7):
 * compare the client's two announced vectors against this proxy's own — **assembled by walking
 * the registered module graph** ([currentVersionVector]): each registered `ServiceSchema` carries
 * its version, floors derive from the registered migration chains, and the module `include(...)`
 * edges supply the dependency closure. No separate version registry exists to drift.
 *
 * Per service, each direction judged on the number that concerns it (§4.3):
 *  - `requires > proxyCurrent` → [SessionAdmission.updateProxy] ("update Control");
 *  - `current < proxyFloor`    → [SessionAdmission.updateClient] ("this app needs updating");
 *  - a service the proxy doesn't know at all → reported with `proxyFloor = proxyCurrent = 0`
 *    (the proxy is behind by construction, whatever the client requires).
 * The core slot compares the same way under the sentinel id [CORE_SERVICE_ID].
 * Proxy services the client did not announce are NOT mismatches — the client
 * simply doesn't use them.
 *
 * ⚠ Using `current` for the floor and `requires` for the ceiling is the point of carrying both: a
 * client whose requirement sits below the proxy's floor while its current version is above it is
 * compatible, and comparing one number both ways would refuse it (§4.7).
 *
 * A refusal is a *successful typed reply* (§6.3): the [SessionAdmission] is
 * returned either way, and the admitted vectors are recorded on the session's
 * [org.tenshu.core.common.proxy.SessionAnnouncement] (§6.4) — the state the
 * reject-if-silent dispatcher gate (§6.5) and later per-session polyfill
 * routing (§9.2) read.
 */
internal class AnnounceSessionImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val appId: TString = params.read(AnnounceSession.Params.APP_ID)
        val current = params.read<VersionVector>(AnnounceSession.Params.CURRENT)
        val requires = params.read<VersionVector>(AnnounceSession.Params.REQUIRES)

        val context = executionContext.context
        val provider = executionContext.schemaProvider
        val proxyVector = provider.currentVersionVector(context)
        val wireFloors = provider.currentVersionFloors(context)

        val proxyCurrent = proxyVector.toMap()
        val proxyFloors = wireFloors.toMap()
        val clientCurrent = current.toMap()
        val clientRequires = requires.toMap()

        var updateProxy = false
        var updateClient = false
        val mismatches = buildList {
            fun judge(serviceId: String, cur: Int, req: Int, floor: Int, proxyCur: Int) {
                val proxyBehind = req > proxyCur
                val clientBehind = cur < floor
                if (!proxyBehind && !clientBehind) return
                updateProxy = updateProxy || proxyBehind
                updateClient = updateClient || clientBehind
                add(ServiceVersionMismatch.create(context, serviceId, cur, req, floor, proxyCur))
            }
            judge(
                CORE_SERVICE_ID,
                cur = current.coreVersion.get().toInt(),
                req = requires.coreVersion.get().toInt(),
                floor = wireFloors.coreVersion.get().toInt(),
                proxyCur = proxyVector.coreVersion.get().toInt(),
            )
            // ⚠ The union of both vectors' services: a service present in only one of them is
            // still judged, with the missing side read as that side's own current — the safest
            // reading for a client that (incorrectly) left it out of one vector.
            for (serviceId in (clientCurrent.keys + clientRequires.keys).sorted()) {
                val cur = clientCurrent[serviceId] ?: clientRequires.getValue(serviceId)
                val req = clientRequires[serviceId] ?: cur
                judge(serviceId, cur, req, proxyFloors[serviceId] ?: 0, proxyCurrent[serviceId] ?: 0)
            }
        }

        val admitted = !updateProxy && !updateClient
        if (admitted) {
            executionContext.sessionAnnouncement.announcedVector = current
            executionContext.sessionAnnouncement.announcedRequirement = requires
            executionContext.sessionAnnouncement.announcedAppId = appId.get()
        }

        val host = context.proxyHostIdentity()
        return SessionAdmission.create(
            context = context,
            updateProxy = updateProxy,
            updateClient = updateClient,
            proxyVector = proxyVector,
            wireFloors = wireFloors,
            mismatches = mismatches,
            proxyAppId = host?.appId.orEmpty(),
            proxyAppVersion = host?.appVersion.orEmpty(),
        ) as T
    }

    companion object {
        /**
         * Sentinel service id for the core slot in a mismatch report —
         * `:core` registers no `ServiceSchema`, its version rides the
         * vector's dedicated `coreVersion` field (§4).
         *
         * ⚠ Aliases the canonical
         * [org.tenshu.core.common.schema.migration.CORE_SERVICE_ID] rather than
         * repeating the literal: E2j-2 made the same id addressable by the store
         * migrator (`schemaMigration(CORE_SERVICE_ID, …)`), and two spellings of
         * the core slot could drift apart.
         */
        const val CORE_SERVICE_ID = org.tenshu.core.common.schema.migration.CORE_SERVICE_ID
    }
}
