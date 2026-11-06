package org.tenshu.core.common.app.proxy

import org.tenshu.core.Data
import org.tenshu.core.TenshuException
import org.tenshu.core.app.AppRegistration
import org.tenshu.core.app.OpenOutcome
import org.tenshu.core.app.OpenTarget
import org.tenshu.core.app.OpenTargetRequest
import org.tenshu.core.app.OpenTargetUnavailable
import org.tenshu.core.common.app.HandlerTable
import org.tenshu.core.common.proxy.ClientConnection
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.entity.DataId
import org.tenshu.core.get

/**
 * [OpenTarget] — resolve the row's type to an app, check it is installed **here**, and hand it over
 * (`control-cross-app-open.md` TT-1a).
 *
 * ⚠⚠ **TT-1a ships the resolution and no delivery**, deliberately. [delivery] is unbound in every
 * production build until TT-1b, so a real call resolves correctly and then refuses with
 * [OpenTargetUnavailable.Reasons.NO_DELIVERY]. That is the honest shape of a half-built feature —
 * the alternative, returning an [OpenOutcome] naming an app that was never told anything, would be
 * a success claim for something that did not happen, which is the one thing this whole doc's
 * CO-D7 forbids.
 *
 * ⚠ Registered in production anyway rather than held back: `inference-service.md` §17 is the
 * standing lesson that a service ships when it is in the proxy's schema list, not when its tests
 * are green. An op that is reachable and refuses honestly is discoverable; an op that is absent
 * fails as `Could not handle protocol` a long way from the cause.
 */
internal class OpenTargetImplementation(
    private val delivery: TargetDelivery?,
    private val deviceId: () -> String?,
    private val preferredHandlerFor: (ExecutionContext, type: String) -> String?,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val target = params?.get(OpenTarget.Params.TARGET) as? DataId
            ?: unavailable(
                executionContext,
                OpenTargetUnavailable.Reasons.NO_HANDLER,
                "No target was given.",
            )

        val type = target.type.get()

        // 1. Which apps can show this, best first? ⚠ The compiled-in table today; the app's
        //    declared `handles` set and the user's primary-handler choice at TT-4 (CO-D2). Callers
        //    never see the change.
        val candidates = withUserChoiceFirst(executionContext, type, HandlerTable.handlersFor(type))
        if (candidates.isEmpty()) {
            unavailable(
                executionContext,
                OpenTargetUnavailable.Reasons.NO_HANDLER,
                "No app opens $type.",
            )
        }

        // 2. Which of them is installed HERE? ⚠⚠ A store read over `AppRegistration`, which is
        //    keyed `(appId, deviceId)` — never an OS probe, which would be a second source of truth
        //    able to disagree with Control's own Apps screen (CO-D2).
        //
        // ⭐ The **first** installed candidate wins, so a preference order degrades instead of
        //    failing: a user with Maps but not Timeline still gets their place opened. ⚠ The order
        //    is the table's, never the store's — iterating registrations instead would make the
        //    handler depend on install order, which is not a preference anyone expressed.
        val device = deviceId()
            ?: unavailable(
                executionContext,
                OpenTargetUnavailable.Reasons.NOT_INSTALLED_HERE,
                "This device has no identity yet, so nothing is registered on it.",
            )
        val appId = candidates.firstOrNull { isRegisteredHere(executionContext, it, device) }
            ?: unavailable(
                executionContext,
                OpenTargetUnavailable.Reasons.NOT_INSTALLED_HERE,
                // ⚠ Names every candidate, because "install Timeline" and "install Timeline or
                // Maps" are different instructions and only the second is true.
                "None of ${candidates.joinToString()} is installed on this device.",
            )

        // 3. Hand it over — or say plainly that this build cannot (TT-1a).
        val deliverer = delivery
            ?: unavailable(
                executionContext,
                OpenTargetUnavailable.Reasons.NO_DELIVERY,
                "Opening apps is not wired on this platform yet.",
            )

        val request = OpenTargetRequest.create(context = executionContext.context, target = target)
        // ⚠⚠ `executionContext.clientConnection` is the CALLER's connection, not the handler's —
        // one per session, minted per client id. That is what lets a cold-start ticket be handed
        // back to the app the user tapped in without inventing a second channel to reach it.
        val outcome = deliverer.deliver(
            appId = appId,
            request = request,
            caller = executionContext.clientConnection,
        )
            ?: unavailable(
                executionContext,
                OpenTargetUnavailable.Reasons.LAUNCH_FAILED,
                "$appId did not become reachable.",
            )

        return OpenOutcome.create(
            context = executionContext.context,
            appId = appId,
            launchTicketId = outcome.launchTicketId.orEmpty(),
        ) as T
    }

    /**
     * The user's chosen handler moved to the front (`PrimaryHandler`, CO-D2/TT-4).
     *
     * ⚠⚠ **A re-ordering, never a replacement.** The rest of the list stays, so the installed-here
     * check can still fall through when the preferred app is not on this device — choosing
     * Maps for places on a phone that later loses Maps should degrade, not break.
     *
     * ⚠ A preference naming an app that is not a candidate is IGNORED rather than honoured: the
     * table is a claim that the app has a receiving route, and a stale setting must not be able to
     * send a row somewhere that cannot show it.
     */
    private fun withUserChoiceFirst(
        executionContext: ExecutionContext,
        type: String,
        candidates: List<String>,
    ): List<String> {
        val chosen = preferredHandlerFor(executionContext, type)?.takeIf { it in candidates }
            ?: return candidates
        return listOf(chosen) + candidates.filterNot { it == chosen }
    }

    /**
     * ⚠ `Removed` is not installed. `RegisterAppImplementation` keeps a tombstone row rather than
     * deleting it (§5.1), so a presence check that only asked "is there a row" would happily open
     * an app the user removed.
     */
    private fun isRegisteredHere(
        executionContext: ExecutionContext,
        appId: String,
        deviceId: String,
    ): Boolean {
        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = AppRegistration.ID,
            keyValue = AppRegistration.compositeKey(appId = appId, deviceId = deviceId),
        )
        val row = executionContext.database.getData(dataId) as AppRegistration?
        return row?.state?.get() == AppRegistration.State.ACTIVE
    }

    private fun unavailable(
        executionContext: ExecutionContext,
        reason: String,
        message: String,
    ): Nothing = throw TenshuException(
        OpenTargetUnavailable.create(
            context = executionContext.context,
            reason = reason,
            message = message,
        ),
    )
}

/**
 * How an [OpenTargetRequest] actually reaches its app — the seam TT-1b fills in per platform.
 *
 * ⚠⚠ **Not bound in TT-1a.** Keeping the seam empty is what makes that slice reviewable: the
 * resolution, the registration check and the four refusals can be read and tested without any
 * platform code in the diff.
 *
 * ⚠ Returns `null` for "it never became reachable", which the caller turns into
 * [OpenTargetUnavailable.Reasons.LAUNCH_FAILED]. A `Delivered` result must mean the request was
 * handed over, never merely that a launch was requested (CO-D9).
 */
interface TargetDelivery {

    /**
     * @param caller the connection back to the app that asked — the carrier for a launch ticket
     *   when the handler is cold, because the proxy may not start an activity itself (CO-D3).
     */
    suspend fun deliver(
        appId: String,
        request: OpenTargetRequest,
        caller: ClientConnection,
    ): Delivered?

    /**
     * @param launchTicketId `null` when the request was handed over and there is nothing left to
     *   do; otherwise the id of a ticket already pushed to [deliver]'s `caller`, which the caller
     *   must fire to finish the open. See [OpenOutcome.launchTicketId].
     */
    data class Delivered(val launchTicketId: String?)
}

/**
 * The `OpenTarget` implementation, for the **per-platform** proxy wiring.
 *
 * ⚠⚠ Not in `core-common`'s own `implementationFactory(id)`, which takes an id and nothing else:
 * this op needs the device identity now and a platform launcher at TT-1b, so it belongs where those
 * exist — beside `inferenceServiceProxyImplementationFactory` and friends in `control-shared-proxy`.
 *
 * @param delivery `null` until TT-1b binds one; the op then refuses with `NO_DELIVERY` rather than
 *   claiming an open that did not happen.
 * @param preferredHandlerFor the user's primary-handler choice (TT-4). ⚠ Injected rather than read
 *   here because it lives in `:preference-service`, which `:core-common` must not depend on — the
 *   per-platform wiring supplies it, exactly as it supplies [deviceId]. Defaults to "no choice",
 *   which is the compiled-in order.
 */
fun appOpenTargetImplementationFactory(
    id: String,
    deviceId: () -> String?,
    delivery: TargetDelivery? = null,
    preferredHandlerFor: (ExecutionContext, type: String) -> String? = { _, _ -> null },
): OperationImplementation? = when (id) {
    OpenTarget.ID -> OpenTargetImplementation(
        delivery = delivery,
        deviceId = deviceId,
        preferredHandlerFor = preferredHandlerFor,
    )

    else -> null
}
