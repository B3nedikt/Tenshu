package org.tenshu.inference.proxy

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.get
import org.tenshu.inference.ClassifyIntent
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.IntentCandidate
import org.tenshu.inference.IntentChoice

/**
 * [ClassifyIntent] — ask the installed backend which of the caller's intents an utterance is, and
 * **return nothing whenever the answer is not certainly one of them** (MQ-5).
 *
 * ⚠⚠ **Every refusal here is an empty list, and that is the opposite of [PhotosLikeImplementation]
 * on purpose.** Semantic search has no fallback, so its refusals had to be a typed error the user
 * could act on. This has a perfect fallback — the deterministic grammar — so an empty list is a
 * complete answer, the caller keeps working, and the user is never told about a model. The rule
 * that separates them: **throw when the user loses a capability, return empty when only the
 * implementation changes.**
 *
 * ⚠⚠ **The returned id is validated against the caller's own candidate set before it leaves here.**
 * A backend that answers `"deleteEverything"`, or `"findPhotos "` with a stray space, or a label
 * from a *previous* question, is indistinguishable from one that answered nothing — the caller
 * cannot be handed a string it did not offer. That check is what makes §5's "constrained decoding,
 * never free-text parsed" true in code rather than in prose, and it belongs on this side of the
 * boundary because the caller's own allowlist would otherwise be the only thing standing between a
 * model and an intent.
 */
internal class ClassifyIntentImplementation(
    private val registry: InferenceBackendRegistry,
    private val deviceId: String,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val utterance = (params?.get(ClassifyIntent.Params.UTTERANCE) as? TString)?.get().orEmpty()
        val candidates = (params?.get(ClassifyIntent.Params.CANDIDATES) as? TList<*>)
            ?.toList().orEmpty()
            .filterIsInstance<IntentCandidate>()

        if (utterance.isBlank() || candidates.isEmpty()) return empty()

        // ⚠ Consent before the model runs, as in `PhotosLikeImplementation` — reading someone's
        // sentence with a model is the inference they did or did not agree to, and discovering
        // that afterwards would be too late to matter.
        if (!executionContext.hasInferenceConsent(CAPABILITY, deviceId)) return empty()

        val backend = registry.backendFor(CAPABILITY) ?: return empty()

        val labels = candidates.map {
            IntentLabel(id = it.intentId.get(), description = it.description.get())
        }
        val chosen = backend.classifyIntent(utterance, labels) ?: return empty()

        // ⚠⚠ The allowlist check. `contains` on the ids the caller sent, not a prefix, not a
        // trim — an answer that needs repairing is an answer we do not understand.
        if (labels.none { it.id == chosen }) return empty()

        return SimpleTList(
            listOf(IntentChoice.create(context = executionContext.context, intentId = chosen))
        ) as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Data?> empty(): T = SimpleTList(emptyList<Data>()) as T

    private companion object {
        val CAPABILITY = InferenceCapability.QUERY_PLANNING
    }
}
