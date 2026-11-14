package org.tenshu.sdk.ios

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration
import app.tenshu.control.inference.ForegroundIndexStanding
import app.tenshu.control.inference.HeldModel
import app.tenshu.control.inference.IndexCoverage
import app.tenshu.control.inference.IndexCoverageSource
import app.tenshu.control.inference.IndexPassPosition
import app.tenshu.control.inference.IndexProgressSource
import app.tenshu.control.inference.OnDeviceIndexSource
import app.tenshu.control.inference.StoredIndex
import app.tenshu.control.ui.screens.models.IndexStartOutcome
import app.tenshu.control.ui.screens.models.IndexingPause
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import app.tenshu.control.inference.ModelActionResult
import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelSetup.DownloadSaid
import app.tenshu.control.inference.ModelSetup.Standing
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.OfferedModel
import app.tenshu.control.inference.OnDeviceModelSource
import app.tenshu.control.ui.screens.models.CapabilityConsent
import app.tenshu.control.ui.screens.models.ModelDeleteOutcome
import app.tenshu.control.ui.screens.models.ModelsConsents
import app.tenshu.control.ui.screens.models.ModelsRepository
import app.tenshu.control.ui.screens.models.OnDeviceModelsCapabilities
import app.tenshu.control.ui.screens.models.OnDeviceModelsRepository
import app.tenshu.control.ui.screens.models.OtherDeviceConsent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import org.tenshu.inference.InferenceCapability

/**
 * The `-photosModels <state>` probe's controls (`ios-on-device-inference.md` §14.18.15), for the
 * harness's own overlay. **DEV_HARNESS only.**
 */
class PhotosModelsProbe internal constructor(
    /** Every call the screens made on the repository, in order: `download(id, wifi) · cancel(id)`. */
    val calls: StateFlow<String>,
    /** What the screen is told of the network in place of the monitor, or `null` to leave it the monitor's. */
    val onWifi: Boolean?,
    private val next: () -> Unit,
) {
    /** Moves the fake one step along its state's script. ⚠ Nothing else moves it: no call does. */
    fun advance() = next()
}

/**
 * The Models screens' fixture: the **real** [OnDeviceModelsRepository] over a source, a removal
 * and consents the probe moves. **DEV_HARNESS only**; nothing but
 * [PhotosSurface.installModelsFixture] builds one.
 *
 * ⚠⚠ **No call moves the fake** (§14.18.7: *no test does what the product never does*). A
 * download that is accepted leaves the row as it was; the row is fetching when
 * [PhotosModelsProbe.advance] says so, as a journal would.
 */
internal class PhotosModelsFixture private constructor(
    first: ModelsAvailability,
    private val notOffered: ModelsAvailability.NotOffered.Why? = null,
    realm: Boolean = false,
    device: Boolean = false,
    others: List<OtherDeviceConsent>? = emptyList(),
    private val deviceKnown: Boolean = true,
    private val onWifi: Boolean? = null,
    /** How a removal answers. `null`: when the probe next advances, and then *removed*. */
    private val removal: ModelDeleteOutcome? = null,
    /** A check stays unanswered until the probe advances. */
    private val slowCheck: Boolean = false,
    private val script: List<ModelsAvailability> = emptyList(),
    /**
     * Advances once by itself this long after the list is **first read** (the Models screen
     * opening), for a step that has to land while an alert covers the probe's button (an alert
     * takes every tap under it). ⚠ From the first read, not from construction: a cold launch's
     * length is then not in the case's clock.
     */
    private val advancesItselfAfter: Duration? = null,
    /** The indexing section's first state, and its own script, a step per advance (§14.19.6). */
    private val index: IndexStep = IndexStep(),
    private val indexScript: List<IndexStep> = emptyList(),
    /** How *Index now* answers. ⚠ It moves nothing: the section moves when the probe advances. */
    private val indexAnswer: IndexStartOutcome = IndexStartOutcome.Started,
    /**
     * Face grouping bound beside search (`ios-face-grouping.md` F6): its switch is offered, and the
     * list's face row is the face model's. [facesRealm] / [facesDevice] `false` stands for a row
     * that is **absent** as much as for an explicit no: the screen's consent read does not tell them
     * apart (both are shown off), and only the operation's deletion does (`inferenceConsentWithdrawn`).
     */
    private val faces: Boolean = false,
    private val facesRealm: Boolean = false,
    private val facesDevice: Boolean = false,
) {
    /** One state of the fake pass: what the driver, the bar, the coverage, the store and the pause say. */
    internal data class IndexStep(
        val standing: ForegroundIndexStanding = ForegroundIndexStanding.NotAsked,
        val running: IndexPassPosition? = null,
        val coverage: IndexCoverage? = null,
        val stored: StoredIndex = StoredIndex(searchable = null),
        val paused: Boolean = false,
    )

    private val shown = MutableStateFlow("")
    private val list = MutableStateFlow(first)
    private val step = MutableStateFlow(0)
    private val waiting = MutableStateFlow<List<CompletableDeferred<Unit>>>(emptyList())
    private val rows = MutableStateFlow(listOfNotNull(CapabilityConsent(search, realm, device), CapabilityConsent(facesCapability, facesRealm, facesDevice).takeIf { faces }))
    private val roster = MutableStateFlow(others?.let { if (faces) mapOf(search to it, facesCapability to it) else mapOf(search to it) })

    /** ⚠ One step: two calls recorded at once keep the order they were recorded in. */
    private fun record(call: String) = shown.update { if (it.isEmpty()) call else "$it · $call" }

    /** A consent write, as recorded: search's as it always was, face grouping's under its own prefix. */
    private fun consentCall(capability: InferenceCapability, call: String): String = if (capability == facesCapability) "faces.$call" else call

    /**
     * Records [call], and when [held] suspends until the probe next advances. ⚠⚠ The wait is
     * registered BEFORE the call is recorded: a harness that reads the call and then advances
     * lets this one go, and never advances past a call that was not waiting yet.
     */
    private suspend fun recordAndHold(call: String, held: Boolean) {
        val gate = CompletableDeferred<Unit>().takeIf { held }
        gate?.let { mine -> waiting.update { it + mine } }
        record(call)
        gate?.await()
    }

    private val source = object : OnDeviceModelSource {
        override val models: Flow<ModelsAvailability> =
            list.onStart { armSelfAdvance() }.transformWhile { emit(it); it != ModelsAvailability.NotConnected }
        override val serves: Flow<Map<String, List<String>>> =
            MutableStateFlow(if (faces) mapOf(MODEL_ID to listOf(search.id), FACE_MODEL_ID to listOf(facesCapability.id)) else mapOf(MODEL_ID to listOf(search.id)))

        override suspend fun consentWithdrawn(capability: InferenceCapability) {
            // ⚠ What the real source does next (the operation's own withdrawal-first deletion) is not
            // this fixture's; only that it was told, and of which capability.
            if (capability == facesCapability) record("faces.withdrawn")
        }

        override suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult {
            record("download($modelId, ${if (allowsCellular) "mobile" else "wifi"})")
            return ModelActionResult.Accepted
        }

        override suspend fun cancel(modelId: String): ModelActionResult {
            record("cancel($modelId)")
            return ModelActionResult.Accepted
        }

        override suspend fun checkAgain() {
            recordAndHold("checkAgain", held = slowCheck)
        }

        override suspend fun loadAgain(modelId: String) {
            record("loadAgain($modelId)")
        }
    }

    private val consents = object : ModelsConsents {
        override fun consents(): Flow<List<CapabilityConsent>> = rows

        override suspend fun setRealmConsent(capability: InferenceCapability, allowed: Boolean): Boolean {
            record(consentCall(capability, "realm(${onOff(allowed)})"))
            rows.update { all -> all.map { if (it.capability == capability) it.copy(realm = allowed) else it } }
            return true
        }

        override suspend fun setDeviceConsent(capability: InferenceCapability, allowed: Boolean): Boolean {
            record(consentCall(capability, "device(${onOff(allowed)})"))
            // ⚠ As the real one: nothing is written under an id that is not known.
            if (!deviceKnown) return false
            rows.update { all -> all.map { if (it.capability == capability) it.copy(device = allowed) else it } }
            return true
        }

        override fun thisDeviceKnown(): Flow<Boolean> = MutableStateFlow(deviceKnown)

        override fun otherDeviceConsents(): Flow<Map<InferenceCapability, List<OtherDeviceConsent>>?> = roster

        override suspend fun clearOtherDeviceConsent(capability: InferenceCapability, deviceId: String): Boolean {
            record(consentCall(capability, "clear($deviceId)"))
            roster.update { known -> known?.mapValues { (_, devices) -> devices.map { if (it.deviceId == deviceId) it.copy(allowed = false) else it } } }
            return true
        }
    }

    private val indexState = MutableStateFlow(index)
    private val pausedRow = MutableStateFlow(index.paused)

    private val indexing = object : OnDeviceIndexSource {
        override val progress: IndexProgressSource = object : IndexProgressSource {
            override val progress: Flow<IndexPassPosition?> = indexState.map { it.running }.distinctUntilChanged()
        }
        override val coverage: IndexCoverageSource = object : IndexCoverageSource {
            override val coverage: Flow<IndexCoverage?> = indexState.map { it.coverage }.distinctUntilChanged()
        }
        override val standing: Flow<ForegroundIndexStanding> = indexState.map { it.standing }.distinctUntilChanged()
        override val stored: Flow<StoredIndex> = indexState.map { it.stored }.distinctUntilChanged()

        override suspend fun indexNow(): IndexStartOutcome {
            record("indexNow")
            return indexAnswer
        }
    }

    /** ⚠ As the real row: what was written is what is read. */
    private val pause = object : IndexingPause {
        override fun paused(): Flow<Boolean> = pausedRow

        override suspend fun set(paused: Boolean) {
            record("pause(${onOff(paused)})")
            pausedRow.value = paused
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ⚠ Below everything it reads: the repository is built over the fields above.
    val repository: ModelsRepository =
        if (notOffered != null) {
            OnDeviceModelsRepository.notOffered(notOffered, scope)
        } else {
            OnDeviceModelsRepository(
                source = source,
                notOffered = null,
                capabilities = if (faces) OnDeviceModelsCapabilities.copy(features = listOf(search, facesCapability), modelFeatures = mapOf(MODEL_ID to search, FACE_MODEL_ID to facesCapability)) else OnDeviceModelsCapabilities,
                remove = { modelId ->
                    recordAndHold("delete($modelId)", held = removal == null)
                    removal ?: ModelDeleteOutcome.Removed
                },
                consents = consents,
                scope = scope,
                indexing = indexing,
                pause = pause,
            )
        }

    private fun advance() {
        // What waits on the probe is let go first; the list then takes its next scripted value.
        val gates = waiting.getAndUpdate { emptyList() }
        val at: Int = step.getAndUpdate { it + 1 }
        script.getOrNull(at)?.let { list.value = it }
        indexScript.getOrNull(at)?.let { next ->
            indexState.value = next
            pausedRow.value = next.paused
        }
        gates.forEach { it.complete(Unit) }
    }

    val probe = PhotosModelsProbe(calls = shown, onWifi = onWifi) { advance() }

    private val selfAdvanceArmed = MutableStateFlow(false)

    /** Once, at the first read of the list. */
    private fun armSelfAdvance() {
        val after: Duration = advancesItselfAfter ?: return
        if (!selfAdvanceArmed.compareAndSet(expect = false, update = true)) return
        scope.launch {
            delay(after)
            advance()
        }
    }

    companion object {
        // ⚠ The published catalog's own entry (`deploy/models/models.json`, runtime `coreml`):
        // its id, name, version, licence and the sum of its three artifacts. What the screens
        // show over the fixture is what they show over the catalog.
        const val MODEL_ID = "siglip-base-patch16-256-multilingual"
        private const val NAME = "SigLIP base (multilingual)"
        const val DOWNLOAD_BYTES = 743_448_870L

        /** [DOWNLOAD_BYTES] and what preparing it takes (`CoreMlDiskBudget.prepareBytes`; held to it in `ModelsViewModelOnNativeTest`). */
        const val ON_DEVICE_BYTES = 2_579_969_593L
        private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
        private val facesCapability = InferenceCapability.FACE_CLUSTERING

        // ⚠ The face entry of `deploy/models/models.json` (runtime `coreml+vision`): its id, name,
        // licence and its one archive's size; on-device is that and `CoreMlDiskBudget.prepareBytes`.
        const val FACE_MODEL_ID = "auraface-v1-int8"
        private const val FACE_NAME = "People (AuraFace + Apple Vision)"
        const val FACE_DOWNLOAD_BYTES = 130_631_680L
        const val FACE_ON_DEVICE_BYTES = 453_327_426L
        private val faceOffered = OfferedModel(FACE_MODEL_ID, FACE_NAME, 1, downloadBytes = FACE_DOWNLOAD_BYTES, onDeviceBytes = FACE_ON_DEVICE_BYTES, licence = "Apache-2.0")
        private val faceHeld = HeldModel(FACE_MODEL_ID, FACE_NAME, 1, onDeviceBytes = FACE_ON_DEVICE_BYTES, licence = "Apache-2.0")

        /** Search's model ready, and the face model as [face] says, in the order the real source lists them. */
        private fun withFaces(face: ModelSetup) = ModelsAvailability.Listed(
            ModelsAvailability.Catalog.READ,
            ModelsAvailability.Downloads.KNOWN,
            listOf(ModelRow(MODEL_ID, NAME, ModelSetup.Held(heldModel, Standing.Ready, ModelSetup.Update.None)), ModelRow(FACE_MODEL_ID, FACE_NAME, face)),
        )
        private val faceReady = ModelSetup.Held(faceHeld, Standing.Ready, ModelSetup.Update.None)
        private fun onOff(allowed: Boolean) = if (allowed) "on" else "off"

        private val offered = OfferedModel(MODEL_ID, NAME, 1, downloadBytes = DOWNLOAD_BYTES, onDeviceBytes = ON_DEVICE_BYTES, licence = "Apache-2.0")
        private val newer = offered.copy(modelVersion = 2, downloadBytes = 766_000_000)
        private val heldModel = HeldModel(MODEL_ID, NAME, 1, onDeviceBytes = ON_DEVICE_BYTES, licence = "Apache-2.0")

        private fun listed(setup: ModelSetup, downloads: ModelsAvailability.Downloads = ModelsAvailability.Downloads.KNOWN) =
            ModelsAvailability.Listed(ModelsAvailability.Catalog.READ, downloads, listOf(ModelRow(MODEL_ID, NAME, setup)))

        private fun absent(last: DownloadSaid? = null) = listed(ModelSetup.Absent(offered, last))
        private fun download(written: Long?, held: DownloadSaid? = null) = ModelSetup.Download(allowsCellular = false, written, DOWNLOAD_BYTES, held)
        private fun fetching(written: Long? = 371_700_000, held: DownloadSaid? = null) = listed(ModelSetup.Fetching(offered, download(written, held)))
        private fun held(standing: Standing, update: ModelSetup.Update = ModelSetup.Update.None) = listed(ModelSetup.Held(heldModel, standing, update))
        private val ready = held(Standing.Ready)
        private val space = DownloadSaid.NeedsSpace(neededBytes = 3_400_000_000, freeBytes = 1_200_000_000)
        /** The last pass walked 1,416 photographs, 106 of them not on this phone: 1,310 it can reach. */
        private val reach = IndexCoverage(total = 1_416, notOnDevice = 106)
        private fun waiting(why: ForegroundIndexStanding.Waiting.Why) = ForegroundIndexStanding.Waiting(why)
        private fun stopped(why: ForegroundIndexStanding.Stopped.Why) = ForegroundIndexStanding.Stopped(why)
        private fun indexed(first: IndexStep, script: List<IndexStep> = emptyList(), answer: IndexStartOutcome = IndexStartOutcome.Started) =
            PhotosModelsFixture(ready, realm = true, device = true, index = first, indexScript = script, indexAnswer = answer)
        private val others = listOf(OtherDeviceConsent("mac", "Alex's Mac", allowed = true), OtherDeviceConsent("pad", "iPad", allowed = false))

        /** The state named, or `null`. ⚠ The names are the harness's (`ModelsUITests`); one per main row of §14.18.3. */
        fun of(state: String): PhotosModelsFixture? = when (state) {
            "loading" -> PhotosModelsFixture(ModelsAvailability.Loading)
            "notConnected" -> PhotosModelsFixture(ModelsAvailability.NotConnected)
            "installedUnreadable" -> PhotosModelsFixture(ModelsAvailability.InstalledUnreadable)
            // ⚠ *Try again* stays unanswered until the probe advances, and then reads a list.
            "slowCheck" -> PhotosModelsFixture(ModelsAvailability.InstalledUnreadable, slowCheck = true, script = listOf(absent()))
            "catalogUnavailable" ->
                PhotosModelsFixture(ModelsAvailability.Listed(ModelsAvailability.Catalog.UNAVAILABLE, ModelsAvailability.Downloads.KNOWN, emptyList()))
            "nothingPublished" ->
                PhotosModelsFixture(ModelsAvailability.Listed(ModelsAvailability.Catalog.READ, ModelsAvailability.Downloads.KNOWN, emptyList()))
            "notOfferedOs" -> PhotosModelsFixture(ModelsAvailability.Loading, notOffered = ModelsAvailability.NotOffered.Why.OS_TOO_OLD)
            "notOfferedMemory" -> PhotosModelsFixture(ModelsAvailability.Loading, notOffered = ModelsAvailability.NotOffered.Why.NOT_ENOUGH_MEMORY)
            // The whole way of a first install, a step per advance; the last one asks the feature question.
            "absent" -> PhotosModelsFixture(
                absent(),
                script = listOf(
                    fetching(212_700_000), held(Standing.Preparing(Standing.Preparing.How.Now)),
                    held(Standing.Checking(Standing.Checking.How.Running)), ready,
                ),
            )
            "needsSpace" -> PhotosModelsFixture(absent(space))
            "freeSpaceUnknown" -> PhotosModelsFixture(absent(DownloadSaid.FreeSpaceUnknown))
            "downloadFailed" -> PhotosModelsFixture(absent(DownloadSaid.Failed(integrity = false)))
            "integrityFailed" -> PhotosModelsFixture(absent(DownloadSaid.Failed(integrity = true)))
            // ⚠ A cancel is accepted and the row stays: it goes when the probe advances.
            "fetching" -> PhotosModelsFixture(fetching(), onWifi = true, script = listOf(absent(DownloadSaid.Cancelled)))
            "fetchingNoWifi" -> PhotosModelsFixture(fetching(), onWifi = false)
            "pausedForSpace" -> PhotosModelsFixture(fetching(held = space))
            // 8c's other reasons, one sentence each.
            "pausedFreeSpaceUnknown" -> PhotosModelsFixture(fetching(held = DownloadSaid.FreeSpaceUnknown))
            "pausedNoTransport" -> PhotosModelsFixture(fetching(held = DownloadSaid.NoTransport))
            "pausedTransfersUnknown" -> PhotosModelsFixture(fetching(held = DownloadSaid.TransfersUnknown))
            "pausedStoreRefused" -> PhotosModelsFixture(fetching(held = DownloadSaid.StoreRefused))
            "fetchFailed" -> PhotosModelsFixture(fetching(held = DownloadSaid.Failed(integrity = false)))
            "journalUnreadable" -> PhotosModelsFixture(held(Standing.Ready, ModelSetup.Update.Unknown).copy(downloads = ModelsAvailability.Downloads.UNREADABLE))
            "preparing" -> PhotosModelsFixture(held(Standing.Preparing(Standing.Preparing.How.Now)))
            "prepareFailed" -> PhotosModelsFixture(held(Standing.Preparing(Standing.Preparing.How.Failed("vision", ModelSetup.Space(3_400_000_000, 1_200_000_000)))))
            "incomplete" -> PhotosModelsFixture(held(Standing.Preparing(Standing.Preparing.How.Incomplete)))
            "preparingUnreadable" -> PhotosModelsFixture(held(Standing.Preparing(Standing.Preparing.How.Unreadable)))
            "checking" -> PhotosModelsFixture(held(Standing.Checking(Standing.Checking.How.Running)))
            "checkPaused" -> PhotosModelsFixture(held(Standing.Checking(Standing.Checking.How.Paused(Standing.Checking.Why.HOT))))
            // 10b: a probe that failed is checked again, and says only that it is checking.
            "checkRetried" -> PhotosModelsFixture(held(Standing.Checking(Standing.Checking.How.Pending)))
            "noProbe" -> PhotosModelsFixture(held(Standing.Checking(Standing.Checking.How.NoProbe)))
            "checkUnreadable" -> PhotosModelsFixture(held(Standing.Checking(Standing.Checking.How.Unreadable)))
            // A removal asked for, then the model lands and the feature question comes: asked
            // after the removal is answered, in Kotlin's order.
            // ⚠ It lands by itself, eight seconds after the screen first read the list: under the removal's alert the
            // probe's button cannot be pressed.
            "removalThenOffer" -> PhotosModelsFixture(held(Standing.Checking(Standing.Checking.How.Running)), script = listOf(ready), advancesItselfAfter = 8.seconds)
            "installFailed" -> PhotosModelsFixture(held(Standing.Checking(Standing.Checking.How.InstallFailed)))
            // A removal runs until the probe advances; the list then shows the model gone.
            "ready" -> PhotosModelsFixture(ready, script = listOf(absent()))
            "removeFails" -> PhotosModelsFixture(ready, removal = ModelDeleteOutcome.Failed)
            "readyOn" -> PhotosModelsFixture(ready, realm = true, device = true, others = others, script = listOf(absent()))
            "rosterUnreadable" -> PhotosModelsFixture(ready, realm = true, device = true, others = null)
            "deviceUnknown" -> PhotosModelsFixture(ready, others = null, deviceKnown = false)
            "updateAvailable" -> PhotosModelsFixture(held(Standing.Ready, ModelSetup.Update.Available(newer)))
            // 11a: no catalog, so no update is claimed and none ruled out.
            "catalogFailedReady" -> PhotosModelsFixture(held(Standing.Ready, ModelSetup.Update.Unknown))
            "updating" -> PhotosModelsFixture(held(Standing.Ready, ModelSetup.Update.Fetching(download(212_700_000))))
            "unsuitable" -> PhotosModelsFixture(held(Standing.Unsuitable))
            // §14.5 item 0: loads Photos did not survive, and the answers a download would meet.
            "stoppedLoading" -> PhotosModelsFixture(held(Standing.StoppedLoading))
            "absentUnsuitable" -> PhotosModelsFixture(listed(ModelSetup.Absent(offered, null, answered = ModelSetup.Answered.UNSUITABLE)))
            "absentStoppedLoading" -> PhotosModelsFixture(listed(ModelSetup.Absent(offered, null, answered = ModelSetup.Answered.STOPPED_LOADING)))
            // A press whose caller stopped waiting: the call still starting, then refused late.
            "stillStarting" -> PhotosModelsFixture(listed(ModelSetup.Absent(offered, null, pressed = ModelSetup.Pressed.Starting)))
            "refusedLate" -> PhotosModelsFixture(listed(ModelSetup.Absent(offered, null, pressed = ModelSetup.Pressed.Refused(ModelActionResult.Why.NoCatalog))))
            // ─── The indexing section (§14.19.6), over a ready model with search on.
            // Not asked; a step starts it, the next ends it.
            "indexNotAsked" -> indexed(
                IndexStep(coverage = reach, stored = StoredIndex(1_204)),
                script = listOf(
                    IndexStep(ForegroundIndexStanding.Asked, running = IndexPassPosition(412, 1_310), coverage = reach, stored = StoredIndex(1_204)),
                    IndexStep(coverage = IndexCoverage(1_416, 106), stored = StoredIndex(1_310)),
                ),
            )
            "indexRunning" -> indexed(IndexStep(ForegroundIndexStanding.Asked, running = IndexPassPosition(412, 1_310), coverage = reach, stored = StoredIndex(1_204)))
            "indexWarm" -> indexed(IndexStep(waiting(ForegroundIndexStanding.Waiting.Why.WARM), coverage = reach, stored = StoredIndex(640)))
            "indexLowPower" -> indexed(IndexStep(waiting(ForegroundIndexStanding.Waiting.Why.LOW_POWER_MODE), coverage = reach, stored = StoredIndex(640)))
            "indexBatteryLow" -> indexed(IndexStep(waiting(ForegroundIndexStanding.Waiting.Why.BATTERY_LOW), coverage = reach, stored = StoredIndex(640)))
            "indexLeftPhotos" -> indexed(IndexStep(stopped(ForegroundIndexStanding.Stopped.Why.LEFT_THE_APP), stored = StoredIndex(640)))
            "indexLowMemory" -> indexed(IndexStep(stopped(ForegroundIndexStanding.Stopped.Why.LOW_MEMORY), stored = StoredIndex(640)))
            "indexPaused" -> indexed(IndexStep(coverage = reach, stored = StoredIndex(1_204), paused = true))
            // ⚠ After an iOS update: the store is empty and says why; no pass has finished since.
            "indexAfterUpdate" -> indexed(IndexStep(stored = StoredIndex(0, clearedByOsUpdate = true)))
            // The model is ready and search is off everywhere: no button, the switch named.
            "indexSwitchOff" -> PhotosModelsFixture(ready, index = IndexStep(stored = StoredIndex(0)))
            // A press the phone holds back: it says no message, and the section moves when the probe does.
            // ─── Face grouping beside search (`ios-face-grouping.md` F6).
            // The face model not downloaded and its rows ABSENT (never written): a step per advance
            // takes it through the download, the prepare and the probe to ready, and the last asks.
            "facesAbsent" -> PhotosModelsFixture(
                withFaces(ModelSetup.Absent(faceOffered, null)),
                realm = true, device = true, faces = true,
                script = listOf(
                    withFaces(ModelSetup.Fetching(faceOffered, ModelSetup.Download(allowsCellular = false, 52_000_000, FACE_DOWNLOAD_BYTES, null))),
                    withFaces(ModelSetup.Held(faceHeld, Standing.Preparing(Standing.Preparing.How.Now), ModelSetup.Update.None)),
                    withFaces(ModelSetup.Held(faceHeld, Standing.Checking(Standing.Checking.How.Running), ModelSetup.Update.None)),
                    withFaces(faceReady),
                ),
            )
            // Both models ready, face grouping on here and on a Mac.
            "facesOn" -> PhotosModelsFixture(withFaces(faceReady), realm = true, device = true, others = others, faces = true, facesRealm = true, facesDevice = true)
            "indexPressWaits" -> indexed(
                IndexStep(coverage = reach, stored = StoredIndex(1_204)),
                script = listOf(IndexStep(waiting(ForegroundIndexStanding.Waiting.Why.WARM), coverage = reach, stored = StoredIndex(1_204))),
                answer = IndexStartOutcome.Waiting,
            )
            else -> null
        }
    }
}
