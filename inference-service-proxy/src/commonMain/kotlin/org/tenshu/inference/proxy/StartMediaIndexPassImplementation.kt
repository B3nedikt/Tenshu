package org.tenshu.inference.proxy

import org.tenshu.core.Data
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.inference.IndexPassRefused
import org.tenshu.inference.InferenceCapability

/**
 * [org.tenshu.inference.StartMediaIndexPass] — start a pass now, and say why not when it cannot.
 *
 * ⚠⚠ **Every refusal is checked HERE, before the runner, and that is the point of the op.** The pass
 * itself refuses by counting — `noBackend=635`, `deferred=true` — which is the right shape for a
 * scheduled job nobody is watching and useless to a person who just pressed a button: the call would
 * report success and the bar would never move. So the four conditions are read up front and turned
 * into [IndexPassRefused], with the two that name an action kept apart (install a model, switch it
 * on).
 *
 * ⚠ An already-running pass is a **success**: the user asked for indexing to be happening.
 */
internal class StartMediaIndexPassImplementation(
    private val runner: MediaIndexRunner,
    private val indexer: MediaIndexer,
    private val registry: InferenceBackendRegistry,
    private val deviceId: String,
    private val manualIndexLauncher: ManualIndexLauncher? = null,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        // ⚠ Pause first: it is the user's own "not right now", and the screen's answer to it is
        // Resume. Reporting a model or consent problem to someone who simply paused would be noise.
        if (executionContext.isIndexingPaused(deviceId)) {
            refuse(
                executionContext,
                IndexPassRefused.Reasons.PAUSED,
                "Indexing is paused on this device.",
            )
        }

        // ⚠⚠ Per capability, not two independent ORs. A device with a backend for X and consent
        // for Y satisfies "some backend" and "some consent" while having nothing it may actually do:
        // the pass then starts, refuses every file, and reports success. Unreachable while only one
        // capability is offered, and live the day a second one is.
        val runnable = INDEXING_CAPABILITIES.filter { registry.isAvailable(it) }
        if (runnable.isEmpty()) {
            refuse(
                executionContext,
                IndexPassRefused.Reasons.NO_BACKEND,
                "No model on this device can index photos.",
            )
        }

        // ⚠ Asked of the capabilities this device can actually run, so the reason the user is
        // given names the switch that would change the answer.
        val consented = runnable.any { executionContext.hasInferenceConsent(it, deviceId) }
        if (!consented) {
            refuse(
                executionContext,
                IndexPassRefused.Reasons.NO_CONSENT,
                "Indexing is switched off for this realm or this device.",
            )
        }

        // ⚠⚠ The MANUAL bar, not the scheduled one: charging and screen-off are not required of a
        // pass the user asked for — see `IndexingConditions.allowManualIndexing`.
        if (!indexer.allowed(manual = true)) {
            refuse(
                executionContext,
                IndexPassRefused.Reasons.NOT_READY,
                "This device is not in a state to index photos right now.",
            )
        }

        // ⚠ Every refusal above is asked HERE, so the screen still hears "paused" or "no model" at the
        // tap, whichever runs the pass.
        if (manualIndexLauncher != null) manualIndexLauncher.launch() else runner.start(executionContext, manual = true)
        return null as T
    }

    private fun refuse(
        executionContext: ExecutionContext,
        reason: String,
        message: String,
    ): Nothing = throw TenshuException(
        IndexPassRefused.create(
            context = executionContext.context,
            reason = reason,
            message = message,
        ),
    )

    private companion object {
        /**
         * ⚠ Any half is reason enough to run: labels, vectors and faces are separately switched and
         * separately backed (§32), so a device that can only do one still has work to do.
         *
         * ⚠⚠ `FACE_CLUSTERING` is the pass's third half and must be here: without
         * it a device holding only the People model, faces switched on, is told "no model" by
         * Index now — or "no consent" if SigLIP was installed with search off.
         */
        val INDEXING_CAPABILITIES = listOf(
            InferenceCapability.SEMANTIC_IMAGE_SEARCH,
            InferenceCapability.CONTENT_TAGGING,
            InferenceCapability.FACE_CLUSTERING,
        )
    }
}

/**
 * Runs a manual *Index now* pass somewhere other than in-process (`control-background-work-off-fgs.md`
 * §5.6.1). Android enqueues a WorkManager long-running worker, which awaits
 * [MediaIndexRunner.runManualToCompletion] under a `mediaProcessing` (API 35+) or `dataSync`
 * foreground notification with a Stop action. Must not block: the op returns once the work is enqueued.
 */
fun interface ManualIndexLauncher {
    fun launch()
}
