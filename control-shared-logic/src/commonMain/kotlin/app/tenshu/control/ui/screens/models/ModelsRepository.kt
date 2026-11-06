package app.tenshu.control.ui.screens.models

import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.CoroutineContext
import app.tenshu.control.inference.ForegroundIndexStanding
import app.tenshu.control.inference.HeldModel
import app.tenshu.control.inference.StoredIndex
import app.tenshu.control.inference.IndexCoverage
import app.tenshu.control.inference.IndexCoverageSource
import app.tenshu.control.inference.IndexProgressSource
import app.tenshu.control.inference.ModelActionResult
import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelStoreAccessSource
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.OfferedModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.fold
import org.tenshu.core.get
import org.tenshu.core.getOrNull
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.identity.client.identityService
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus
import org.tenshu.inference.AvailableModel
import org.tenshu.inference.IndexPassRefused
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.InstalledModel
import org.tenshu.inference.ModelUnavailable
import org.tenshu.inference.availableModels
import org.tenshu.inference.capabilityIds
import org.tenshu.inference.client.inferenceService
import org.tenshu.inference.deleteModel
import org.tenshu.inference.downloadModel
import org.tenshu.inference.IndexingPaused
import org.tenshu.inference.downloadProgress
import org.tenshu.inference.installedModels
import org.tenshu.inference.startMediaIndexPass
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.read
import org.tenshu.preference.client.readForDevice
import org.tenshu.preference.client.readOnce
import org.tenshu.preference.client.readOnceForDevice
import org.tenshu.preference.client.set
import org.tenshu.preference.client.setForDevice
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a Models screen is given: the list, and the two things about it that are not a row's.
 *
 * ⚠ One list, [availability]'s. A second copy of the rows beside it could disagree with it.
 */
data class ModelsState(
    val availability: ModelsAvailability,
    /**
     * The [org.tenshu.inference.InferenceCapability] ids each listed model serves, by model id.
     * ⚠ The catalog's, so empty for a model it does not list; ids this build does not know are kept.
     */
    val serves: Map<String, List<String>> = emptyMap(),
    /**
     * Why this device can show what is installed and change nothing, for as long as the process
     * runs, or `null` when it can. ⚠ A standing state: the screen says it up front and takes the
     * download and remove buttons away rather than letting them fail. ⚠ Also set by a download
     * or a removal the store refused, and then never cleared.
     */
    val storeReadOnly: StoreReadOnly? = null,
    /**
     * The models being removed. ⚠⚠ The repository's, like a download: a removal goes on after
     * the screen that asked for it has closed, and the screen that opens next must not offer
     * that row anything. ⚠ It qualifies a row; whether the model is installed is the row's.
     */
    val removing: Set<String> = emptySet(),
    /**
     * How the last removal of each model **ended**, as the call answered it. ⚠⚠ Set in the same
     * step that takes the model out of [removing], so whoever watches a removal end is told what
     * the removal said, and never has to infer it from whether the list still holds the model.
     * ⚠ Read only at that step: afterwards it says nothing of what is installed.
     */
    val removalEndings: Map<String, ModelDeleteOutcome> = emptyMap(),
)

/**
 * What a platform's [ModelsRepository] can do. ⚠⚠ **What is false is not drawn**: a button for
 * an action the repository does not have is a claim the screen cannot keep.
 */
data class ModelsCapabilities(
    /** A download can be ended. */
    val cancel: Boolean,
    /** A download is offered twice: on Wi-Fi only, and with mobile data allowed. */
    val cellularChoice: Boolean,
    /** While one model is being downloaded no other can be: nothing offers a second one. */
    val oneDownloadAtATime: Boolean,
    val spaceReturns: SpaceReturns,
    /** The capabilities a switch changes something for on this platform, in the order shown. */
    val features: List<InferenceCapability>,
    /** The indexing section: progress, *Index now*, pause, coverage. */
    val indexing: Boolean,
    /** The realm's other devices can be listed and switched off from here. */
    val otherDevices: Boolean,
) {
    /** When the space of a removed model comes back. */
    enum class SpaceReturns {
        /** The bytes are released when the process next starts. Nothing may report them as freed. */
        AT_NEXT_START,

        /** What was compiled goes with the removal; the archives at the next start. */
        TOWERS_AT_ONCE,
    }
}

/**
 * Why this Control may not download or remove models.
 *
 * ⚠⚠ Two kinds with two sentences (`storeReadOnlyNotice`). Telling someone to quit another Control
 * when the lock simply could not be taken sends them after a process that is not there.
 */
sealed interface StoreReadOnly {

    /** Another Control process owns the model store. */
    data object OwnedElsewhere : StoreReadOnly

    /**
     * The store's lock could not be created or taken at all. [detail] is what failed and where
     * (the lock path and the cause); ⚠ blank when it arrived as a typed refusal, which carries
     * no detail to the screen.
     */
    data class LockUnavailable(val detail: String = "") : StoreReadOnly
}

/**
 * How a `DownloadModel` call ended, by its typed reason.
 *
 * ⚠ Six failures, not one, because the screen owes each a different sentence and several of them
 * a different button: [TransportFailed] is worth retrying, [IntegrityFailed] fetches the same wrong
 * bytes from the same CDN, and [StoreNotOwned] / [StoreLockUnavailable] cannot succeed while this
 * process lives.
 */
enum class ModelDownloadOutcome {
    Landed,
    NoCatalog,
    UnknownModel,
    TransportFailed,
    IntegrityFailed,

    /** Another Control process owns the model store. ⚠ Never offer a retry: it cannot work. */
    StoreNotOwned,

    /** The store's lock could not be taken at all. ⚠ Not [StoreNotOwned], and no retry either. */
    StoreLockUnavailable,
}

/**
 * What a removal did. ⚠ Without it, a removal that failed looks exactly like one that worked
 * until the row comes back installed.
 */
enum class ModelDeleteOutcome {
    /**
     * The model is no longer installed or used. ⚠⚠ **Its disk is not free yet**: the bytes are
     * released when Control next starts, so nothing may report the space as reclaimed.
     */
    Removed,

    /** @see ModelDownloadOutcome.StoreNotOwned */
    StoreNotOwned,

    /** @see ModelDownloadOutcome.StoreLockUnavailable */
    StoreLockUnavailable,

    /** The model is being downloaded, and nothing can end that first. Nothing was removed. */
    Busy,

    /**
     * The removal has not ended yet, and the caller has waited long enough. ⚠ Not a failure and
     * not done: the model is still being removed, and its row goes on saying so.
     */
    StillRemoving,

    /**
     * The runtime the removal ran in was torn down before it answered. ⚠⚠ **Not known**: the
     * removal may still have happened, so this is neither [Failed] nor [Removed], and nothing
     * records it as how the removal ended ([ModelsState.removalEndings]).
     */
    NotConnected,

    Failed,
}

/**
 * What pressing **Index now** did (§34).
 *
 * ⚠⚠ Four refusals, not one, because two of them name an action — install a model, switch the
 * capability on — and the other two are states the user put the device in. A single "couldn't start"
 * would send someone to look for a fault in their photos.
 */
enum class IndexStartOutcome {
    /** A pass is running — whether this call started it or joined one already going. */
    Started,
    NoModel,
    NoConsent,
    Paused,
    NotReady,

    /**
     * Refused by the phone **now**, and started by itself once that clears, while the app stays
     * in front (iOS's foreground pass, `ios-on-device-inference.md` §14.19). ⚠ Says no message:
     * why it waits is [ModelsUiState.foregroundIndex]'s, on the screen for as long as it is true.
     * Never answered on Android or desktop.
     */
    Waiting,
    Failed,
}

/**
 * How far this device's media index pass has got (IN-D6).
 *
 * ⚠⚠ [indexing] is carried rather than inferred from [total]: a pass over a library with no images
 * genuinely reports `total = 0`, so a zero cannot be read as "nothing is running". "Not indexing"
 * and "Indexing 0 of 0" are different sentences and the screen has to pick one.
 */
data class IndexProgressState(
    val indexing: Boolean = false,
    /** Images visited — ⚠ including the ones skipped as already current. */
    val done: Int = 0,
    /** The library handed to the pass, not the count that will be newly indexed. */
    val total: Int = 0,
)

/**
 * One capability's two IN-D3 answers, as this device sees them. Both must be yes for anything to
 * run here (`inference-service.md` §9.2).
 */
data class CapabilityConsent(
    val capability: InferenceCapability,
    /** *"May this realm be indexed for X at all"* — synced, so it follows the user to a new device. */
    val realm: Boolean = false,
    /** *"May **this** device do the work"* — a phone on battery and a desktop are different answers. */
    val device: Boolean = false,
)

/**
 * Another of the realm's devices and its own answer for one capability — a row of the feature
 * screen's device list (§38).
 */
data class OtherDeviceConsent(
    val deviceId: String,
    val name: String,
    /** That device's stored `deviceConsent`. ⚠ Absent reads as no, as everywhere (§9.2). */
    val allowed: Boolean,
)

/**
 * The capabilities this screen offers a switch for — ⚠⚠ **only those a switch changes something
 * for in this build**, because a switch that does nothing is a claim the product cannot keep.
 *
 * Each absence is deliberate, and each one's switch arrives with the slice that gives it an effect:
 * - `CONTENT_TAGGING` — the production vocabulary is empty and no registered backend tags, so the
 *   pass would emit nothing (§27; calibration is what adds it).
 * - `QUERY_PLANNING` — no backend implements `classifyIntent`.
 * - `TEXT_IN_IMAGES`, `SPEECH_TO_TEXT` — no backend at all.
 *
 * ⭐ `FACE_CLUSTERING` is offered with its backend (`MediaPipeFaceBackend`).
 * ⚠⚠ Settings-only and off by default (IN-D3) — never offered in onboarding
 * (`offerableInOnboarding = false`) — and withdrawing it deletes the face templates, not the names.
 */
internal val OfferedConsents: List<InferenceCapability> =
    listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH, InferenceCapability.FACE_CLUSTERING)

/**
 * Control on Android and desktop. ⚠ No cancel and no cellular choice: the in-process downloader
 * has neither (`tech-debt.md`).
 */
val ControlModelsCapabilities = ModelsCapabilities(
    cancel = false,
    cellularChoice = false,
    // ⚠ The progress stream cannot tell two downloads apart (`ModelBytes`).
    oneDownloadAtATime = true,
    spaceReturns = ModelsCapabilities.SpaceReturns.AT_NEXT_START,
    features = OfferedConsents,
    indexing = true,
    otherDevices = true,
)

/** One of the realm's authorised devices. */
data class RosterDevice(val deviceId: String, val name: String)

/** The two things the Models screen asks about the realm's devices. */
interface ModelsDevices {

    /** This device's id, or blank while it has none. */
    suspend fun deviceId(): String

    /**
     * The realm's authorised devices, this one among them. ⚠⚠ `null` when the roster could not
     * be read: an empty list there would say *no other device*, and *Turn off everywhere* would
     * then clear no peer and still write the realm no.
     */
    val roster: Flow<List<RosterDevice>?>
}

/** [ModelsDevices] over a [TenshuClient]. */
class ClientModelsDevices(private val client: TenshuClient) : ModelsDevices {

    override suspend fun deviceId(): String = client.syncService.getSyncStatus().getOrNull()?.deviceId?.get().orEmpty()

    override val roster: Flow<List<RosterDevice>?>
        get() = client.identityService.devices.flow().map { result ->
            (result as? TResult.Success)?.value?.toList()
                // ⚠ Authorised only: a revoked device's rows linger until its tombstone lands, and
                // it can neither index nor be told to stop.
                ?.filter { it.isAuthorized.get() }
                ?.map { RosterDevice(it.deviceId.get(), it.name.get()) }
        }
}

/**
 * The consent half of a Models screen (IN-D3, §38): the two answers per feature, and the other
 * devices'. ⚠ One implementation for every platform ([ClientModelsConsents]): the order of the
 * writes and what a failed read means must not differ between them.
 */
interface ModelsConsents {

    /**
     * The two answers of each of the features it was built for — reactive, so a realm switch flipped elsewhere shows up.
     *
     * ⚠ Never completes, and also follows this device's id: while there is none, the device half is
     * each consent's default, and once one appears the stored answers replace it on the same
     * subscription.
     */
    fun consents(): Flow<List<CapabilityConsent>>

    /**
     * Writes the realm answer (synced) for [capability].
     *
     * @return `false` when nothing was written — see [setDeviceConsent] for why that can happen.
     */
    suspend fun setRealmConsent(capability: InferenceCapability, allowed: Boolean): Boolean

    /**
     * Writes this device's answer for [capability].
     *
     * @return ⚠⚠ `false` when this device has no id yet. A `DeviceSetting` is keyed
     *   `"$deviceId/$preferenceId"` and the proxy reads it under its real id, so a row written under a
     *   placeholder is a switch that visibly moves and never takes effect (§9.2) — which for consent,
     *   unlike a pause, is a lie about what the user agreed to.
     */
    suspend fun setDeviceConsent(capability: InferenceCapability, allowed: Boolean): Boolean

    /**
     * Whether this device has an id to write its own answer under: `false` while it has none,
     * `true` once it has. ⚠ While it is `false`, [setDeviceConsent] writes nothing, and a screen
     * that can say so takes the switch away rather than letting the press fail.
     *
     * ⚠ Defaulted to known, so a test double owes nothing.
     */
    fun thisDeviceKnown(): Flow<Boolean> = flowOf(true)

    /**
     * The realm's **other** authorised devices, each with its own answer per offered capability
     * (§38) — reactive.
     *
     * ⚠⚠ **`null` is "not known", an empty map is "there are none"**. Until this
     * device knows its own id it cannot tell itself from the others, and a roster that could not be
     * read names nobody: *Turn off everywhere* run over either would clear no peer and still write
     * the realm no.
     *
     * ⚠ Defaulted to none, so a test double owes nothing.
     */
    fun otherDeviceConsents(): Flow<Map<InferenceCapability, List<OtherDeviceConsent>>?> = flowOf(emptyMap())

    /**
     * Writes an explicit **no** for another device's [capability].
     *
     * ⚠⚠ Off only, and that is the interface rather than a check: a device's yes spends that device's
     * battery and storage, so it is given on that device (§38.2). ⚠⚠ For `FACE_CLUSTERING` an explicit
     * no is what deletes that device's face data, once the row reaches it.
     *
     * @return `false` when nothing was written.
     */
    suspend fun clearOtherDeviceConsent(capability: InferenceCapability, deviceId: String): Boolean = false
}

/**
 * Control → Settings → **Models**: the canonical management surface for on-device inference models.
 * Control owns it because Control owns the store — the same reason Offline maps lives here.
 */
interface ModelsRepository : ModelsConsents {

    /** What this platform's repository can do. Fixed for the process. */
    val capabilities: ModelsCapabilities

    /**
     * The list and where each model stands, live.
     *
     * ⚠⚠ **Downloading is in here and nowhere else**: a row is fetching because the repository
     * says so, and a screen holds no copy of it. ⚠ What the repository rests that on is its own:
     * a download journal where there is one; on Android and desktop its own call being under
     * way, from the press (`ModelsLedger`). ⚠ Emits at once. A repository whose runtime can be
     * torn down emits [ModelsAvailability.NotConnected] and completes.
     */
    fun models(): Flow<ModelsState>

    /**
     * Starts a download of the whole checkpoint, and answers when it is **under way or
     * refused**, and why. ⚠ It does not wait for the model: everything after `Accepted` is the
     * row's, in [models]. ⚠ *Under way* is the repository's to define and is not instant: seconds
     * to tens of seconds on Android and desktop, where the row is already fetching meanwhile.
     * ⚠ The call belongs to the repository: a caller that stops waiting stops nothing.
     * [allowsCellular] is asked only where [ModelsCapabilities.cellularChoice].
     */
    suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult

    /** Ends a download. Asked only where [ModelsCapabilities.cancel]. */
    suspend fun cancel(modelId: String): ModelActionResult

    /**
     * Reads the list again, and returns when it has been read. A failure is in [models]. ⚠ What
     * is shown stays while it runs, and the read belongs to the repository, not to its caller.
     */
    suspend fun checkAgain()

    /** See [app.tenshu.control.inference.OnDeviceModelSource.loadAgain]. ⚠ Nothing where there is no such fail. */
    suspend fun loadAgain(modelId: String) = Unit

    /**
     * ⚠ Removes the model and **nothing else** — consent is a separate switch (IN-D10).
     * ⚠⚠ The model stops being used at once; when its space returns is [ModelsCapabilities.SpaceReturns].
     * ⚠⚠ **The repository owns the call**: the removal, and the read of what is installed after
     * it, run to their end in the repository's scope whether or not the caller is still waiting.
     * A screen that is closed mid-removal must not leave a list that still shows the model.
     */
    suspend fun delete(modelId: String): ModelDeleteOutcome

    /**
     * A push stream of this device's index-pass position (IN-D6).
     *
     * ⚠⚠ **Never completes**, and emits at once: `indexing = false` while no pass runs, the ticks of
     * one that does, then `indexing = false` again when it ends — and the same again for every pass
     * that **starts later**, on the same subscription. Subscribe once; nothing re-subscribes.
     *
     * ⚠ Not the `IndexProgress` op, which is bounded by the pass it watches: a later pass would be
     * invisible until the user pressed Refresh, and *Index now* starts one while the screen is up.
     * See `IndexProgressSource`.
     */
    fun indexProgress(): Flow<IndexProgressState>

    /** Whether the user has paused indexing on **this** device — reactive, and never synced. */
    fun indexingPaused(): Flow<Boolean>

    /**
     * How much of the library the last finished pass could look at — `null` until one has run in
     * this process. ⚠ Defaulted to none, so a test double owes nothing.
     */
    fun lastCoverage(): Flow<IndexCoverage?> = flowOf(null)

    /**
     * Where the pass a person asked for stands, on a platform that indexes only in the foreground
     * (iOS, `ios-on-device-inference.md` §14.19.6); `null` elsewhere. ⚠ Defaulted to none, so
     * Android, desktop and a test double owe nothing.
     */
    fun foregroundIndex(): Flow<ForegroundIndexStanding?> = flowOf(null)

    /** What the index holds, read from the store (§14.19.6); `null` where it is not read. ⚠ Defaulted to none. */
    fun storedIndex(): Flow<StoredIndex?> = flowOf(null)

    /**
     * Pause or resume indexing on this device.
     *
     * ⚠⚠ Not consent. Withdrawing consent means *"do not index my library"*; pausing means *"not
     * right now"* and keeps every row already derived (IN-D5 / `IndexingPaused`).
     */
    suspend fun setIndexingPaused(paused: Boolean)

    /**
     * Starts a pass now (§34).
     *
     * ⚠ Returns when the pass has STARTED, never when it ends: a first index is minutes to hours, and
     * [indexProgress] is what reports the rest.
     */
    suspend fun startIndexing(): IndexStartOutcome
}

/**
 * [ModelsRepository] over Control's in-process [TenshuClient] — the same client Control's other
 * screens read. `InferenceService` is registered on it (Control hosts the proxy), so this is a plain
 * in-process call, no IPC.
 */
class ClientModelsRepository(
    private val client: TenshuClient,
    /** ⚠ For the device id (`IndexingPaused` is DEVICE-scoped) and for the roster: [ClientModelsConsents]. */
    devices: ModelsDevices,
    /**
     * Process-local, like [coverage] — the holder the indexer ticks, read directly so the stream
     * stays open across passes (see [ModelsRepository.indexProgress]).
     *
     * ⚠⚠ Required, not `null`-defaulted like the two below: an unbound source would compile, start,
     * and quietly show a bar that misses every pass started after the screen opened.
     */
    private val indexProgress: IndexProgressSource,
    /** Process-local (Control's UI shares the proxy's process); `null` where it is not wired. */
    private val coverage: IndexCoverageSource? = null,
    /**
     * Process-local, like [coverage]. ⚠ `null` reads as writable; a tap then gets the typed
     * `STORE_NOT_OWNED` refusal instead of the notice, which is the backstop, not the design.
     */
    private val storeAccess: ModelStoreAccessSource? = null,
    /** ⚠ The process's, not a screen's: a download goes on, and is followed, with no screen open. */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val consentHalf: ClientModelsConsents = ClientModelsConsents(client, devices, ControlModelsCapabilities.features),
) : ModelsRepository, ModelsConsents by consentHalf {

    override val capabilities: ModelsCapabilities = ControlModelsCapabilities

    override fun lastCoverage(): Flow<IndexCoverage?> = coverage?.coverage ?: flowOf(null)

    private val ledger = ModelsLedger(
        calls = ClientModelCalls(client),
        storeAccess = { storeReadOnlyOf(storeAccess) },
        scope = scope,
        dispatcher = Dispatchers.Default,
    )

    override fun models(): Flow<ModelsState> = ledger.state

    // ⚠ [allowsCellular] is not sent: `DownloadModel` carries no such choice, and nothing here
    // restricts a download to Wi-Fi ([ModelsCapabilities.cellularChoice] is false).
    override suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult = ledger.download(modelId)

    /** ⚠ Never asked: [ModelsCapabilities.cancel] is false, and there is no operation to send. */
    override suspend fun cancel(modelId: String): ModelActionResult =
        ModelActionResult.Refused(ModelActionResult.Why.Unnamed)

    override suspend fun checkAgain() = ledger.checkAgain()

    override suspend fun delete(modelId: String): ModelDeleteOutcome = ledger.delete(modelId)

    override fun indexProgress(): Flow<IndexProgressState> = indexProgressStates(indexProgress)

    // ⚠ Follows the device id: a read resolved once at the first collect stays on the placeholder's
    // row after the real id appears, while a press writes under the real one.
    override fun indexingPaused(): Flow<Boolean> = pausedOver(
        consentHalf.deviceIdsOrPlaceholder(),
        read = { id -> client.preferenceService.readForDevice(IndexingPaused, id) },
        readOnce = { id -> client.preferenceService.readOnceForDevice(IndexingPaused, id) },
    )

    override suspend fun setIndexingPaused(paused: Boolean) {
        client.preferenceService.setForDevice(IndexingPaused, paused, consentHalf.deviceIdOrPlaceholder())
    }

    override suspend fun startIndexing(): IndexStartOutcome =
        client.inferenceService.startMediaIndexPass().fold(
            onSuccess = { IndexStartOutcome.Started },
            // ⚠ Typed, so the four reasons survive to the screen. Anything untyped is [Failed] — the
            // bucket that says "try again", which is the honest answer to a failure we cannot name.
            onFailure = { error ->
                when ((error as? IndexPassRefused)?.reason?.get()) {
                    IndexPassRefused.Reasons.NO_BACKEND -> IndexStartOutcome.NoModel
                    IndexPassRefused.Reasons.NO_CONSENT -> IndexStartOutcome.NoConsent
                    IndexPassRefused.Reasons.PAUSED -> IndexStartOutcome.Paused
                    IndexPassRefused.Reasons.NOT_READY -> IndexStartOutcome.NotReady
                    else -> IndexStartOutcome.Failed
                }
            },
        )
}

/**
 * [ModelsConsents] over a [TenshuClient]: the preference rows, read and written the same way on
 * every platform.
 *
 * @param devices for this device's id (the device consents are DEVICE-scoped, and
 *   `:preference-service-client` has no `currentDeviceId` resolver) and for the roster.
 * @param features the capabilities a switch is offered for here ([ModelsCapabilities.features]).
 */
class ClientModelsConsents(
    private val client: TenshuClient,
    private val devices: ModelsDevices,
    private val features: List<InferenceCapability>,
    /**
     * Where every read and write runs. ⚠⚠ Photos' client is in-process and its executor switches
     * no dispatcher, so without this each preference read and write runs on the caller's thread,
     * which on iOS is the main thread (`viewModelScope` is `Main.immediate`). Empty (the caller's)
     * for Control's IPC client.
     * ⚠ `flowOn` on flows the view model `collect`s, never one it `stateIn`s: the two fuse.
     */
    private val work: CoroutineContext = EmptyCoroutineContext,
) : ModelsConsents {

    @kotlin.concurrent.Volatile
    private var cachedDeviceId: String? = null

    override fun consents(): Flow<List<CapabilityConsent>> = consentsOver(
        features = features,
        deviceIds = deviceIdOnceKnown(::resolvedDeviceId),
        // ⚠ Seeded with a one-shot read: a relation that has never held the row stays silent, and
        // `combine` waits for every input — one unset preference would hide the whole section.
        // ⚠⚠ The snapshot, not the default: the read is re-subscribed after every consent write and
        // again when the device id appears, and a default seed would flick a switch that is on to off
        // and back each time.
        realm = { capability ->
            client.preferenceService.read(capability.realmConsent)
                .onStart { emit(client.preferenceService.readOnce(capability.realmConsent)) }
        },
        device = { capability, deviceId ->
            client.preferenceService.readForDevice(capability.deviceConsent, deviceId)
                .onStart { emit(client.preferenceService.readOnceForDevice(capability.deviceConsent, deviceId)) }
        },
    ).flowOn(work)

    override suspend fun setRealmConsent(capability: InferenceCapability, allowed: Boolean): Boolean = withContext(work) {
        client.preferenceService.set(capability.realmConsent, allowed) is TResult.Success
    }

    override suspend fun setDeviceConsent(capability: InferenceCapability, allowed: Boolean): Boolean = withContext(work) {
        val deviceId = resolvedDeviceId() ?: return@withContext false
        client.preferenceService.setForDevice(capability.deviceConsent, allowed, deviceId) is TResult.Success
    }

    override fun thisDeviceKnown(): Flow<Boolean> = deviceIdOnceKnown(::resolvedDeviceId).map { it != null }.distinctUntilChanged().flowOn(work)

    override fun otherDeviceConsents(): Flow<Map<InferenceCapability, List<OtherDeviceConsent>>?> =
        otherDeviceConsentsOver(
            features = features,
            ownDeviceIds = deviceIdOnceKnown(::resolvedDeviceId),
            roster = devices.roster.map { roster -> roster?.map { it.deviceId to it.name } },
            // ⚠ Seeded for [consents]' reason: a row that never existed stays silent.
            device = { capability, deviceId ->
                client.preferenceService.readForDevice(capability.deviceConsent, deviceId)
                    .onStart { emit(client.preferenceService.readOnceForDevice(capability.deviceConsent, deviceId)) }
            },
        ).flowOn(work)

    override suspend fun clearOtherDeviceConsent(capability: InferenceCapability, deviceId: String): Boolean = withContext(work) {
        // ⚠ This device's own answer goes through [setDeviceConsent], which the view model orders
        // against the realm write.
        if (deviceId.isBlank() || deviceId == resolvedDeviceId()) return@withContext false
        client.preferenceService.setForDevice(capability.deviceConsent, false, deviceId) is TResult.Success
    }

    /**
     * The real id or `null` — never the placeholder, which consent must not write under (see
     * [ModelsConsents.setDeviceConsent]). ⚠ A miss is not cached, so the real id is adopted once sync surfaces one.
     */
    private suspend fun resolvedDeviceId(): String? =
        cachedDeviceId ?: devices.deviceId().takeIf { it.isNotBlank() }?.also { cachedDeviceId = it }

    /**
     * ⚠ Falls back rather than returning null: a blank id makes both the read and the write silent
     * no-ops (as in `TenshuRecoveryAffordancePreferences`). The fallback is never cached, so the
     * real id is adopted the moment sync surfaces one.
     */
    suspend fun deviceIdOrPlaceholder(): String = withContext(work) { resolvedDeviceId() } ?: LOCAL_FALLBACK_DEVICE_ID

    /**
     * [deviceIdOrPlaceholder] as it changes: the placeholder while no id is known, then the real
     * id once it appears ([deviceIdOnceKnown], the path [consents] follows). ⚠ For a read that a
     * write made under [deviceIdOrPlaceholder] must land in: a read resolved once at its first
     * collect stays on the placeholder's row after the real id arrives.
     */
    fun deviceIdsOrPlaceholder(): Flow<String> =
        deviceIdOnceKnown(::resolvedDeviceId).map { it ?: LOCAL_FALLBACK_DEVICE_ID }.distinctUntilChanged().flowOn(work)

    private companion object {
        const val LOCAL_FALLBACK_DEVICE_ID = "local-device"
    }
}

/**
 * The pause switch of a Models screen: `IndexingPaused`, a device setting, never synced (IN-D5).
 * ⚠ An interface so that the on-device repository can be given none (nothing to pause).
 */
interface IndexingPause {
    /** ⚠ Never completes. ⚠ May emit nothing on a device that holds no device-setting rows. */
    fun paused(): Flow<Boolean>

    suspend fun set(paused: Boolean)
}

/**
 * [IndexingPause] over a client, **as [ClientModelsRepository] reads and writes it**: the same
 * row, under the same device id (or its placeholder), on [work]. ⚠ The read follows the id
 * ([pausedOver]), so a pause written once the real id is known is the one the screen shows.
 *
 * ⚠ The placeholder is Android's behaviour kept: a pause written before the device has an id is
 * under `local-device` and is not read by the pass. Recorded, not changed here (§14.19.6).
 */
class ClientIndexingPause(
    private val client: TenshuClient,
    private val consents: ClientModelsConsents,
    private val work: CoroutineContext = EmptyCoroutineContext,
) : IndexingPause {
    override fun paused(): Flow<Boolean> = pausedOver(
        consents.deviceIdsOrPlaceholder(),
        read = { id -> client.preferenceService.readForDevice(IndexingPaused, id) },
        readOnce = { id -> client.preferenceService.readOnceForDevice(IndexingPaused, id) },
    ).flowOn(work)

    override suspend fun set(paused: Boolean) {
        withContext(work) { client.preferenceService.setForDevice(IndexingPaused, paused, consents.deviceIdOrPlaceholder()) }
    }
}

/**
 * The pause row under whichever id [deviceIds] last named. ⚠ Re-read when the id changes: a
 * press writes under the id known at the press, so a read pinned to the first id would show
 * *not paused* over a device paused under its real id — and offer no *Resume*.
 * Split out so it is testable without a client.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun pausedOver(
    deviceIds: Flow<String>,
    read: (String) -> Flow<Boolean>,
    /**
     * ⚠⚠ Each id's read is SEEDED with a snapshot, as the consents' are: a reactive device read
     * emits nothing while the device-setting relation holds no row, so after a switch of id the
     * screen would keep the previous id's answer — a pause shown that the pass does not read.
     */
    readOnce: suspend (String) -> Boolean,
): Flow<Boolean> =
    deviceIds.distinctUntilChanged().flatMapLatest { id -> read(id).onStart { emit(readOnce(id)) } }.distinctUntilChanged()

/** The holder's position as the screen's state: no pass running reads as `indexing = false`. */
internal fun indexProgressStates(source: IndexProgressSource): Flow<IndexProgressState> =
    source.progress.map { pass ->
        pass?.let { IndexProgressState(indexing = true, done = it.done, total = it.total) }
            ?: IndexProgressState(indexing = false)
    }

/**
 * The answers of [features] under whichever device id [deviceIds] last named — the device half is
 * each consent's default while that is `null`, and the stored answer once it is not.
 *
 * ⚠ Split out of [ClientModelsRepository.consents] only so the switch-over is testable without a
 * client; the repository passes its real preference reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun consentsOver(
    deviceIds: Flow<String?>,
    realm: (InferenceCapability) -> Flow<Boolean>,
    device: (InferenceCapability, String) -> Flow<Boolean>,
    features: List<InferenceCapability> = OfferedConsents,
): Flow<List<CapabilityConsent>> =
    deviceIds
        .distinctUntilChanged()
        .flatMapLatest { deviceId ->
            val perCapability = features.map { capability ->
                val deviceHalf = deviceId?.let { device(capability, it) } ?: flowOf(capability.deviceConsent.default)
                combine(realm(capability), deviceHalf) { r, d -> CapabilityConsent(capability, realm = r, device = d) }
            }
            combine(perCapability) { it.toList() }
        }
        .distinctUntilChanged()

/**
 * Every device in [roster] but this one, with its answer per capability of [features].
 *
 * ⚠⚠ `null` while this device's id is unknown: the roster includes this device, and without the id
 * it would appear among the others, under a switch that writes another device's row. ⚠⚠ And `null`
 * while [roster] is: a roster that could not be read is not a realm of one device. ⚠⚠ `null`, not
 * empty — see [ModelsRepository.otherDeviceConsents].
 *
 * ⚠ Split out of [ClientModelsRepository.otherDeviceConsents] so it is testable without a client.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun otherDeviceConsentsOver(
    ownDeviceIds: Flow<String?>,
    roster: Flow<List<Pair<String, String>>?>,
    device: (InferenceCapability, String) -> Flow<Boolean>,
    features: List<InferenceCapability> = OfferedConsents,
): Flow<Map<InferenceCapability, List<OtherDeviceConsent>>?> =
    combine(ownDeviceIds.distinctUntilChanged(), roster.distinctUntilChanged()) { own, devices ->
        if (own == null || devices == null) null else devices.filter { (id, _) -> id != own }
    }
        .distinctUntilChanged()
        .flatMapLatest { others ->
            if (others == null) return@flatMapLatest flowOf(null)
            // ⚠ `combine` over no flows never emits, so no other device is said outright.
            if (others.isEmpty()) return@flatMapLatest flowOf(emptyMap())
            val perCapability = features.map { capability ->
                combine(
                    others.map { (id, name) ->
                        device(capability, id).map { OtherDeviceConsent(id, name, allowed = it) }
                    },
                ) { capability to it.toList() }
            }
            combine(perCapability) { it.toMap() }
        }
        .distinctUntilChanged()

/**
 * This device's id as [resolve] first answers it, then — only if that was `null` — the real id
 * once it appears, asked again on a backoff from [firstRetry] doubling up to [maxRetry].
 *
 * ⚠⚠ Polled because `SyncRepository` offers the id one-shot only (`getDeviceId`), and adding a
 * reactive op for one screen's switch is not worth a schema change. The wait is bounded per step,
 * not in count: a cap on attempts would bring back the switch that stays off forever. It costs
 * nothing once the id is known — the flow emits it and completes — and dies with its collector.
 */
internal fun deviceIdOnceKnown(
    resolve: suspend () -> String?,
    firstRetry: Duration = 1.seconds,
    maxRetry: Duration = 30.seconds,
): Flow<String?> = flow {
    val first = resolve()
    emit(first)
    if (first != null) return@flow
    var wait = firstRetry
    while (true) {
        delay(wait)
        wait = (wait * 2).coerceAtMost(maxRetry)
        val id = resolve() ?: continue
        emit(id)
        return@flow
    }
}

/**
 * The plain-data projection of a row from either source, so the join below stays pure and testable
 * without a client.
 */
internal data class ModelFacts(
    val modelId: String,
    val name: String,
    val byteSize: Long,
    val modelVersion: Int,
    val capabilityIds: List<String> = emptyList(),
    val licence: String = "",
)

private fun InstalledModel.facts() = ModelFacts(
    modelId = modelId.get(),
    // ⚠ The install index carries no display name — it is a catalog field, and a device that cannot
    // read the catalog still has to render this row. The id is the honest fallback.
    name = modelId.get(),
    byteSize = byteSize.get(),
    modelVersion = modelVersion.get().toInt(),
    licence = licence.get(),
)

private fun AvailableModel.facts() = ModelFacts(
    modelId = modelId.get(),
    name = name.get(),
    byteSize = byteSize.get(),
    modelVersion = modelVersion.get().toInt(),
    capabilityIds = capabilityIds(),
    licence = licence.get(),
)

/** [ModelCalls] over the client's inference service. */
private class ClientModelCalls(private val client: TenshuClient) : ModelCalls {

    override suspend fun installed(): ModelRead = read({ client.inferenceService.installedModels() }) { it.facts() }

    override suspend fun available(): ModelRead = read({ client.inferenceService.availableModels() }) { it.facts() }

    /**
     * ⚠ Three answers. A failure the operation typed ([ModelUnavailable]) is the proxy saying it
     * could not; anything else, a throw included, is no answer from it at all.
     */
    private suspend fun <T : Data> read(call: suspend () -> TResult<TList<T>>, facts: (T) -> ModelFacts): ModelRead {
        // ⚠ The mapping is inside the catch with the call: a row that cannot be read out of an
        // answer is no answer either.
        return runCatchingCancellable {
            val result = call()
            when {
                result is TResult.Success -> result.value?.toList()?.map(facts)?.let { ModelRead.Got(it) } ?: ModelRead.NoAnswer
                result is TResult.Failure && result.error is ModelUnavailable -> ModelRead.Failed
                else -> ModelRead.NoAnswer
            }
        }.getOrDefault(ModelRead.NoAnswer)
    }

    override suspend fun download(modelId: String): ModelDownloadOutcome =
        client.inferenceService.downloadModel(modelId).fold(
            onSuccess = { ModelDownloadOutcome.Landed },
            // ⚠ Typed, so the four reasons survive to the screen. Anything untyped is treated as a
            // transport failure — the retryable bucket, because denying someone the button that
            // would have worked is the worse of the two mistakes.
            onFailure = { error -> downloadOutcomeFor((error as? ModelUnavailable)?.reason?.get()) },
        )

    override fun progress(modelId: String): Flow<ModelBytes> =
        // ⚠ The id is the proxy's stamp on the tick, never the id asked for: see [ModelBytes].
        client.inferenceService.downloadProgress(modelId)
            .map { ModelBytes(it.modelId.get(), it.downloadedBytes.get(), it.totalBytes.get()) }

    override suspend fun delete(modelId: String): ModelDeleteOutcome =
        client.inferenceService.deleteModel(modelId).fold(
            onSuccess = { ModelDeleteOutcome.Removed },
            onFailure = { error -> deleteOutcomeFor((error as? ModelUnavailable)?.reason?.get()) },
        )
}

/**
 * Joins the verified catalog with the local install index, into the rows Control shows on Android
 * and desktop.
 *
 * ⚠⚠ **The one place those rows are made, and it takes nothing an in-process download cannot
 * know**: no readiness, no reason a download is held, no unreadable journal. So every installed
 * model is [ModelSetup.Standing.Ready] (there is no prepare step and no check here) and every
 * download is unheld, by what this function is given and not by a caller remembering.
 *
 * ⚠⚠ [available] is **nullable rather than possibly-empty**, and that is the whole point: null means
 * "we could not look", empty means "there is nothing for this device". Folding the first into the
 * second would tell a user with no pinned key that we publish no models — and drop the models they
 * have already installed, which are exactly the ones that still work with no catalog.
 *
 * ⚠ With no catalog a resident model's update is [ModelSetup.Update.Unknown]: staleness is a
 * comparison, and with nothing to compare against none is claimed and none is ruled out.
 *
 * @param stale [available] is the catalog as it was last read; the read after it failed.
 * @param fetching the models a download of is running, each with the last tick seen (or none yet).
 * @param last how a download last ended, shown only under the catalog version it asked for.
 * @param catalogRead `false` while the first catalog read has not answered: [available] is then
 *   `null` because nothing was read yet, which is not *could not be read*.
 */
internal fun modelsSnapshot(
    installed: List<ModelFacts>,
    available: List<ModelFacts>?,
    stale: Boolean = false,
    fetching: Map<String, ModelBytes?> = emptyMap(),
    last: Map<String, LastDownload> = emptyMap(),
    catalogRead: Boolean = true,
): ModelsAvailability.Listed {
    val resident = installed.associateBy { it.modelId }
    fun download(modelId: String, cataloguedBytes: Long?) = fetching[modelId].let { tick ->
        // ⚠ Nothing restricts an in-process download to Wi-Fi, and nothing can hold one.
        ModelSetup.Download(
            allowsCellular = true,
            writtenBytes = tick?.written,
            totalBytes = tick?.total ?: cataloguedBytes ?: 0L,
            held = null,
        )
    }
    fun held(record: ModelFacts, name: String, update: ModelSetup.Update) = ModelRow(
        modelId = record.modelId,
        name = name,
        setup = ModelSetup.Held(
            HeldModel(record.modelId, name, record.modelVersion, onDeviceBytes = record.byteSize, licence = record.licence),
            ModelSetup.Standing.Ready,
            update,
        ),
    )

    val catalogued = available.orEmpty().map { entry ->
        val offered = OfferedModel(
            modelId = entry.modelId,
            name = entry.name,
            modelVersion = entry.modelVersion,
            downloadBytes = entry.byteSize,
            // Nothing is unpacked or compiled here: what is downloaded is what is kept.
            onDeviceBytes = entry.byteSize,
            licence = entry.licence,
        )
        val said = last[entry.modelId]?.takeIf { it.modelVersion == entry.modelVersion }?.said
        val record = resident[entry.modelId]
        when {
            record == null && entry.modelId in fetching ->
                ModelRow(entry.modelId, entry.name, ModelSetup.Fetching(offered, download(entry.modelId, entry.byteSize)))
            record == null -> ModelRow(entry.modelId, entry.name, ModelSetup.Absent(offered, said))
            // ⚠ A resident copy of an older version is installed with an update, never absent: it
            // still works. A newer one is offered no downgrade.
            else -> held(
                record,
                entry.name,
                when {
                    entry.modelId in fetching -> ModelSetup.Update.Fetching(download(entry.modelId, entry.byteSize))
                    record.modelVersion < entry.modelVersion -> ModelSetup.Update.Available(offered, said)
                    else -> ModelSetup.Update.None
                },
            )
        }
    }

    // ⚠⚠ A model this device holds that the catalog does not list still appears — it is on the
    // disk either way, and a management screen that cannot show it is one that cannot delete it.
    // That is exactly how a withdrawn model would become unreclaimable space. ⚠ By its id: the
    // install index carries no name.
    val listed = catalogued.mapTo(mutableSetOf()) { it.modelId }
    val unlisted = installed.filterNot { it.modelId in listed }.map { record ->
        held(
            record,
            record.modelId,
            when {
                record.modelId in fetching -> ModelSetup.Update.Fetching(download(record.modelId, null))
                available == null -> ModelSetup.Update.Unknown
                else -> ModelSetup.Update.None
            },
        )
    }
    // A download of something neither read lists: its id, and the total the stream said.
    val nameless = fetching.keys.filterNot { it in listed || it in resident }.map { modelId ->
        ModelRow(modelId, modelId, ModelSetup.Fetching(model = null, download = download(modelId, null)))
    }

    return ModelsAvailability.Listed(
        catalog = when {
            !catalogRead -> ModelsAvailability.Catalog.NOT_READ_YET
            available == null -> ModelsAvailability.Catalog.UNAVAILABLE
            stale -> ModelsAvailability.Catalog.STALE
            else -> ModelsAvailability.Catalog.READ
        },
        downloads = ModelsAvailability.Downloads.KNOWN,
        models = catalogued + unlisted + nameless,
    )
}

/** How a failed [org.tenshu.inference.DeleteModel] reads on the screen, by its typed reason. */
internal fun deleteOutcomeFor(reason: String?): ModelDeleteOutcome = when (reason) {
    ModelUnavailable.Reasons.STORE_NOT_OWNED -> ModelDeleteOutcome.StoreNotOwned
    ModelUnavailable.Reasons.STORE_LOCK_UNAVAILABLE -> ModelDeleteOutcome.StoreLockUnavailable
    // ⚠ Already gone is what the user asked for; the refresh that follows shows it.
    ModelUnavailable.Reasons.NOT_INSTALLED -> ModelDeleteOutcome.Removed
    else -> ModelDeleteOutcome.Failed
}

/** The same for [org.tenshu.inference.DownloadModel]. ⚠ Unnamed failures stay in the retryable bucket. */
internal fun downloadOutcomeFor(reason: String?): ModelDownloadOutcome = when (reason) {
    ModelUnavailable.Reasons.NO_CATALOG -> ModelDownloadOutcome.NoCatalog
    ModelUnavailable.Reasons.UNKNOWN_MODEL -> ModelDownloadOutcome.UnknownModel
    ModelUnavailable.Reasons.INTEGRITY_FAILED -> ModelDownloadOutcome.IntegrityFailed
    ModelUnavailable.Reasons.STORE_NOT_OWNED -> ModelDownloadOutcome.StoreNotOwned
    ModelUnavailable.Reasons.STORE_LOCK_UNAVAILABLE -> ModelDownloadOutcome.StoreLockUnavailable
    else -> ModelDownloadOutcome.TransportFailed
}

/** The proxy's process-local answer, as the screen's state. ⚠ Absent reads as writable. */
internal fun storeReadOnlyOf(access: ModelStoreAccessSource?): StoreReadOnly? = when (access?.refusal) {
    null -> null
    ModelStoreAccessSource.Refusal.OwnedElsewhere -> StoreReadOnly.OwnedElsewhere
    ModelStoreAccessSource.Refusal.LockUnavailable -> StoreReadOnly.LockUnavailable(access.detail)
}
