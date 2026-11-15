package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.ForegroundIndexStanding
import app.tenshu.control.inference.IndexCoverage
import app.tenshu.control.inference.StoredIndex
import app.tenshu.control.inference.ModelActionResult
import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.download
import app.tenshu.control.inference.offersDownload
import app.tenshu.control.inference.offersRemoval
import app.tenshu.control.inference.usable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.util.bestEffort
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.inference.InferenceCapability

/**
 * Transient user-facing signal, mapped to text by the UI (ADR 0016).
 *
 * ⚠ Four, not one. [IntegrityFailed] in particular must not read as "try again": the bytes did not
 * match the signed catalog, and retrying fetches the same wrong bytes from the same CDN.
 */
enum class ModelsMessage {
    DownloadFailed,
    IntegrityFailed,
    UnknownModel,
    CatalogUnavailable,

    /**
     * This Control may not change models — the sentence is [ModelsUiState.storeReadOnly]'s.
     * ⚠⚠ Must not read as "try again" either: nothing this process does will work.
     */
    StoreReadOnly,

    /**
     * A model was removed. ⚠⚠ Says that its space is released when Control next starts, because
     * until then the bytes are still on disk and nothing else on the screen says so.
     */
    RemovedSpacePending,

    /** An update landed; the version it replaced stays on disk until Control next starts. */
    UpdatedSpacePending,

    /** A removal failed for a reason nobody named. */
    DeleteFailed,

    /** The models' runtime did not answer. */
    NotConnected,

    /** A download was refused for want of room. The figures, where known, are the row's. */
    NotEnoughSpace,

    /** A download could not be started just now, and asking again may work. */
    DownloadCouldNotStart,

    /** The same download already runs under the other mobile-data choice, which stands. */
    OtherCellularChoice,

    /** A download could not be ended. It goes on. */
    CancelFailed,

    /** Refused because the model is being removed or downloaded, or another download runs. */
    ModelBusy,

    /** A removal is taking long. It goes on; its row says so until it has ended. */
    StillRemoving,

    /** A consent switch could not be written — typically a device that has no id yet. */
    ConsentNotSaved,

    /** Another device's answer could not be switched off from here (§38.2). */
    OtherDeviceConsentNotSaved,

    /** §34 — the four ways *Index now* can refuse, plus the unnamed one. */
    IndexingStarted,
    IndexNoModel,
    IndexNoConsent,
    IndexPaused,
    IndexNotReady,
    IndexFailed,
}

data class ModelsUiState(
    /**
     * What the repository behind this screen can do. ⚠ No default: a state that does not say
     * whose screen it is would be drawn as Control's, buttons and features included.
     */
    val capabilities: ModelsCapabilities,
    /**
     * The list, as the repository last said it. ⚠⚠ **Downloading is in here and nowhere else**:
     * this class keeps no copy of it, so a screen opened over a running download shows it. ⚠ What
     * makes a row fetching is the repository's ([ModelsRepository.models]).
     */
    val availability: ModelsAvailability = ModelsAvailability.Loading,
    /**
     * Models whose download was pressed and not answered yet. ⚠ It says *starting* and takes
     * the row's buttons until the answer; it never says *downloading*. ⚠ The answer can take
     * tens of seconds. Where the repository shows the row fetching from the press (Android and
     * desktop) that row is drawn, and this is seen for a frame at most.
     */
    val starting: Set<String> = emptySet(),
    /**
     * Models whose removal is in flight. ⚠ Per row. The repository's ([ModelsState.removing]),
     * joined with this screen's own press for the moment before the repository reports it.
     */
    val removing: Set<String> = emptySet(),
    /** *Try again* was pressed and the list is being read again. */
    val checkingAgain: Boolean = false,
    /** @see ModelsState.storeReadOnly */
    val storeReadOnly: StoreReadOnly? = null,
    val pendingDeletion: ModelRow? = null,
    val message: ModelsMessage? = null,
    /** Where this device's index pass has got to (IN-D6). */
    val indexProgress: IndexProgressState = IndexProgressState(),
    /**
     * The user has paused indexing on **this** device.
     *
     * ⚠ Seeded `false` because `readForDevice` stays silent on a device that holds no device-setting
     * rows at all, so the first emission may never come. `false` is `IndexingPaused`'s own default,
     * so the seed and the absent row agree.
     */
    val indexingPaused: Boolean = false,
    /** The last finished pass's reach — see `LastIndexCoverage`. */
    val coverage: IndexCoverage? = null,
    /**
     * Where the pass a person asked for stands, where a pass runs only in the foreground (iOS,
     * §14.19.6). ⚠ `null` on Android and desktop, whose repository has none, and until read.
     */
    val foregroundIndex: ForegroundIndexStanding? = null,
    /** What the index holds, from the store (iOS, §14.19.6). ⚠ `null` where it is not read. */
    val storedIndex: StoredIndex? = null,
    /**
     * Whether a continued task carries the running pass (iOS 26+, `ios-on-device-inference.md`
     * §15): leaving the app does not stop it. ⚠ `false` until read, and everywhere else.
     */
    val indexContinued: Boolean = false,
    /**
     * IN-D3's switches, one entry per capability of [capabilities].
     *
     * ⚠ Seeded with every offered capability switched off rather than empty: off is each consent's
     * own default, so the section renders at once instead of appearing when the first read lands.
     */
    val consents: List<CapabilityConsent> = capabilities.features.map { CapabilityConsent(it) },
    /**
     * ⚠⚠ Whether [consents] has been **read** at least once. Until it has, [consents] is the seed
     * (every switch off) and says nothing of what is stored: no switch acts on it (the setters
     * refuse), and a screen shows it as not read yet. A toggle in that window would write the
     * opposite of a default, over whatever was stored.
     */
    val consentsRead: Boolean = false,
    /** A start is in flight — the button is disabled for the moment the call takes. */
    val startingIndex: Boolean = false,
    /** The realm's other devices and their answers, per capability — the feature screen's list (§38). */
    val otherDevices: Map<InferenceCapability, List<OtherDeviceConsent>> = emptyMap(),
    /**
     * [otherDevices] has been read. ⚠⚠ Until then an empty list is "not loaded", not "no other
     * device", and *Turn off everywhere* waits for it.
     */
    val otherDevicesKnown: Boolean = false,
    /** The other devices were asked for and an answer came, known or not: not known is then *could not be read*. */
    val otherDevicesRead: Boolean = false,
    /**
     * Whether this device has an id its own answer can be written under; `null` until that was
     * read. ⚠ `false` is said by a screen that can, and its switch then writes nothing (§9.2).
     */
    val thisDeviceKnown: Boolean? = null,
    /**
     * A feature whose model has just landed here and which is not on for this device (§38.3) — the
     * screen asks once whether to switch it on. ⚠ Without the question a downloaded model does
     * nothing, and the switch that says why is at the bottom of the screen.
     */
    val featureOffer: InferenceCapability? = null,
    /**
     * The capability ids each listed model serves, by model id, from the repository's catalog read
     * ([ModelsState.serves]). ⚠ Empty until the catalog was read: what a screen then says of a row
     * is said as of search's model (`ios-face-grouping.md` F6).
     */
    val serves: Map<String, List<String>> = emptyMap(),
) {
    /** Nothing has been read yet. */
    val loading: Boolean get() = availability == ModelsAvailability.Loading

    /** The rows, or none while there is no list. ⚠ Empty is not *nothing installed*: ask [availability]. */
    val models: List<ModelRow> get() = (availability as? ModelsAvailability.Listed)?.models.orEmpty()

    /** The catalog could not be read, and has never been in this process. */
    val catalogUnavailable: Boolean
        get() = (availability as? ModelsAvailability.Listed)?.catalog == ModelsAvailability.Catalog.UNAVAILABLE

    /**
     * Whether a model here can be **used**: installed and ready. `null` when what is installed
     * is not known. ⚠ Not known is not *none*. ⚠ Installed is not usable: a model that is being
     * prepared or checked, or that this device cannot run, indexes nothing.
     */
    val anyModelUsable: Boolean?
        get() {
            val models: List<ModelRow> = (availability as? ModelsAvailability.Listed)?.models ?: return null
            if (models.any { it.setup.usable }) return true
            // ⚠ A row whose standing was not read, or could not be, is not known to be unusable:
            // *none* would be said of a model readiness has not answered for yet.
            return if (models.all { it.setup.knownNotUsable }) false else null
        }
}

/**
 * What a removal's answer says to the user: the same sentence whether the call's own caller hears
 * it or a screen that watched the removal end.
 */
private fun ModelDeleteOutcome.closingMessage(): ModelsMessage? = when (this) {
    // ⚠⚠ Said, because where the bytes are still there "removed" would otherwise read as "freed".
    // ⚠ And only once the removal answered: nothing says *removed* before.
    ModelDeleteOutcome.Removed -> ModelsMessage.RemovedSpacePending
    ModelDeleteOutcome.Failed -> ModelsMessage.DeleteFailed
    ModelDeleteOutcome.Busy -> ModelsMessage.ModelBusy
    ModelDeleteOutcome.StillRemoving -> ModelsMessage.StillRemoving
    // ⚠ Never *couldn't remove, it is still installed*: nobody knows that of a removal cut off.
    ModelDeleteOutcome.NotConnected -> ModelsMessage.NotConnected
    // ⚠ The state's to say, as for a download ([ModelsState.storeReadOnly]).
    ModelDeleteOutcome.StoreNotOwned, ModelDeleteOutcome.StoreLockUnavailable -> null
}

/** See [ModelsViewModel.refusalOf]: the one place an action's answer is told apart. */
private fun whyRefused(answer: ModelActionResult): ModelActionResult.Why? = (answer as? ModelActionResult.Refused)?.why

/** What a row carries of how a download of what it offers last ended. */
private fun ModelSetup.lastSaid(): ModelSetup.DownloadSaid? = when (this) {
    is ModelSetup.Absent -> last
    is ModelSetup.Held -> (update as? ModelSetup.Update.Available)?.last
    is ModelSetup.Fetching -> null
}

/**
 * Whether this row is **known** not to be usable. ⚠ A standing that was not read, or could not
 * be, is not known either way: a model seen ready after it has not just become ready.
 */
internal val ModelSetup.knownNotUsable: Boolean
    get() = when (this) {
        is ModelSetup.Absent, is ModelSetup.Fetching -> true
        is ModelSetup.Held -> when (standing) {
            is ModelSetup.Standing.Preparing, is ModelSetup.Standing.Checking, ModelSetup.Standing.Unsuitable, ModelSetup.Standing.StoppedLoading -> true
            ModelSetup.Standing.Ready, ModelSetup.Standing.NotReadYet, ModelSetup.Standing.Unreadable -> false
        }
    }

/**
 * What one emission of the list, set beside the one before it, gives a screen to say once.
 *
 * @param removalsEnded the models that were being removed and no longer are, each with how the
 *   removal **answered** ([ModelsState.removalEndings]), or `null` where the repository did not say.
 *   ⚠ Never inferred from whether the list still holds the model: a list that has not caught up
 *   would call a removal that happened a failed one.
 */
internal data class ModelsNews(
    val message: ModelsMessage? = null,
    val installedFirst: String? = null,
    val removalsEnded: Map<String, ModelDeleteOutcome?> = emptyMap(),
)

/**
 * What changed between two emissions that is worth saying **once**: a first install, an update
 * that landed, a download that ended badly, a store that turned read-only.
 *
 * ⚠⚠ Nothing is said of the first emission ([before] is `null`), or across a list that was not
 * there: what a screen opens on is a state, and only what it watched happen is news.
 * ⚠ Per row and by model id, so nothing is said under a row it was not about.
 */
internal fun newsBetween(before: ModelsState?, now: ModelsState): ModelsNews {
    if (before == null) return ModelsNews()
    // ⚠ Whatever the list is, and whatever else the emission says: the ending is the call's
    // answer, not a reading of the list, and an emission that drops it never says it again.
    val removalsEnded = (before.removing - now.removing).associateWith { modelId -> now.removalEndings[modelId] }
    if (before.storeReadOnly == null && now.storeReadOnly != null) return ModelsNews(ModelsMessage.StoreReadOnly, removalsEnded = removalsEnded)
    val was = (before.availability as? ModelsAvailability.Listed)?.models?.associateBy { it.modelId } ?: return ModelsNews(removalsEnded = removalsEnded)
    val rows = (now.availability as? ModelsAvailability.Listed)?.models ?: return ModelsNews(removalsEnded = removalsEnded)
    var message: ModelsMessage? = null
    var installedFirst: String? = null
    for (row in rows) {
        val old = was[row.modelId]?.setup ?: continue
        val new = row.setup
        when {
            // ⚠ *Usable*, not *held*: where an install is prepared and checked first, the question
            // is asked when the model can do something, and never for one this device cannot run.
            // ⚠ And from a row **known** not to be usable: see [knownNotUsable].
            old.knownNotUsable && new.usable -> installedFirst = installedFirst ?: row.modelId
            old is ModelSetup.Held && new is ModelSetup.Held && new.model.modelVersion > old.model.modelVersion ->
                message = message ?: ModelsMessage.UpdatedSpacePending
            // ⚠ *Says something it did not say before* as well as *was being fetched*: two
            // emissions can straddle a fetch that was too short to be seen.
            new.download == null && (old.download != null || old.lastSaid() != new.lastSaid()) -> {
                val said = new.lastSaid()
                if (said is ModelSetup.DownloadSaid.Failed) {
                    message = message ?: if (said.integrity) ModelsMessage.IntegrityFailed else ModelsMessage.DownloadFailed
                }
            }
        }
    }
    return ModelsNews(message, installedFirst, removalsEnded)
}

/**
 * Control → Settings → **Models** (IN-D10): list what this device could install, what it
 * holds, and how much each costs; download and delete.
 *
 * ⚠⚠ **This is the only route by which a model reaches a device.** Nothing downloads on its own —
 * IN-D3 keeps inference off by default and IN-D10 requires the user to see the cost first — so
 * without this screen the backend, the store and the downloader are all unreachable.
 *
 * Everything shown is a live stream subscribed once: the model list (and with it what is being
 * downloaded), index progress, the pause switch, coverage, the consents. ⚠ What this class holds
 * of its own is only what nothing else publishes: a removal in flight, a press not answered yet.
 */
class ModelsViewModel(
    private val repository: ModelsRepository,
    /**
     * `false` for the feature screen's instance, which shows the consents and nothing of the
     * models. ⚠ It then says nothing a model did (no message, no feature question: nothing on
     * that screen would show or consume them) and asks for no read of the list.
     */
    private val forTheModelsScreen: Boolean = true,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelsUiState(capabilities = repository.capabilities))
    val uiState: StateFlow<ModelsUiState> get() = _uiState

    /** @see observeConsents */
    private var consentsJob: Job? = null

    /** @see observeModels */
    private var modelsJob: Job? = null

    /** Removals this screen pressed and has no answer for yet, and the ones the repository reports. */
    private var pressedRemovals = emptySet<String>()
    private var reportedRemovals = emptySet<String>()

    /**
     * Removals whose end this screen has said, or will say, from its own call's answer, so that
     * the list's word for the same ending is not said as well: [endSeen] while the call has not
     * answered yet, [endAnswered] once it has and the list has not shown the ending.
     */
    private var endSeen = emptySet<String>()
    private var endAnswered = emptySet<String>()

    /** Models this screen has seen usable: an update of one is not a first install. */
    private var seenUsable = emptySet<String>()

    /**
     * ⚠⚠ **One consent write at a time, in press order.** Each press launches its own coroutine, and
     * unserialised writes can land out of order — on then off, with the older `true` stored last and
     * indexing permitted against the user's last press. `Mutex` is fair, so presses queue in order.
     */
    private val consentWrites = Mutex()

    /** Per capability, the newest press — only its outcome may repaint the switch. */
    private val consentPresses = mutableMapOf<InferenceCapability, Int>()

    // ⚠⚠ Every field stays ABOVE `init`: on K/N `viewModelScope` is `Main.immediate`, so what
    // `init` launches runs inside the constructor and a field declared below is still unset.

    init {
        observeModels()
        // ⚠ Every open reads the list again: Control runs for days, and what was read when the
        // process started is not what is published now. The rows stay while it is read.
        if (forTheModelsScreen) refresh()
        observeIndexProgress()
        observeConsents()
        observeIndexingPaused()
        observeCoverage()
        observeForegroundIndex()
        // ⚠ Not collected where the repository cannot list them: nothing would be drawn from it.
        if (repository.capabilities.otherDevices) observeOtherDevices()
        // ⚠ Not asked where no switch is offered: nothing would be written under the id.
        if (repository.capabilities.features.isNotEmpty()) observeThisDevice()
    }

    private fun observeThisDevice() {
        viewModelScope.launch {
            bestEffort("Models -> observing whether this device has an id") {
                repository.thisDeviceKnown().collect { known -> _uiState.update { it.copy(thisDeviceKnown = known) } }
            }
        }
    }

    private fun observeOtherDevices() {
        viewModelScope.launch {
            bestEffort("Models -> observing the other devices' consents") {
                repository.otherDeviceConsents().collect { others ->
                    // ⚠⚠ A read that failed after one that worked keeps the devices it listed, and
                    // says they are not known now: never fewer devices because a read failed.
                    _uiState.update { it.copy(otherDevices = others ?: it.otherDevices, otherDevicesKnown = others != null, otherDevicesRead = true) }
                }
            }
        }
    }

    private fun observeCoverage() {
        viewModelScope.launch {
            bestEffort("Models -> observing index coverage") {
                repository.lastCoverage().collect { coverage -> _uiState.update { it.copy(coverage = coverage) } }
            }
        }
    }

    /**
     * ⚠ Both default to a single `null` (Android, desktop): collected, they change nothing there.
     * ⚠ Two collectors, so that one source that fails leaves the other's value standing.
     */
    private fun observeForegroundIndex() {
        viewModelScope.launch {
            bestEffort("Models -> observing the foreground pass") {
                repository.foregroundIndex().collect { standing -> _uiState.update { it.copy(foregroundIndex = standing) } }
            }
        }
        viewModelScope.launch {
            bestEffort("Models -> observing what the index holds") {
                repository.storedIndex().collect { stored -> _uiState.update { it.copy(storedIndex = stored) } }
            }
        }
        viewModelScope.launch {
            bestEffort("Models -> observing the continued task") {
                repository.indexContinued().collect { continued -> _uiState.update { it.copy(indexContinued = continued) } }
            }
        }
    }

    /**
     * ⚠ Once, from `init`; again only after the flow has ended ([retry]). The repository builds
     * what it emits off this thread, so nothing here reads or waits.
     *
     * ⚠⚠ A flow that ends or throws leaves *not connected*: never its last value, which nothing
     * stands behind any more, and never an empty list.
     */
    private fun observeModels() {
        modelsJob = viewModelScope.launch {
            bestEffort("Models -> observing the models") {
                var before: ModelsState? = null
                repository.models().collect { now ->
                    val news = if (forTheModelsScreen) newsBetween(before, now) else ModelsNews()
                    before = now
                    // ⚠ A first install only: an update changes nothing about what is switched on.
                    val offer = news.installedFirst?.takeUnless { it in seenUsable }
                        ?.let { offerAfterDownload(now.serves[it].orEmpty()) }
                    // ⚠⚠ What was seen usable is an INSTALL, not a model id: once the list shows the
                    // model not held (removed, or gone from the list), the next install of it is a
                    // first one again. A list that is not there says nothing either way.
                    (now.availability as? ModelsAvailability.Listed)?.models?.let { listed ->
                        val held = listed.filter { it.setup is ModelSetup.Held }.mapTo(mutableSetOf()) { it.modelId }
                        seenUsable = (seenUsable intersect held) + listed.filter { it.setup.usable }.map { it.modelId }
                    }
                    reportedRemovals = now.removing
                    // ⚠⚠ Told or said here, never both: a removal this screen's own call is waiting
                    // on, or was answered for, is that call's to say. What is left ended with
                    // nobody waiting (the wait is bounded, the removal is not), or was another
                    // screen's.
                    val ended = news.removalsEnded.filterKeys { it !in pressedRemovals && it !in endAnswered }
                    endSeen = endSeen + news.removalsEnded.keys.filter { it in pressedRemovals }
                    endAnswered = endAnswered - news.removalsEnded.keys
                    val closing = ended.values.firstNotNullOfOrNull { it?.closingMessage() }
                    val said = news.message ?: closing
                    _uiState.update {
                        it.copy(
                            availability = now.availability,
                            removing = reportedRemovals + pressedRemovals,
                            storeReadOnly = now.storeReadOnly,
                            // ⚠ Not said twice: a store's refusal is also its press's answer.
                            message = said?.takeUnless { new -> new == it.message } ?: it.message,
                            featureOffer = offer ?: it.featureOffer,
                            serves = now.serves,
                        )
                    }
                }
            }
            // ⚠ The repository's marks end with its flow: nothing stands behind *removing* any
            // more, and a mark kept here would swallow the ending of a removal the next flow shows.
            reportedRemovals = emptySet()
            endAnswered = emptySet()
            _uiState.update { it.copy(availability = ModelsAvailability.NotConnected, removing = pressedRemovals) }
        }
    }

    /**
     * Asks the repository to read the list again. ⚠ What it read arrives on the stream
     * [observeModels] holds; a read that failed arrives there too, as that failure.
     */
    fun refresh() {
        viewModelScope.launch { checkAgain() }
    }

    private suspend fun checkAgain() {
        // ⚠ First: a stream that ended does not come back by itself, and what is read below
        // must have somewhere to arrive.
        if (modelsJob?.isActive != true) observeModels()
        bestEffort("Models -> reading the models again") { repository.checkAgain() }
    }

    /**
     * ⚠ Once, from `init`: the stream never completes and reports every pass, including one that
     * starts after the screen opened (*Index now*, or one the scheduler started), so there is nothing to
     * re-subscribe to. It dies with the view model.
     *
     * ⚠ `bestEffort`: an escaping throw out of `viewModelScope` takes the app with it.
     */
    private fun observeIndexProgress() {
        viewModelScope.launch {
            bestEffort("Models -> observing index progress") {
                repository.indexProgress().collect { progress ->
                    _uiState.update { it.copy(indexProgress = progress) }
                }
            }
        }
    }

    /**
     * ⚠ Never completes — it is a preference read, not a job — so it is not tied to [refresh]. It
     * dies with the view model.
     */
    private fun observeIndexingPaused() {
        viewModelScope.launch {
            bestEffort("Models -> observing the indexing pause switch") {
                repository.indexingPaused().collect { paused ->
                    _uiState.update { it.copy(indexingPaused = paused) }
                }
            }
        }
    }

    /**
     * Pause or resume indexing on this device.
     *
     * ⚠⚠ The state moves **before** the write, not from the read echoing back. `readForDevice` emits
     * nothing at all on a device that holds no device-setting rows, which is exactly the state of a
     * device that has never paused — so a switch waiting for the echo would not move on the very
     * first press.
     */
    fun setIndexingPaused(paused: Boolean) {
        _uiState.update { it.copy(indexingPaused = paused) }
        viewModelScope.launch {
            bestEffort("Models -> writing the indexing pause switch") { repository.setIndexingPaused(paused) }
        }
    }

    /**
     * ⚠ Subscribed from `init` and re-subscribed after each consent write (see [persistConsent]). A
     * device id that appears later needs no re-subscribe: [ModelsRepository.consents] follows it.
     */
    private fun observeConsents() {
        consentsJob?.cancel()
        consentsJob = viewModelScope.launch {
            bestEffort("Models -> observing the consents") {
                repository.consents().collect { consents -> _uiState.update { it.copy(consents = consents, consentsRead = true) } }
            }
        }
    }

    /**
     * The realm switch — *"may this realm be indexed for X at all"*.
     *
     * ⚠⚠ **Both answers move together from here, in both directions.** Turning it on from this device
     * is the person holding this device asking for the feature, so this device's answer follows; the
     * other devices' answers do not, because each device's is its own (IN-D3). Turning it off clears
     * this device's too, so a later "on" from another device cannot quietly resume the work here.
     *
     * ⚠⚠ **The ORDER of the two writes is the safety property.** On writes the realm first; off clears
     * this device first. Either way a pair interrupted between its writes — the view model cleared,
     * the process killed — leaves this device *not* consented, never a stored yes under a realm no
     * that another device's "on" would silently resume.
     *
     * ⚠ Optimistic, like the pause: the switch moves before the write and moves back if it fails.
     */
    fun setRealmConsent(capability: InferenceCapability, allowed: Boolean) {
        val before = consentFor(capability) ?: return
        updateConsent(capability) { it.copy(realm = allowed, device = allowed) }
        persistConsent(capability, before, allowed, realmTouched = true) {
            if (allowed) {
                val realmSaved = attempt { repository.setRealmConsent(capability, true) }
                realmSaved to (realmSaved && attempt { repository.setDeviceConsent(capability, true) })
            } else {
                val deviceSaved = attempt { repository.setDeviceConsent(capability, false) }
                attempt { repository.setRealmConsent(capability, false) } to deviceSaved
            }
        }
    }

    /** This device's switch — *"may this device do the work"*. */
    fun setDeviceConsent(capability: InferenceCapability, allowed: Boolean) {
        val before = consentFor(capability) ?: return
        updateConsent(capability) { it.copy(device = allowed) }
        persistConsent(capability, before, allowed, realmTouched = false) {
            true to attempt { repository.setDeviceConsent(capability, allowed) }
        }
    }

    /**
     * Switches [capability] off for another device (§38.2). ⚠ Off only — see
     * [ModelsRepository.clearOtherDeviceConsent]. The list is a live read, so the row moves when the
     * write lands; a failure is said.
     */
    fun turnOffOtherDevice(capability: InferenceCapability, deviceId: String) {
        // ⚠⚠ Not over a roster that is not known: the row is one a read that has since failed
        // left behind, and nothing is written after a failed read.
        if (!_uiState.value.otherDevicesKnown) return
        viewModelScope.launch {
            val saved = consentWrites.withLock {
                attempt { repository.clearOtherDeviceConsent(capability, deviceId) }
            }
            if (!saved) _uiState.update { it.copy(message = ModelsMessage.OtherDeviceConsentNotSaved) }
        }
    }

    /**
     * *Turn off everywhere* (§38.2): this device, every other device that holds a yes, then the realm.
     *
     * ⚠⚠ The realm goes LAST, for [setRealmConsent]'s reason: interrupted anywhere, what is left is
     * the feature still on for fewer devices — never a realm no over device answers that a later
     * "on" would resume unseen.
     *
     * ⚠⚠ Refused until the other devices are known ([ModelsUiState.otherDevicesKnown]): over a list
     * that has not loaded it would clear no peer, write the realm no, and leave their answers stored.
     */
    fun turnOffEverywhere(capability: InferenceCapability) {
        if (!_uiState.value.otherDevicesKnown) return
        val before = consentFor(capability) ?: return
        val others = _uiState.value.otherDevices[capability].orEmpty().filter { it.allowed }
        updateConsent(capability) { it.copy(realm = false, device = false) }
        persistConsent(capability, before, allowed = false, realmTouched = true) {
            val deviceSaved = attempt { repository.setDeviceConsent(capability, false) }
            // ⚠ Every one is tried: one device's failed write must not leave the rest switched on.
            val othersSaved = others
                .map { other -> attempt { repository.clearOtherDeviceConsent(capability, other.deviceId) } }
                .all { it }
            if (!othersSaved) _uiState.update { it.copy(message = ModelsMessage.OtherDeviceConsentNotSaved) }
            attempt { repository.setRealmConsent(capability, false) } to deviceSaved
        }
    }

    /** Yes to [ModelsUiState.featureOffer]: the same two writes as switching it on by hand. */
    fun acceptFeatureOffer() {
        val capability = _uiState.value.featureOffer ?: return
        _uiState.update { it.copy(featureOffer = null) }
        setRealmConsent(capability, true)
    }

    fun dismissFeatureOffer() = _uiState.update { it.copy(featureOffer = null) }

    /**
     * The feature to offer once a model serving [capabilityIds] has landed: the first one it serves that this screen has
     * a switch for and that is not already on for this device.
     */
    private fun offerAfterDownload(capabilityIds: List<String>): InferenceCapability? =
        // ⚠ Not over the seed: an answer that was not read is not *off*, and is asked nothing.
        _uiState.value.takeIf { it.consentsRead }?.consents
            ?.firstOrNull { it.capability.id in capabilityIds && !(it.realm && it.device) }?.capability

    /**
     * Runs [writes] (→ realm saved, device saved) behind [consentWrites], then shows what is stored:
     * each half is the press's value if its write landed and what it was before the press if not.
     *
     * ⚠⚠ A failure is reported in BOTH directions. A failed clear while switching off leaves this
     * device's yes behind, and a later "on" from another device would silently resume the work here.
     */
    private fun persistConsent(
        capability: InferenceCapability,
        before: CapabilityConsent,
        allowed: Boolean,
        realmTouched: Boolean,
        writes: suspend () -> Pair<Boolean, Boolean>,
    ) {
        val press = (consentPresses[capability] ?: 0) + 1
        consentPresses[capability] = press
        viewModelScope.launch {
            val (realmSaved, deviceSaved) = consentWrites.withLock { writes() }
            // ⚠ A write that finishes after a newer press must not repaint the switch with its result.
            if (consentPresses[capability] == press) {
                updateConsent(capability) {
                    it.copy(
                        realm = if (!realmTouched || realmSaved) it.realm else before.realm,
                        device = if (deviceSaved) allowed else before.device,
                    )
                }
            }
            if (!realmSaved || !deviceSaved) {
                _uiState.update { it.copy(message = ModelsMessage.ConsentNotSaved) }
            }
            // ⚠⚠ Re-read after EVERY write — it is what makes the switch show what is stored,
            // whatever the queue did. `before` is the screen at press time, which after queued
            // presses can be an earlier press's optimistic state rather than anything persisted,
            // and the re-read seeds from a one-shot snapshot of the store. A late device id needs
            // no re-read: `consents()` follows the id itself.
            observeConsents()
        }
    }

    private suspend fun attempt(write: suspend () -> Boolean): Boolean =
        runCatchingCancellable { write() }.getOrDefault(false)

    /** ⚠ `null` until the consents were read: nothing is written over the seed. */
    private fun consentFor(capability: InferenceCapability): CapabilityConsent? =
        _uiState.value.takeIf { it.consentsRead }?.consents?.firstOrNull { it.capability == capability }

    private fun updateConsent(capability: InferenceCapability, change: (CapabilityConsent) -> CapabilityConsent) =
        _uiState.update { state ->
            state.copy(consents = state.consents.map { if (it.capability == capability) change(it) else it })
        }

    /**
     * **Index now** (§34).
     *
     * ⚠ The bar needs nothing from here: the progress stream subscribed in `init` stays open and
     * reports the pass this starts.
     */
    fun indexNow() {
        if (_uiState.value.startingIndex) return
        _uiState.update { it.copy(startingIndex = true) }
        viewModelScope.launch {
            val outcome = runCatchingCancellable { repository.startIndexing() }
                .getOrDefault(IndexStartOutcome.Failed)
            // ⚠ A pass that waits for the phone says nothing here: the section says why, for as
            // long as it is true. Whatever was said before stays.
            _uiState.update { it.copy(startingIndex = false, message = outcome.message() ?: it.message) }
        }
    }

    private fun IndexStartOutcome.message(): ModelsMessage? = when (this) {
        IndexStartOutcome.Started -> ModelsMessage.IndexingStarted
        IndexStartOutcome.NoModel -> ModelsMessage.IndexNoModel
        IndexStartOutcome.NoConsent -> ModelsMessage.IndexNoConsent
        IndexStartOutcome.Paused -> ModelsMessage.IndexPaused
        IndexStartOutcome.NotReady -> ModelsMessage.IndexNotReady
        IndexStartOutcome.Waiting -> null
        IndexStartOutcome.Failed -> ModelsMessage.IndexFailed
    }

    /** *Try again*: reads the list again, and says so for as long as that takes. */
    fun retry() {
        if (_uiState.value.checkingAgain) return
        _uiState.update { it.copy(checkingAgain = true) }
        viewModelScope.launch {
            checkAgain()
            _uiState.update { it.copy(checkingAgain = false) }
        }
    }

    /**
     * Asks for a download. ⚠⚠ The row does not move from here: it is *downloading* when the
     * repository says so, and what is said afterwards (it landed, it failed) is read off the row.
     * Only a refusal is this call's to say, because a refused download never becomes a row state.
     */
    fun download(modelId: String, allowsCellular: Boolean = false) {
        val state = _uiState.value
        // ⚠ The screen takes the button away in each of these; this is for a caller that kept one.
        if (state.storeReadOnly != null) return
        val row = state.models.firstOrNull { it.modelId == modelId } ?: return
        if (!row.setup.offersDownload || modelId in state.starting || modelId in state.removing) return
        // ⚠ The repository would refuse it: no second download beside a running one.
        if (state.capabilities.oneDownloadAtATime && state.models.any { it.setup.download != null }) return

        _uiState.update { it.copy(starting = it.starting + modelId) }
        viewModelScope.launch {
            // ⚠ A throw is a refusal nobody named, and must not strand the row starting.
            val said: ModelsMessage? = refusalOf { repository.download(modelId, allowsCellular) }?.message()
            _uiState.update { it.copy(starting = it.starting - modelId, message = said ?: it.message) }
        }
    }

    /** Ends a download, where the repository can. ⚠ The row goes when the repository says so. */
    fun cancel(modelId: String) {
        if (!_uiState.value.capabilities.cancel) return
        viewModelScope.launch {
            val why = refusalOf { repository.cancel(modelId) } ?: return@launch
            val message = if (why == ModelActionResult.Why.NotConnected) ModelsMessage.NotConnected else ModelsMessage.CancelFailed
            _uiState.update { it.copy(message = message) }
        }
    }

    /**
     * Why [ask] was refused, or `null` when it was accepted. A throw is a refusal nobody named.
     *
     * ⚠⚠ The type of the answer is tested in [whyRefused], a function of its own, and nowhere in
     * the coroutine that made the call. On Kotlin/Native 2.4.10 an *accepted* answer tested in
     * place (`answer is Refused`, or `as?`, on the value `runCatchingCancellable` returned) takes
     * the process down with a segmentation fault, and is right on the JVM
     * (`ModelsViewModelOnNativeTest`; `tech-debt.md`).
     */
    private suspend fun refusalOf(ask: suspend () -> ModelActionResult): ModelActionResult.Why? =
        whyRefused(runCatchingCancellable { ask() }.getOrDefault(ModelActionResult.Refused(ModelActionResult.Why.Unnamed)))

    /**
     * ⚠ Each refusal keeps its own sentence. Collapsing them would either offer a retry that
     * cannot work, or withhold one that would.
     */
    private fun ModelActionResult.Why.message(): ModelsMessage? = refusalMessage(this)

    /**
     * *Try again* on a model that loads Photos did not survive stopped (§14.5 item 0): one more
     * attempt, asked of the repository. ⚠ Where a repository has no such fail, nothing.
     */
    fun loadAgain(modelId: String) {
        viewModelScope.launch {
            bestEffort("Models -> trying the model once more") { repository.loadAgain(modelId) }
        }
    }

    /**
     * ⚠⚠ Asks first. Deleting is instant and the undo costs a ~383 MB re-download — and IN-D10
     * requires the screen to say which of "downloaded" and "enabled" an action changes, which is a
     * sentence that needs somewhere to live.
     */
    fun confirmDelete(modelId: String) {
        val state = _uiState.value
        if (state.storeReadOnly != null) return
        val row = state.models.firstOrNull { it.modelId == modelId } ?: return
        // ⚠ Not while this row is being fetched or removed. Another row's state holds nothing here.
        if (!row.setup.offersRemoval(state.capabilities.cancel) || modelId in state.removing) return
        _uiState.update { it.copy(pendingDeletion = row) }
    }

    fun dismissDelete() = _uiState.update { it.copy(pendingDeletion = null) }

    fun delete() {
        val state = _uiState.value
        val modelId = state.pendingDeletion?.modelId ?: return
        // ⚠ Asked again of the row as it is now: the confirmation was about the row as it was
        // when it opened, and a download of it may have started since.
        val row = state.models.firstOrNull { it.modelId == modelId }
        if (row == null || !row.setup.offersRemoval(state.capabilities.cancel) || state.storeReadOnly != null) {
            _uiState.update { it.copy(pendingDeletion = null) }
            return
        }
        if (modelId in state.removing) return

        // ⚠ Its own mark covers only the moment until the repository reports the removal; from
        // then on *removing* is the repository's, on this screen and on any other.
        pressedRemovals = pressedRemovals + modelId
        endSeen = endSeen - modelId
        endAnswered = endAnswered - modelId
        _uiState.update { it.copy(pendingDeletion = null, removing = reportedRemovals + pressedRemovals) }
        viewModelScope.launch {
            // ⚠⚠ The outcome is said: discarded, a removal that failed would look like one that
            // worked until the row came back. A throw is a failure nobody named.
            // ⚠ `runCatchingCancellable`, like every suspend call here: a plain `runCatching`
            // would swallow this coroutine's own cancellation.
            val outcome = runCatchingCancellable { repository.delete(modelId) }
                .getOrDefault(ModelDeleteOutcome.Failed)
            val message = outcome.closingMessage()
            pressedRemovals = pressedRemovals - modelId
            // ⚠ The list may show this ending before or after this answer. It is said once, here:
            // an ending the list has already shown is done with; one it has not is remembered so
            // that it is not said again. ⚠ *Still removing* answered nothing: its ending is the
            // list's to say.
            when {
                modelId in endSeen -> endSeen = endSeen - modelId
                // ⚠ Only while the list still shows it removing: then its letting go is still to
                // come, and takes this mark with it. Otherwise the mark would outlive the removal
                // and swallow the ending of the next one, somebody else's.
                outcome != ModelDeleteOutcome.StillRemoving && modelId in reportedRemovals -> endAnswered = endAnswered + modelId
            }
            _uiState.update { it.copy(removing = reportedRemovals + pressedRemovals, message = message ?: it.message) }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    /**
     * Clears [expected] only: the message the user saw and pressed *OK* on. ⚠ A message that
     * replaced it meanwhile stays, to be seen ([consumeMessage] cleared whatever was there).
     */
    fun consumeMessage(expected: ModelsMessage) = _uiState.update { if (it.message == expected) it.copy(message = null) else it }
}

/**
 * ⚠ Each refusal keeps its own sentence. Collapsing them would either offer a retry that cannot
 * work, or withhold one that would. ⚠ One mapping, for the view model's message and for a row
 * that says a refusal its caller no longer waited for ([ModelSetup.Pressed.Refused]).
 */
internal fun refusalMessage(why: ModelActionResult.Why): ModelsMessage? = when (why) {
    ModelActionResult.Why.NoCatalog -> ModelsMessage.CatalogUnavailable
    ModelActionResult.Why.UnknownModel -> ModelsMessage.UnknownModel
    ModelActionResult.Why.NotConnected -> ModelsMessage.NotConnected
    ModelActionResult.Why.Busy -> ModelsMessage.ModelBusy
    ModelActionResult.Why.OtherCellularChoice -> ModelsMessage.OtherCellularChoice
    ModelActionResult.Why.Unnamed -> ModelsMessage.DownloadFailed
    is ModelActionResult.Why.Said -> when (val said = why.said) {
        is ModelSetup.DownloadSaid.Failed ->
            if (said.integrity) ModelsMessage.IntegrityFailed else ModelsMessage.DownloadFailed
        is ModelSetup.DownloadSaid.NeedsSpace -> ModelsMessage.NotEnoughSpace
        ModelSetup.DownloadSaid.FreeSpaceUnknown,
        ModelSetup.DownloadSaid.NoTransport,
        ModelSetup.DownloadSaid.TransfersUnknown,
        ModelSetup.DownloadSaid.JournalUnreadable -> ModelsMessage.DownloadCouldNotStart
        // ⚠ Which refusal it was is [ModelsUiState.storeReadOnly]'s to say, where a repository
        // reports one; the sentence falls back to a plain one where it does not.
        ModelSetup.DownloadSaid.StoreRefused -> ModelsMessage.StoreReadOnly
        // The user ended it: there is nothing to tell them.
        ModelSetup.DownloadSaid.Cancelled -> null
    }
}
