package org.tenshu.inference.proxy

import kotlinx.coroutines.CancellationException
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.get
import org.tenshu.inference.AvailableModel
import org.tenshu.inference.DeleteModel
import org.tenshu.inference.DownloadModel
import org.tenshu.inference.InstalledModel
import org.tenshu.inference.ModelUnavailable
import org.tenshu.inference.proxy.catalog.InstalledIndex
import org.tenshu.inference.proxy.catalog.ModelCatalogSource
import org.tenshu.inference.proxy.catalog.ModelCatalogUnavailableException
import org.tenshu.inference.proxy.catalog.ModelDownloader
import org.tenshu.inference.proxy.catalog.ModelIntegrityException
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.catalog.ModelStoreChanges
import org.tenshu.inference.proxy.catalog.ModelStoreNotOwnedException
import org.tenshu.inference.proxy.catalog.ModelStoreRefusal
import org.tenshu.inference.proxy.catalog.UnknownModelException

/**
 * The four model operations — the surface Control's model screen reads through, over the model
 * store, catalog source and downloader (`inference-service.md` §22, §23).
 *
 * ⚠⚠ **This is the route by which a model reaches a device; without it a shipped backend is
 * unreachable** (§19.2 step 1c). Nothing else calls the downloader; there is deliberately no
 * automatic fetch, because IN-D3 keeps inference off by default and IN-D10 requires the user to see
 * what a model costs first.
 */
private fun unavailable(
    executionContext: ExecutionContext,
    reason: String,
    message: String,
): Nothing = throw TenshuException(
    ModelUnavailable.create(
        context = executionContext.context,
        reason = reason,
        message = message,
    )
)

/**
 * ⚠ The two refusals keep two reasons: only one of them is about another Control, and a screen that
 * tells someone to quit a process that does not exist has sent them nowhere.
 */
private fun storeRefused(executionContext: ExecutionContext, e: ModelStoreNotOwnedException): Nothing =
    unavailable(
        executionContext,
        when (e.refusal) {
            is ModelStoreRefusal.OwnedElsewhere -> ModelUnavailable.Reasons.STORE_NOT_OWNED
            is ModelStoreRefusal.LockUnavailable -> ModelUnavailable.Reasons.STORE_LOCK_UNAVAILABLE
        },
        e.refusal.message,
    )

/**
 * **The one place a failure of the model store or of a downloader becomes a [ModelUnavailable]**,
 * for every operation here. Run the part of an operation that can fail inside it.
 *
 * ⚠⚠ One mapping, not a list of catches per operation: a per-operation list drifts, and an
 * operation that caught only the store's refusal would let a download journal it could not read,
 * or a teardown under it, reach the executor's catch-all with no reason at all while another
 * operation typed the very same exceptions. An exception type added later is mapped here once, or
 * falls to the last line for all of them alike.
 *
 * | Thrown | Reason |
 * |---|---|
 * | [ModelCatalogUnavailableException] | `NO_CATALOG` |
 * | [UnknownModelException] | `UNKNOWN_MODEL` |
 * | [ModelIntegrityException] | `INTEGRITY_FAILED`: asking again fetches the same bytes |
 * | [ModelStoreNotOwnedException] | `STORE_NOT_OWNED` / `STORE_LOCK_UNAVAILABLE`: it cannot work while this process lives |
 * | a [TenshuException] | itself: already typed by whoever threw it |
 * | a cancellation | itself: it is not a failure |
 * | **anything else** | `TRANSPORT_FAILED`, with its own sentence |
 *
 * ⚠ The last row is the **retryable** bucket, and it is where everything without a reason of
 * its own lands: a refused GET, a dropped connection, an index or a download journal that
 * could not be read, too little free space, no transport, a download cancelled or its runtime
 * torn down. Mapping an unknown failure into a non-retryable bucket would deny the user the
 * button that would have worked. ⚠ Some of them deserve a reason of their own, and adding one
 * is a wire-vocabulary change that has not been made (`tech-debt.md`).
 */
private inline fun <T> typed(executionContext: ExecutionContext, whatDidNotHappen: String, fallible: () -> T): T =
    try {
        fallible()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TenshuException) {
        throw e
    } catch (e: ModelCatalogUnavailableException) {
        unavailable(executionContext, ModelUnavailable.Reasons.NO_CATALOG, e.message.orEmpty())
    } catch (e: UnknownModelException) {
        unavailable(executionContext, ModelUnavailable.Reasons.UNKNOWN_MODEL, e.message.orEmpty())
    } catch (e: ModelIntegrityException) {
        unavailable(executionContext, ModelUnavailable.Reasons.INTEGRITY_FAILED, e.message.orEmpty())
    } catch (e: ModelStoreNotOwnedException) {
        storeRefused(executionContext, e)
    } catch (e: Exception) {
        unavailable(executionContext, ModelUnavailable.Reasons.TRANSPORT_FAILED, e.message ?: whatDidNotHappen)
    }

private const val INSTALLED_NOT_READ = "The installed models could not be read."

/** What `InstalledModels` says of bytes that are no index. `internal` for its test. */
internal const val INSTALLED_NOT_AN_INDEX =
    "What is kept where the installed models are recorded is not a record this version wrote."

private fun modelIdOf(params: Map<String, Data>?, key: String): String =
    params?.get(key)?.let { (it as TString).get() }.orEmpty()

/**
 * [org.tenshu.inference.AvailableModels] — the verified catalog, filtered to what this build can run.
 *
 * ⚠⚠ A `null` catalog throws `NO_CATALOG` rather than answering with an empty list. **This is the
 * path every device takes today**: with no release key pinned the source refuses everything without
 * even making a request, and an empty list would render as "we publish no models" — a sentence
 * about us that is false, in place of one about the device that is true.
 *
 * ⚠ An empty list is still possible and means something different: the catalog *was* read and
 * offers this device nothing it can run.
 */
internal class AvailableModelsImplementation(
    private val catalogSource: ModelCatalogSource,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val catalog = catalogSource.load()
            ?: unavailable(
                executionContext,
                ModelUnavailable.Reasons.NO_CATALOG,
                "No model catalog this device can trust: none published, or no signing key pinned.",
            )

        val rows = catalog.models.map { entry ->
            AvailableModel.create(
                context = executionContext.context,
                modelId = entry.modelId,
                // ⚠ A nameless entry is still installable; refusing one would let a missing display
                // string cost a user their model.
                name = entry.name.ifBlank { entry.modelId },
                modelVersion = entry.modelVersion,
                capabilities = entry.capabilities.map { it.id },
                // ⚠ The whole checkpoint, summed across artifacts — what installing actually costs.
                byteSize = entry.byteSize,
                licence = entry.licence,
            )
        }
        return SimpleTList(rows) as T
    }
}

/**
 * [org.tenshu.inference.InstalledModels] — what this device holds.
 *
 * ⚠ Reads proxy-local state only, so unlike its sibling it never throws `NO_CATALOG`: "this device
 * has no models" is true and useful even when nothing can be fetched, and it is the answer a screen
 * needs in order to offer *delete* while offline.
 *
 * ⚠⚠ An empty list is **nothing is installed** and nothing else. It asks [ModelStore.read], so
 * bytes that are no index, and an index that could not be read, both fail `TRANSPORT_FAILED` with
 * their sentence: answered as no records, a screen offers a download whose commit is then refused.
 */
internal class InstalledModelsImplementation(
    private val store: ModelStore,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        // ⚠ Decided here and not inside [typed]'s lambda: `ios-on-device-inference.md` §14.18.10.
        val held = when (val index = typed(executionContext, INSTALLED_NOT_READ) { store.read() }) {
            is InstalledIndex.Records -> index.models
            is InstalledIndex.NotAnIndex ->
                unavailable(executionContext, ModelUnavailable.Reasons.TRANSPORT_FAILED, INSTALLED_NOT_AN_INDEX)
            is InstalledIndex.Unreadable ->
                unavailable(executionContext, ModelUnavailable.Reasons.TRANSPORT_FAILED, index.why.ifBlank { INSTALLED_NOT_READ })
        }
        val rows = held.map { installed ->
            InstalledModel.create(
                context = executionContext.context,
                modelId = installed.modelId,
                modelVersion = installed.modelVersion,
                byteSize = installed.byteSize,
                labelSetVersion = installed.labelSetVersion,
                licence = installed.licence,
            )
        }
        return SimpleTList(rows) as T
    }
}

/**
 * [org.tenshu.inference.DownloadModel] — fetch a checkpoint into the canonical store.
 *
 * ⚠⚠ Every failure the downloader can raise becomes a **distinct** [ModelUnavailable] reason,
 * because a screen owes each a different sentence and two of them a different button: retrying a
 * transport failure is sensible, retrying an integrity failure fetches the same wrong bytes from
 * the same CDN.
 */
internal class DownloadModelImplementation(
    private val downloader: ModelDownloader,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val modelId = modelIdOf(params, DownloadModel.Params.MODEL_ID)
        if (modelId.isBlank()) {
            unavailable(
                executionContext,
                ModelUnavailable.Reasons.UNKNOWN_MODEL,
                "No model id was given.",
            )
        }

        val installed = typed(executionContext, "The download did not complete.") { downloader.download(modelId) }

        return InstalledModel.create(
            context = executionContext.context,
            modelId = installed.modelId,
            modelVersion = installed.modelVersion,
            byteSize = installed.byteSize,
            labelSetVersion = installed.labelSetVersion,
            licence = installed.licence,
        ) as T
    }
}

/**
 * [org.tenshu.inference.DeleteModel] — remove a checkpoint from this device.
 *
 * ⚠⚠ **Success does not mean the disk is free.** The model stops being served at once;
 * `DefaultModelStore` releases the **archives** when the proxy process next starts, because it
 * never deletes ahead of an index write that may not be on disk. A caller must not report that
 * space as reclaimed. ⚠ On iOS the compiled towers are another matter: they are deleted before
 * the record, inside the removal (`ModelPreparer.evicting`), so success there means they are gone
 * now, and a delete that could not remove them fails, typed, with the model still installed.
 *
 * ⚠⚠ **Exactly one of IN-D10's two questions.** This removes the model; it does not touch consent, so a
 * delete cannot silently disable the capability and switching the capability off cannot silently
 * delete anything. Keeping them apart in the *operations* is what makes the screen's promise
 * enforceable rather than a matter of remembering.
 *
 * ⚠ Removing something absent fails rather than succeeding quietly — a delete that reports success
 * having found nothing hides a store that is not where the caller thinks it is.
 */
internal class DeleteModelImplementation(
    private val store: ModelStore,
    /** ⚠ What must stand down before the record goes — see [ModelStoreChanges]. */
    private val changes: ModelStoreChanges = ModelStoreChanges.Direct,
    /**
     * ⚠ Whose download of this model must be ended first, where a download outlives the process
     * and would install the model again — see [ModelDownloader.withoutDownloadOf]. `null`
     * changes nothing.
     */
    private val downloader: ModelDownloader? = null,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val modelId = modelIdOf(params, DeleteModel.Params.MODEL_ID)
        // ⚠ All of it inside [typed]: the index read, the downloader's part, the removal.
        typed(executionContext, "The model was not removed.") {
            if (store.installed().none { it.modelId == modelId }) {
                // ⚠⚠ *No record* from bytes that are not an index is not *not installed*: it was
                // NOT_INSTALLED, which the Models screen shows as "Removed" — on Android and iOS
                // alike — over a model that may well be there. ⚠ Not [ModelStore.read]: that
                // answers a refused store as unreadable, and this keeps its own reason.
                if (!store.holdsAnIndex()) unavailable(executionContext, ModelUnavailable.Reasons.TRANSPORT_FAILED, INSTALLED_NOT_AN_INDEX)
                unavailable(
                    executionContext,
                    ModelUnavailable.Reasons.NOT_INSTALLED,
                    "No model '$modelId' is installed on this device.",
                )
            }
            val remove: suspend () -> Unit = { store.remove(modelId) }
            // ⚠ The download first, the store second, and nothing of the downloader in between.
            // ⚠ The downloader runs the wrapper: what it must ask is asked inside it.
            if (downloader != null) downloader.withoutDownloadOf(modelId, changes, remove) else changes.changing(remove)
        }
        return null as T
    }
}
