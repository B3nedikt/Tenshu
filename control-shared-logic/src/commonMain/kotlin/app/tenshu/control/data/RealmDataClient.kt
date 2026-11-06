package app.tenshu.control.data

import org.tenshu.core.common.proxy.BackgroundLiveness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.tenshu.core.Data
import org.tenshu.core.NotFoundError
import org.tenshu.core.Service
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.util.bestEffort
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.count
import org.tenshu.core.core.TContext
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.getOrNull
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.realmdata.RealmDataService
import org.tenshu.core.realmdata.deleteRealmData
import org.tenshu.core.relation.ServiceDataArrayBinding
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.resolve
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.listType
import org.tenshu.file.client.fileService
import org.tenshu.sync.GenericTaskSpec
import org.tenshu.sync.client.BackgroundTaskHandle
import org.tenshu.sync.client.taskService
import org.tenshu.sync.client.register
import org.tenshu.sync.client.withTask

/**
 * Thin control-shared accessor for the realm's data-type inventory — the type ids that declare
 * the `RealmData` marker (`docs/proposals/core-realm-data.md`) — and the destructive verb over
 * them (`docs/proposals/control-data-reset.md` R1). Keeps the Data ViewModel UI-only; the
 * presentation (label / icon / order) is applied separately by `realmDataPresentation(...)`.
 *
 * Enumeration is a pure schema walk over the in-process proxy's `SchemaRegistry`, so it is
 * synchronous and cheap — no proxy round-trip, no IO. [deleteAll] is the one operation call.
 */
interface RealmDataClient {
    /** Type ids of realm-data types, in declared (config-registration) order. */
    fun realmDataTypeIds(): List<String>

    /**
     * Whether a whole-type reset can actually reach [typeId]'s rows.
     *
     * ⚠ This is **not** a policy flag — it is derived from the schema, and it exists because
     * `DeleteRealmData` reaches rows only through the service-data relation array that holds
     * them (`SchemaProvider.serviceDataArraysHolding`; the store has no type-wide
     * enumeration). `File` has no such array — its rows live in the parent-folder tree — so a
     * reset of Files would delete **zero** rows and report success. Offering that button is
     * the same mistake §6 rejects for the per-device option: a control that silently does
     * nothing. Files are deleted in Drive, which owns the tree-aware delete op.
     */
    fun isResettable(typeId: String): Boolean

    /**
     * Whether [typeId] has at least one stored row right now: `true`/`false`, or `null` when it
     * cannot tell.
     *
     * The Data section lists only types that hold data. Every first-party service registers its
     * schema in Control whether or not its app is installed, so the bare schema walk
     * ([realmDataTypeIds]) shows *Routines* and *Runs* to someone who only uses Drive.
     *
     * ⚠ `null` means *show it*. Hiding a type the probe could not read would hide data the user
     * has, which is worse than showing a card for data they don't.
     */
    suspend fun hasData(typeId: String): Boolean?

    /**
     * Deletes **every** row of [typeId], realm-wide, and reports what happened.
     *
     * ⚠ Realm-wide with no per-device option, by design (§6 D3): a device-local delete would
     * be undone by the next pull. The op tombstones one row per top-level entity so the
     * deletion converges to every device.
     *
     * ⚠ Returns an outcome rather than throwing or returning `Unit`: the op returns a
     * `TResult`, and a `TResult.Failure` dropped silently loses data unnoticed. The caller
     * must render *something*, so the failure is data, not an exception.
     */
    suspend fun deleteAll(typeId: String): RealmDataResetOutcome
}

/** What a [RealmDataClient.deleteAll] actually did — always rendered, never dropped. */
sealed interface RealmDataResetOutcome {

    /** The reset ran; [count] top-level rows were deleted (and may legitimately be `0`). */
    data class Deleted(val count: Int) : RealmDataResetOutcome

    /**
     * The proxy refused the type: it is not a `RealmData` type (a `NotFoundError`). Reachable
     * only through a schema/registration mismatch between this client and its proxy — the ids
     * the UI offers come from the registry itself — so it is a distinct, honest state rather
     * than being folded into [Failed].
     */
    data object NotResettable : RealmDataResetOutcome

    /** Anything else: transport down, proxy error, realm locked. */
    data object Failed : RealmDataResetOutcome

    /**
     * The user stopped it (or the screen went away) part-way.
     *
     * ⚠ Not a [Failed]: what was deleted before the stop **stays** deleted and
     * converges to every device — the op tombstones each chunk before removing
     * it. The count is unknown here by construction (a cancelled call has no
     * return value), so the message says "some", never a number.
     */
    data object Stopped : RealmDataResetOutcome
}

/**
 * Production [RealmDataClient]: reads the in-process proxy's `SchemaRegistry` via the
 * client's environment. In Control the proxy is in-process, so the registry already has every
 * feature-service module registered (File and Workout included), and `RealmDataService`
 * resolves without ever crossing an IPC boundary — which is also why the destructive verb is
 * Control-only (§7).
 */
class TenshuRealmDataClient(
    private val client: TenshuClient,
    /**
     * The proxy's liveness, when the reset runs in Control's own process (Android): held under
     * [BackgroundLiveness.REALM_DATA_RESET] for the reset, which is what keeps the foreground service
     * up (`control-background-work-off-fgs.md` §5.2). `null` where no proxy-side liveness exists.
     */
    private val liveness: BackgroundLiveness? = null,
) : RealmDataClient {

    private val realmDataService: RealmDataService
        get() = client.getService(RealmDataService.ID)

    override fun realmDataTypeIds(): List<String> =
        client.environment.schemaRegistry.realmDataTypeIds()

    override fun isResettable(typeId: String): Boolean =
        client.environment.schemaRegistry.serviceDataArraysHolding(typeId).isNotEmpty()

    /**
     * Counts each collection that holds [typeId]'s rows and answers `true` at the first non-empty
     * one. `count()` on an unfiltered array reads only the array's member list, never the rows,
     * so this stays cheap on a 10k-workout store.
     *
     * The collections are the service-data arrays [isResettable] uses, plus Drive's root folder
     * for `File`: files live in the folder tree, not in an array, so the arrays alone would
     * always call Files empty.
     *
     * ⚠ Off the caller's dispatcher for the reason [withResetTask] gives: the caller is
     * `viewModelScope`, i.e. Main.
     */
    override suspend fun hasData(typeId: String): Boolean? = withContext(Dispatchers.Default) {
        // A binding we cannot turn into a list makes the answer "unknown" unless another
        // collection says `true` — it never makes the type empty.
        var unknown = false
        val collections = buildList {
            if (typeId == File.ID) add(client.fileService.defaultFileSystem.root.files)
            for (binding in client.environment.schemaRegistry.serviceDataArraysHolding(typeId)) {
                serviceDataList(binding)?.let(::add) ?: run { unknown = true }
            }
        }
        // No collection to look in: nothing says the type is empty, so don't claim it is.
        if (collections.isEmpty()) return@withContext null

        for (list in collections) {
            @Suppress("UNCHECKED_CAST")
            val size = runCatchingCancellable {
                (list as TList<Data>).count().resolve().getOrNull()?.get()
            }.getOrNull()
            when {
                size == null -> unknown = true
                size > 0 -> return@withContext true
            }
        }
        if (unknown) null else false
    }

    /**
     * The client-side list for one service-data array — the same chain a typed accessor builds
     * (`ServiceRo.serviceRelation`), assembled from the binding because the type is only known
     * at runtime.
     *
     * The service id comes from this process's own `SchemaRegistry`, so the service is
     * registered by construction; `getService` is guarded anyway, and a miss reads as
     * "can't tell" rather than crashing the Data screen.
     *
     * ⚠ Assumes the binding's `service` is a real service. A hand-registered relation may name
     * a sub-relation instead (`FileService.Relations.DEFAULT_FILE_SYSTEM` does, single-entity
     * so excluded); a list-shaped one would build the wrong chain here and read as
     * `null` (card shown) rather than empty.
     */
    private fun serviceDataList(binding: ServiceDataArrayBinding): TList<*>? {
        val serviceId = binding.service.get()
        val elementSchema = client.environment.schemaRegistry
            .getSchema(binding.elementTypeId) as? CompositeSchema
            ?: return null
        val service = runCatching { client.getService<Service>(serviceId) }.getOrNull()
        val ro = service as? RequestObject ?: return null
        val context = service as? TContext ?: return null
        return context.roProvider().createRequestObject<TList<Data>>(
            type = listType(elementSchema),
            requestChain = ro.requestChain.addReference(serviceId).addReference(binding.ref.get()),
            context = context,
        )
    }

    /**
     * Runs the reset as a registered [org.tenshu.sync.BackgroundTask]
     * (`core-task-composite.md` §5–§6, the `withImportTask` precedent).
     *
     * ⚠ The registration keeps the reset's writes on one sync lane. What keeps
     * the process alive is [liveness] ([BackgroundLiveness.REALM_DATA_RESET]):
     * a RUNNING task row does not promote Android's foreground service
     * (`control-background-work-off-fgs.md` §5.2). Without it a whole-type reset is an ordinary in-process call
     * that an OEM task-killer may cut in half (this device kills Control
     * routinely). Registration failure is **not** fatal — the reset still runs,
     * just unprotected, exactly as the import does.
     *
     * No heartbeat: the reset is a single op call, so there is no client-side
     * loop to tick from. The batched op is well inside the lease; if it ever
     * were not, the sweep marks the row FAILED and the delete still
     * completes (the lease guards liveness, not correctness).
     *
     * ### ⚠⚠ `Dispatchers.Default` is load-bearing, and it is `withTask`'s doing
     *
     * An in-process op runs on **the caller's dispatcher** whenever a `withTask`
     * block is in scope: `LocalTaskExecutor.executeAsyncCancellable` opens its
     * evaluation scope with `coroutineScope { }` — which inherits the caller's
     * context — and `ExecutionContext.execute` then dispatches the async impl
     * with `evaluationScope.async { }`. Called straight from `viewModelScope`
     * that resolves to **Main**, so wrapping the op in a task to protect it
     * silently cancels out the async implementation and puts the whole reset
     * back on the UI thread: the confirmation dialog freezes mid-reset and
     * Control dies to a 20 s `ProxyService` service ANR.
     *
     * ⚠ This is not specific to the reset — any long in-process op called from
     * a Main-dispatched coroutine inside `withTask` inherits it.
     */
    private suspend fun <T> withResetTask(block: suspend () -> T): T =
        withContext(Dispatchers.Default) {
            // ⚠⚠ The registered task below does not hold the foreground service — only
            // user-started work does, and this in-process call never reaches the IPC mark. The user
            // started it from Control's Data screen, so it holds its own user-started key.
            liveness?.holding(BackgroundLiveness.REALM_DATA_RESET) { withResetTaskOnThisDispatcher(block) }
                ?: withResetTaskOnThisDispatcher(block)
        }

    private suspend fun <T> withResetTaskOnThisDispatcher(block: suspend () -> T): T {
        val task: BackgroundTaskHandle<*>? = runCatchingCancellable {
            client.taskService.register(
                GenericTaskSpec.create(client.environment.tContext, RESET_TASK_KIND)
            )
        }.getOrNull()
        if (task == null) return block()
        return try {
            val result = withTask(task) { block() }
            bestEffort("RealmDataClient -> completing the reset task") { task.complete() }
            result
        } catch (e: CancellationException) {
            // The caller's scope is going away, so the terminal transition has
            // to ride NonCancellable — further suspension here would throw.
            withContext(NonCancellable) { runCatching { task.cancel() } }
            throw e
        } catch (t: Throwable) {
            bestEffort("RealmDataClient -> failing the reset task") { task.fail() }
            throw t
        }
    }

    override suspend fun deleteAll(typeId: String): RealmDataResetOutcome = withResetTask {
        when (val result = realmDataService.deleteRealmData(typeId)) {
            is TResult.Success -> RealmDataResetOutcome.Deleted(
                (result.value as? TInteger)?.get()?.toInt() ?: 0
            )

            is TResult.Failure -> if (result.error.id == NotFoundError.ID) {
                RealmDataResetOutcome.NotResettable
            } else {
                RealmDataResetOutcome.Failed
            }

            else -> RealmDataResetOutcome.Failed
        }
    }

    private companion object {
        /** Task kind for the liveness row — mirrors Fit's `fit/health-import`. */
        const val RESET_TASK_KIND = "control/realm-data-reset"
    }
}

/**
 * In-memory [RealmDataClient] for previews / tests. [resettable] defaults to *every* declared
 * type so a preview shows the action; [deleted] fixes what [deleteAll] reports.
 */
class MockRealmDataClient(
    private val typeIds: List<String> = emptyList(),
    private val resettable: Set<String> = typeIds.toSet(),
    /** Types [hasData] reports as holding rows; any other type reports empty. */
    private val withData: Set<String> = typeIds.toSet(),
    /** `suspend` so a test can model a long reset (and have it cancelled) — see the *Stop* path. */
    private val outcome: suspend (String) -> RealmDataResetOutcome = { RealmDataResetOutcome.Deleted(0) },
) : RealmDataClient {

    /** Type ids passed to [deleteAll], in call order — for asserting a reset actually fired. */
    val deleted: MutableList<String> = mutableListOf()

    override fun realmDataTypeIds(): List<String> = typeIds

    override fun isResettable(typeId: String): Boolean = typeId in resettable

    override suspend fun hasData(typeId: String): Boolean? = typeId in withData

    override suspend fun deleteAll(typeId: String): RealmDataResetOutcome {
        deleted += typeId
        return outcome(typeId)
    }
}
