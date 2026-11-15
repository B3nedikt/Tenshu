package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.ForegroundIndexStanding
import app.tenshu.control.inference.IndexCoverage
import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.StoredIndex
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelSetup.DownloadSaid
import app.tenshu.control.inference.ModelSetup.Standing
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.OfferedModel
import app.tenshu.control.inference.download
import app.tenshu.control.inference.usable
import app.tenshu.control.inference.offersRemoval
import org.tenshu.inference.InferenceCapability

/**
 * Photos' Models screen **as values** (`ios-on-device-inference.md` §14.18.3, §14.18.14): what
 * [photosModelsScreen] makes of a [ModelsUiState]. A SwiftUI view draws it and decides nothing:
 * which sentence a state gets, and which buttons, is here and is tested as that table.
 */
data class PhotosModelsScreen(
    val body: Body,
    /**
     * The feature rows under the list, one per switch the screen offers, search first; empty where
     * none is (`ios-face-grouping.md` F6: face grouping's beside search's where faces are bound).
     */
    val features: List<Feature>,
    /** [ModelsUiState.message], as its sentence. */
    val message: String?,
    /** The message [message] says, for [photosModelsMessageSeen]: what *OK* may clear, and nothing else. */
    val messageShown: ModelsMessage?,
    /**
     * The one question up, **and which**: a removal the user has just asked for comes before an
     * offer that arrived by itself, which is asked when the removal is answered. ⚠ Kotlin's choice,
     * not the shell's: the second question must not rest on SwiftUI presenting again on its own.
     */
    val question: Asked?,
    /**
     * *Try again* was pressed and the list is being read again. ⚠ While it is, no *Try again* is
     * offered anywhere on the screen (a second press does nothing), and this says why.
     */
    val checkingAgain: Boolean,
    /** What the screen says while [checkingAgain], in place of the *Try again* it withholds. */
    val checking: String? = null,
    /** The indexing section (§14.19.6), or `null` where it is not drawn. */
    val indexing: Indexing? = null,
) {
    /** The first feature row (search's), or `null` where no switch is offered. */
    val feature: Feature? get() = features.firstOrNull()

    /**
     * The indexing section: where this device's index stands, and what may be pressed now.
     * ⚠ Kotlin's choice, in the order §14.19.6 gives; the shell draws it and decides nothing.
     */
    data class Indexing(
        val title: String,
        val status: String,
        /** What follows from [status], under it. */
        val detail: String?,
        /** What the index holds: *N of M photos … can be searched*, or why it is empty. */
        val coverage: String?,
        val progress: Progress?,
        /** ⚠ In the order drawn. Empty while nothing can be done. */
        val actions: List<IndexAction>,
    )

    /** A button of the indexing section; [photosIndexingPress] carries it out. */
    data class IndexAction(val kind: Kind, val label: String) {
        enum class Kind {
            /** `indexNow()`. */
            INDEX_NOW,

            /** `setIndexingPaused(true)`. */
            PAUSE,

            /** `setIndexingPaused(false)`. */
            RESUME,
        }
    }

    sealed interface Body {
        /** No Models entry at all: this build has no on-device model. */
        data object Hidden : Body

        /** No list. [tryAgain] is the button's label, or `null` where asking again changes nothing. */
        data class Notice(val text: String, val detail: String? = null, val tryAgain: String? = null) : Body

        /** @param notice what is wrong with the list as a whole, above the rows. */
        data class Rows(val rows: List<Row>, val notice: String? = null, val tryAgain: String? = null) : Body
    }

    data class Row(
        val modelId: String,
        val title: String,
        /** The sizes and the licence. */
        val facts: String,
        /** Where the model stands, or `null` when the facts say all there is. */
        val status: String?,
        /** What follows from it for the user, under [status]. */
        val detail: String?,
        val progress: Progress?,
        /** ⚠ In the order drawn. Empty while nothing can be done to the row. */
        val actions: List<Action>,
    )

    sealed interface Progress {
        /** [fraction] in 0..1 of the whole checkpoint. */
        data class Bar(val fraction: Double) : Progress

        /** Something runs, and no honest fraction exists for it. */
        data object Busy : Progress
    }

    /** A button: [kind] is what it calls on the view model, [label] what it says. */
    data class Action(val kind: Kind, val label: String) {
        enum class Kind {
            /** `download(modelId, allowsCellular = false)`. */
            DOWNLOAD,

            /** `download(modelId, allowsCellular = true)`. */
            DOWNLOAD_WITH_MOBILE_DATA,

            /** `cancel(modelId)`. */
            CANCEL,

            /** `confirmDelete(modelId)`. */
            REMOVE,

            /** `retry()`. */
            TRY_AGAIN,

            /** `loadAgain(modelId)`: once more after loads Photos did not survive stopped it (§14.5). */
            LOAD_AGAIN,
        }
    }

    /** The feature row. A press on it opens the feature's own screen ([photosFeatureScreen]). */
    data class Feature(
        val capability: InferenceCapability,
        val title: String,
        val status: String,
        val detail: String?,
    )

    data class Question(val title: String, val text: String, val yes: String, val no: String)

    /** A [question] and what answering it does ([photosModelsAnswer]). */
    data class Asked(val kind: Kind, val question: Question) {
        enum class Kind { REMOVAL, FEATURE_OFFER }
    }
}

/**
 * Whether the list says this phone cannot run the model serving [capability] ([servedBy]):
 * installed and `Unsuitable`, or absent with the key's answer *unsuitable*. ⚠ Not *stopped
 * loading*: that has a *Try again*.
 */
private fun ModelsUiState.unsuitableFor(capability: InferenceCapability): Boolean = servedBy(capability).any { it.unsuitable }

private val ModelRow.unsuitable: Boolean
    get() = when (val setup = setup) {
        is ModelSetup.Held -> setup.standing == ModelSetup.Standing.Unsuitable
        is ModelSetup.Absent -> setup.answered == ModelSetup.Answered.UNSUITABLE
        is ModelSetup.Fetching -> false
    }

/**
 * The capability [modelId] serves as far as this screen can tell (`ios-face-grouping.md` F6): what
 * the catalog says it serves; before the catalog was read (a cold, offline launch), what the graph
 * says the model it prepares serves ([ModelsCapabilities.modelFeatures]); else search's, which is
 * what the screen said before a second model existed.
 */
internal fun ModelsUiState.capabilityOf(modelId: String): InferenceCapability {
    val served: List<String>? = serves[modelId]
    if (served != null) {
        return if (InferenceCapability.FACE_CLUSTERING.id in served) InferenceCapability.FACE_CLUSTERING else InferenceCapability.SEMANTIC_IMAGE_SEARCH
    }
    return capabilities.modelFeatures[modelId] ?: InferenceCapability.SEMANTIC_IMAGE_SEARCH
}

/** The rows whose model serves [capability] ([capabilityOf]). */
private fun ModelsUiState.servedBy(capability: InferenceCapability): List<ModelRow> =
    models.filter { capabilityOf(it.modelId) == capability }

/**
 * Whether a model serving [capability] can be used here, as [ModelsUiState.anyModelUsable] asks
 * it of all of them. ⚠ With one feature on the screen this IS [ModelsUiState.anyModelUsable].
 */
private fun ModelsUiState.usableFor(capability: InferenceCapability): Boolean? {
    if (capabilities.features.size <= 1) return anyModelUsable
    val list: ModelsAvailability.Listed = availability as? ModelsAvailability.Listed ?: return null
    val rows: List<ModelRow> = list.models.filter { capabilityOf(it.modelId) == capability }
    if (rows.any { it.setup.usable }) return true
    return if (rows.all { it.setup.knownNotUsable }) false else null
}

/** An answer to [PhotosModelsScreen.question]: the one place a kind becomes a call. */
fun photosModelsAnswer(viewModel: ModelsViewModel, kind: PhotosModelsScreen.Asked.Kind, yes: Boolean) = when (kind) {
    PhotosModelsScreen.Asked.Kind.REMOVAL -> if (yes) viewModel.delete() else viewModel.dismissDelete()
    PhotosModelsScreen.Asked.Kind.FEATURE_OFFER -> if (yes) viewModel.acceptFeatureOffer() else viewModel.dismissFeatureOffer()
}

/**
 * *OK* on a message: clears [shown] and nothing else. ⚠⚠ A message that replaced it before the
 * press is not cleared unseen by it, as `consumeMessage()` would clear whatever is there.
 */
fun photosModelsMessageSeen(viewModel: ModelsViewModel, shown: ModelsMessage) = viewModel.consumeMessage(shown)

/**
 * Every sentence of Photos' Models screen, each one a value (M11: the placeholders of §14.18.3
 * stand until they are edited, here). [device] is the shell's noun for itself (*iPhone*, *iPad*).
 */
object PhotosModelsCopy {
    /** The Library's toolbar button, the list behind it, and its one row: the path [ModelsSwitchHome] names. */
    const val SETTINGS = "Settings"
    const val MODELS = "Models"
    const val MODELS_ROW_DETAIL = "Search photos by description, on this device"
    const val DONE = "Done"
    const val OK = "OK"
    const val CHECKING_AGAIN = "Checking again…"

    const val TRY_AGAIN = "Try again"
    const val DOWNLOAD = "Download"
    const val DOWNLOAD_AGAIN = "Download again"
    const val USE_MOBILE_DATA = "Use mobile data"
    const val UPDATE = "Update"
    const val UPDATE_WITH_MOBILE_DATA = "Update using mobile data"
    const val CANCEL = "Cancel"
    const val REMOVE = "Remove"
    const val NOT_NOW = "Not now"
    const val TURN_ON = "Turn on"

    const val LOADING = "Loading…"
    const val NOT_CONNECTED = "Photos can't reach its library right now."
    const val CATALOG_UNAVAILABLE = "The list of models couldn't be loaded."
    const val CATALOG_STALE = "Couldn't refresh the list of models. Showing it as it was last read."
    const val JOURNAL_UNREADABLE = "Couldn't read the download's record."
    const val OS_TOO_OLD = "It needs iOS 17 or later."

    fun notOffered(device: String) = "Search by description isn't available on this $device."
    fun notEnoughMemory(device: String) = "This $device doesn't have enough memory for the model."
    fun installedUnreadable(device: String) = "Couldn't read what's installed on this $device."
    fun nothingPublished(device: String) = "No model is available for this $device yet."

    fun downloadSize(bytes: Long) = "${photosModelBytes(bytes)} download"
    fun onDevice(bytes: Long, device: String) = "about ${photosModelBytes(bytes)} on this $device"
    fun updateAvailable(bytes: Long) = "Update available · ${photosModelBytes(bytes)}"

    const val STARTING = "Starting…"
    const val REMOVING = "Removing…"
    const val DOWNLOADING = "Downloading…"
    const val CAN_LEAVE = "You can leave Photos; the download continues."
    const val WAITING_FOR_WIFI = "Waiting for Wi-Fi."

    /** M6: no switch after a Wi-Fi-only download has started. */
    const val CELLULAR_NEEDS_A_NEW_START = "To use mobile data, cancel and start again. What was downloaded is dropped."

    fun downloaded(written: Long, total: Long) = "${photosModelBytes(written)} of ${photosModelBytes(total)}"
    fun needsSpace(needed: Long, free: Long, device: String) =
        "Needs ${photosModelBytes(needed)} free. This $device has ${photosModelBytes(free)}."
    fun pausedForSpace(needed: Long, free: Long, device: String) =
        "Paused. Needs ${photosModelBytes(needed)} free; this $device has ${photosModelBytes(free)}."

    const val FREE_SPACE_UNKNOWN = "Couldn't check the free space."
    const val NO_TRANSPORT = "Photos couldn't start the download just now."
    const val TRANSFERS_UNKNOWN = "Photos couldn't tell what is already downloading."
    const val STORE_REFUSED = "Photos couldn't save the model."
    const val PAUSED_FREE_SPACE_UNKNOWN = "Paused. Photos couldn't check the free space."
    const val PAUSED_NO_TRANSPORT = "Paused. Photos couldn't continue the download just now."
    const val PAUSED_TRANSFERS_UNKNOWN = "Paused. Photos couldn't tell what is still downloading."
    const val PAUSED_STORE_REFUSED = "Paused. Photos couldn't save the model."
    const val DOWNLOAD_FAILED = "The download didn't finish."

    /** ⚠ No retry wording: asking again fetches the same file. The way out is to cancel. */
    const val HELD_INTEGRITY_FAILED = "The download didn't match what was published."
    const val INTEGRITY_FAILED = "The download didn't match what was published. Trying again will fetch the same file."

    const val STANDING_NOT_READ = "Checking where the model stands…"

    /** ⚠ Placeholder copy (M11). Loads the process did not survive, twice; nothing else. */
    fun stoppedLoading(device: String) = "Photos stopped trying after it closed twice while loading the model on this $device."
    const val CONSENT_NOT_READ = "Checking whether it's on…"
    const val STANDING_UNREADABLE = "Couldn't read where the model stands."
    const val PREPARING = "Preparing…"
    const val PREPARING_NOW = "Preparing… Keep Photos open. About ten seconds."
    const val PREPARING_NEEDS_FOREGROUND = "Preparing goes on while Photos is open."
    const val PREPARE_FAILED = "Couldn't prepare the model."
    const val INCOMPLETE = "Part of the model is missing."
    const val FILES_UNREADABLE = "Couldn't read the model's files."
    const val CHECK_PAUSED = "Checking paused. Photos will try again the next time you open it."
    const val CHECK_NO_PROBE = "This version of Photos can't check this model. Update Photos."
    const val CHECK_UNREADABLE = "Couldn't read the result of the check."
    const val CHECK_SLOW = "The last check took too long."
    const val READY = "Ready"

    fun checking(device: String) = "Checking this $device…"
    fun warm(device: String) = "$device is warm."
    fun lowOnMemory(device: String) = "$device is low on memory."

    /** M12: the way out of a failed install is the removal, and the sentence says so. */
    fun installFailed(device: String) =
        "The model didn't work on this $device after it was installed. Remove it and download it again."
    fun unsuitable(device: String) = "This $device couldn't run the model well enough, so search by description stays off."

    const val FEATURE = "Search photos by description"
    /**
     * ⚠ Promises nothing the code does not do: search finds nothing until a pass
     * has run, and nothing on iOS starts one by itself.
     */
    const val STARTS_WHEN_READY = "It works once the model is ready and your photos are indexed."

    fun featureOn(device: String) = "On for this $device"
    fun featureOff(device: String) = "Off for this $device"
    fun removeTitle(name: String) = "Remove $name?"
    fun removeText(bytes: Long, device: String) =
        "This frees about ${photosModelBytes(bytes)} on this $device. Search by description stops working here " +
            "until a model is downloaded again. The switch for it is not changed."
    fun offerTitle(device: String) = "Turn on \"$FEATURE\" for this $device?"
    const val OFFER_TEXT = "The model is ready. Photos looks through your library when you tap Index now, on this device only."

    // ─── The feature's own screen (§38's shape, M4)

    const val FEATURE_DESCRIPTION =
        "Find photos by what is in them. Photos looks through your library on this device when you tap Index now, " +
            "with a model it has downloaded. Nothing leaves the device."
    const val DEVICES = "Devices"
    const val UNNAMED_DEVICE = "Unnamed device"
    const val DEVICE_ON = "On"
    const val DEVICE_OFF = "Off"
    const val OTHER_DEVICE_OFF = "Off. It is switched on from that device."
    const val TURN_OFF_EVERYWHERE = "Turn off everywhere"
    const val TURN_OFF = "Turn off"
    const val KEEP_ON = "Keep on"
    const val TURN_OFF_EVERYWHERE_TEXT = "Every device stops indexing, and searching by description stops working."
    const val OTHER_DEVICES_NOT_READ = "Checking your other devices…"
    const val OTHER_DEVICES_UNREADABLE = "Couldn't read your other devices, so only this one is shown."

    fun thisDevice(device: String) = "This $device"
    fun thisDeviceNotSetUp(device: String) = "This $device hasn't finished setting up, so the switch can't be saved yet."

    /** A stored yes that the realm's no holds back: shown on, so that it can be switched off. */
    const val ON_BUT_OFF_EVERYWHERE = "On here, and off for all your devices. Switch it off and on again to use it."
    val turnOffEverywhereTitle = "Turn off \"$FEATURE\" everywhere?"

    // ─── Face grouping (`ios-face-grouping.md` F6). ⚠⚠ Android's words wherever Android has them
    // (`consentHeadline`, `featureDescription`, `turnOffText`, `turnOffEverywhereLabel` in
    // `:control-shared`): one feature, one way of saying what switching it off deletes.

    /** Android's `consentHeadline(FACE_CLUSTERING)`. */
    const val FACES_FEATURE = "Group photos by people"

    /**
     * Android's `featureDescription(FACE_CLUSTERING)`. ⚠⚠ Biometric data (threat model A40): where
     * the face data lives, that only typed names travel, and what switching off removes, all said
     * before the user agrees (IN-D3).
     */
    const val FACES_DESCRIPTION =
        "Find faces in your photos and group them so you can name people. Face data is made on " +
            "each device you allow and never leaves it. Only the names you type sync to your " +
            "devices. Turning this off deletes the face data and keeps the names."

    /** ⚠⚠ Said wherever the face switch is on: what a press on it would delete, before it is pressed. */
    fun facesOffDeletes(device: String) = "Turning this off deletes this $device's face data; names stay."

    /** The question *Turn off* asks first, here. ⚠ Android's `turnOffTitle` / `turnOffText`, naming this device. */
    fun facesTurnOffTitle(device: String) = "Turn off \"$FACES_FEATURE\" on this $device?"
    fun facesTurnOffText(device: String) =
        "This $device's face data is deleted. The names you typed are kept, and other devices are not changed."

    /** The same question for another device of the realm, by its name. */
    fun facesTurnOffOtherTitle(name: String) = "Turn off \"$FACES_FEATURE\" on $name?"
    const val FACES_TURN_OFF_OTHER_TEXT = "Its face data is deleted. The names you typed are kept, and other devices are not changed."

    /** Android's `turnOffEverywhereLabel` and `turnOffText(everywhere = true)` for faces. */
    const val FACES_TURN_OFF_EVERYWHERE = "Turn off everywhere and delete face data"
    val facesTurnOffEverywhereTitle = "Turn off \"$FACES_FEATURE\" everywhere?"
    const val FACES_TURN_OFF_EVERYWHERE_TEXT = "Face data is deleted on every device. The names you typed are kept."

    fun facesOfferTitle(device: String) = "Turn on \"$FACES_FEATURE\" for this $device?"
    fun facesOfferText(device: String) =
        "The model is ready. Photos looks for faces in your library when you tap Index now. Face data is made on " +
            "this $device and never leaves it; only the names you type sync. Turning this off deletes the face data and keeps the names."

    /** ⚠ Removing the model deletes no face data: only switching the feature off does. */
    fun facesRemoveText(bytes: Long, device: String) =
        "This frees about ${photosModelBytes(bytes)} on this $device. Grouping photos by people stops working here " +
            "until a model is downloaded again. The switch for it is not changed, and face data already made stays until you turn it off."

    fun facesUnsuitable(device: String) = "This $device couldn't run the model well enough, so grouping photos by people stays off."

    /** The feature's name, as its row and its screen say it. */
    fun featureTitle(capability: InferenceCapability): String =
        if (capability == InferenceCapability.FACE_CLUSTERING) FACES_FEATURE else FEATURE

    // ─── The indexing section (§14.19.6). ⚠ Placeholder copy (M11), like the rest.

    const val INDEXING = "Indexing"
    const val INDEX_NOW = "Index now"
    const val PAUSE = "Pause"
    const val RESUME = "Resume"
    const val INDEX_WHEN_READY = "You can index your photos once the model is ready."
    const val INDEX_NEEDS_THE_SWITCH = "Turn on \"$FEATURE\" above to index your photos."

    /** [INDEX_NEEDS_THE_SWITCH] naming every switch the screen offers (`ios-face-grouping.md` F6). */
    fun indexNeedsTheSwitch(features: List<String>): String =
        if (features.size <= 1) "Turn on \"${features.firstOrNull() ?: FEATURE}\" above to index your photos."
        else "Turn on ${features.joinToString(" or ") { "\"$it\"" }} above to index your photos."

    /** A switch is on, and the model it needs is not ready: nothing could index yet. */
    fun indexWhenModelReady(feature: String) = "You can index your photos once the model for \"$feature\" is ready."

    /** A switch is on, and this phone cannot run the model it needs. */
    fun indexCannotRun(feature: String, device: String) = "This $device can't run the model for \"$feature\", so it can't index for it."
    const val INDEX_SWITCH_OFF_EVERYWHERE = "It is off for all your devices."
    fun indexSwitchOffHere(device: String) = "It is off for this $device."
    fun indexPaused(device: String) = "Indexing is paused on this $device."
    fun indexingCount(done: Int, total: Int) = "Indexing ${count(done)} of ${count(total)}"
    const val INDEX_LOOKING = "Looking through your library…"
    /**
     * The running line while a leave would stop the pass. ⚠⚠ Only then ([runningDetail]): on iOS 26+
     * a continued task may carry the pass, and this sentence is then false (§15.7 Q2).
     */
    const val INDEX_KEEP_OPEN = "Keep Photos open. Indexing stops when you leave."

    /** The running line while a continued task carries the pass (iOS 26+, §15): the system shows its progress. */
    const val INDEX_GOES_ON = "You can leave Photos. Indexing goes on, and iOS shows how far it has got."

    /**
     * What the running pass does when the user leaves, as the section's line. ⚠⚠ [continued] is the
     * runtime's word that a launched task carries the pass (`OnDeviceIndexSource.continued`), never
     * a guess from the OS version: a request submitted and not yet launched, or refused, is still a
     * pass a leave stops (§15.2), so it keeps [INDEX_KEEP_OPEN].
     */
    fun runningDetail(continued: Boolean): String = if (continued) INDEX_GOES_ON else INDEX_KEEP_OPEN
    fun indexWarm(device: String) = "Waiting: this $device is warm."
    const val INDEX_LOW_POWER = "Waiting: Low Power Mode is on."
    const val INDEX_BATTERY_LOW = "Waiting: the battery is low."
    const val INDEX_GOES_ON_COOLED = "It goes on by itself once it has cooled down, while Photos stays open."
    const val INDEX_GOES_ON_LOW_POWER = "It goes on by itself when Low Power Mode is off, while Photos stays open."
    const val INDEX_GOES_ON_BATTERY = "It goes on by itself when charging, while Photos stays open."
    const val INDEX_STOPPED_LEFT = "Stopped when you left Photos."
    fun indexStoppedMemory(device: String) = "Stopped: $device ran low on memory."
    /**
     * The idle section's first line where nothing is known of the index yet. ⚠ Idle has no *"not
     * indexing"* line: its first line is the coverage line when there is one.
     */
    const val INDEX_PROMPT = "Index your photos to search them by what is in them."
    fun indexNotOnDevice(n: Int, device: String) = "${count(n)} aren't on this $device."

    /**
     * ⚠⚠ *Can be searched*, never *indexed*: the store's own count is what search
     * finds, and the pass's reach is not what it indexed.
     */
    fun canBeSearched(searchable: Int, of: Int?, device: String): String = when {
        of != null && searchable <= of -> "${count(searchable)} of ${photos(of)} on this $device can be searched."
        searchable == 0 -> "No photos can be searched yet."
        else -> "${photos(searchable)} can be searched."
    }

    /** ⚠⚠ Not a bare *0 of N* after an update cleared the index (§14.18.16 item 3). */
    fun clearedByOsUpdate(searchable: Int?): String =
        if (searchable != null && searchable > 0) {
            "The index was cleared after an iOS update. ${photos(searchable)} can be searched again so far."
        } else {
            "The index was cleared after an iOS update. Index now to search your photos again."
        }

    /** The pass's reach alone, when the store's count is not known: what it could look at. */
    fun canBeIndexed(reachable: Int, device: String) = "${photos(reachable)} on this $device can be indexed."

    private fun count(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
    private fun photos(n: Int): String = if (n == 1) "1 photo" else "${count(n)} photos"

    /** A transient message's sentence. ⚠ Total: a message with no sentence would be a silent one. */
    fun message(message: ModelsMessage, device: String, continued: Boolean = false): String = when (message) {
        ModelsMessage.DownloadFailed -> DOWNLOAD_FAILED
        ModelsMessage.IntegrityFailed -> INTEGRITY_FAILED
        ModelsMessage.UnknownModel -> "That model is no longer offered."
        ModelsMessage.CatalogUnavailable -> CATALOG_UNAVAILABLE
        ModelsMessage.StoreReadOnly -> STORE_REFUSED
        // ⚠ What was compiled goes with the removal; the archives go when Photos next starts.
        ModelsMessage.RemovedSpacePending -> "Removed. The rest of its space is released the next time Photos starts."
        ModelsMessage.UpdatedSpacePending -> "Updated. The previous version's space is released the next time Photos starts."
        ModelsMessage.DeleteFailed -> "Couldn't remove the model. It is still installed."
        ModelsMessage.NotConnected -> NOT_CONNECTED
        ModelsMessage.NotEnoughSpace -> "There isn't enough free space on this $device."
        ModelsMessage.DownloadCouldNotStart -> NO_TRANSPORT
        ModelsMessage.OtherCellularChoice -> CELLULAR_NEEDS_A_NEW_START
        ModelsMessage.CancelFailed -> "Couldn't cancel the download. It goes on."
        ModelsMessage.ModelBusy -> "Something else is being done to this model. Try again in a moment."
        ModelsMessage.StillRemoving -> "Still removing…"
        ModelsMessage.ConsentNotSaved -> "Couldn't save that. Try again in a moment."
        ModelsMessage.OtherDeviceConsentNotSaved -> "Couldn't switch it off for that device."
        // ⚠ The press's answer. Where the phone holds it back the section says why instead
        // (`IndexStartOutcome.Waiting` says no message).
        // ⚠ The running line's rule ([runningDetail]): drawn from the state, so a task launched
        // after the press turns this line true as it is shown.
        ModelsMessage.IndexingStarted -> "Indexing started. ${runningDetail(continued)}"
        ModelsMessage.IndexNoModel -> "There is no model to index with yet."
        ModelsMessage.IndexNoConsent -> "\"$FEATURE\" is off for this $device."
        ModelsMessage.IndexPaused -> indexPaused(device)
        ModelsMessage.IndexNotReady -> "Photos can't index right now. Try again in a moment."
        ModelsMessage.IndexFailed -> "Indexing couldn't start."
    }
}

/** Decimal units, as iOS's own storage screen counts: *709 MB*, *2.6 GB*. */
fun photosModelBytes(bytes: Long): String {
    val megabytes: Long = (bytes.coerceAtLeast(0L) + 500_000L) / 1_000_000L
    if (megabytes < 1_000L) return "$megabytes MB"
    val tenths: Long = (bytes + 50_000_000L) / 100_000_000L
    return "${tenths / 10}.${tenths % 10} GB"
}

/**
 * [state] as Photos draws it.
 *
 * @param device the shell's noun for itself.
 * @param onWifi the platform's network monitor, or `null` while it has not answered or there is
 *   none: *waiting for Wi-Fi* is then not said (§14.18.3, row 8a).
 */
fun photosModelsScreen(state: ModelsUiState, device: String, onWifi: Boolean?): PhotosModelsScreen {
    val tryAgain: String = PhotosModelsCopy.TRY_AGAIN
    val body: PhotosModelsScreen.Body = when (val list: ModelsAvailability = state.availability) {
        is ModelsAvailability.NotOffered -> when (list.why) {
            ModelsAvailability.NotOffered.Why.NOT_IN_THIS_BUILD -> PhotosModelsScreen.Body.Hidden
            ModelsAvailability.NotOffered.Why.OS_TOO_OLD ->
                PhotosModelsScreen.Body.Notice(PhotosModelsCopy.notOffered(device), PhotosModelsCopy.OS_TOO_OLD)
            ModelsAvailability.NotOffered.Why.NOT_ENOUGH_MEMORY ->
                PhotosModelsScreen.Body.Notice(PhotosModelsCopy.notOffered(device), PhotosModelsCopy.notEnoughMemory(device))
        }
        ModelsAvailability.NotConnected -> PhotosModelsScreen.Body.Notice(PhotosModelsCopy.NOT_CONNECTED, tryAgain = tryAgain)
        ModelsAvailability.Loading -> PhotosModelsScreen.Body.Notice(PhotosModelsCopy.LOADING)
        // ⚠ No row and nothing that writes: only the retry.
        ModelsAvailability.InstalledUnreadable ->
            PhotosModelsScreen.Body.Notice(PhotosModelsCopy.installedUnreadable(device), tryAgain = tryAgain)
        is ModelsAvailability.Listed -> listed(state, list, device, onWifi)
    }
    // ⚠ No switch where nothing is offered. One row per switch the repository offers, search first.
    val features: List<PhotosModelsScreen.Feature> = when (state.availability) {
        is ModelsAvailability.NotOffered -> emptyList()
        ModelsAvailability.NotConnected, ModelsAvailability.Loading, ModelsAvailability.InstalledUnreadable,
        is ModelsAvailability.Listed -> state.consents.map { consent -> featureRow(state, consent, device) }
    }
    return PhotosModelsScreen(
        indexing = photosIndexing(state, device),
        checkingAgain = state.checkingAgain,
        checking = PhotosModelsCopy.CHECKING_AGAIN.takeIf { state.checkingAgain },
        // ⚠ While the list is being read again nothing offers to read it again.
        body = if (state.checkingAgain) body.withoutTryAgain() else body,
        features = features,
        message = state.message?.let { PhotosModelsCopy.message(it, device, state.indexContinued) },
        messageShown = state.message,
        question = state.pendingDeletion?.let { row ->
            val frees: Long = (row.setup as? ModelSetup.Held)?.model?.onDeviceBytes ?: 0L
            val text: String = when (state.capabilityOf(row.modelId)) {
                InferenceCapability.FACE_CLUSTERING -> PhotosModelsCopy.facesRemoveText(frees, device)
                else -> PhotosModelsCopy.removeText(frees, device)
            }
            PhotosModelsScreen.Asked(
                PhotosModelsScreen.Asked.Kind.REMOVAL,
                PhotosModelsScreen.Question(
                    PhotosModelsCopy.removeTitle(row.name), text,
                    yes = PhotosModelsCopy.REMOVE, no = PhotosModelsCopy.CANCEL,
                ),
            )
        } ?: state.featureOffer?.let { offered ->
            val faces: Boolean = offered == InferenceCapability.FACE_CLUSTERING
            PhotosModelsScreen.Asked(
                PhotosModelsScreen.Asked.Kind.FEATURE_OFFER,
                PhotosModelsScreen.Question(
                    if (faces) PhotosModelsCopy.facesOfferTitle(device) else PhotosModelsCopy.offerTitle(device),
                    if (faces) PhotosModelsCopy.facesOfferText(device) else PhotosModelsCopy.OFFER_TEXT,
                    yes = PhotosModelsCopy.TURN_ON, no = PhotosModelsCopy.NOT_NOW,
                ),
            )
        },
    )
}

/**
 * One feature row of the Models screen. ⚠ Not *off* before the answers were read: the seed is no
 * answer. ⚠ Not *it works once ready* over a model this phone could not run: its own sentence says
 * it never will. ⚠⚠ Face grouping's row says what switching it off deletes, while it is on.
 */
private fun featureRow(state: ModelsUiState, consent: CapabilityConsent, device: String): PhotosModelsScreen.Feature {
    val capability: InferenceCapability = consent.capability
    val on: Boolean = consent.realm && consent.device
    val waits: Boolean = state.consentsRead && on && state.usableFor(capability) != true && !state.unsuitableFor(capability)
    val deletes: Boolean = capability == InferenceCapability.FACE_CLUSTERING && state.consentsRead && on
    return PhotosModelsScreen.Feature(
        capability = capability,
        title = PhotosModelsCopy.featureTitle(capability),
        status = when {
            !state.consentsRead -> PhotosModelsCopy.CONSENT_NOT_READ
            on -> PhotosModelsCopy.featureOn(device)
            else -> PhotosModelsCopy.featureOff(device)
        },
        detail = listOfNotNull(
            PhotosModelsCopy.STARTS_WHEN_READY.takeIf { waits },
            PhotosModelsCopy.facesOffDeletes(device).takeIf { deletes },
        ).joinToString(" ").ifEmpty { null },
    )
}

private val indexNow = PhotosModelsScreen.IndexAction(PhotosModelsScreen.IndexAction.Kind.INDEX_NOW, PhotosModelsCopy.INDEX_NOW)
private val pause = PhotosModelsScreen.IndexAction(PhotosModelsScreen.IndexAction.Kind.PAUSE, PhotosModelsCopy.PAUSE)
private val resume = PhotosModelsScreen.IndexAction(PhotosModelsScreen.IndexAction.Kind.RESUME, PhotosModelsCopy.RESUME)

/**
 * The indexing section (§14.19.6), or `null` where it is not drawn: a repository with no pass,
 * or no list a model could be on. The states, first that holds:
 * 1. no model, or none ready — no button;
 * 2. the switch not read, or off — no button, the switch named;
 * 3. paused — *Resume*;
 * 4. running — *Indexing N of M*, the bar;
 * 5. waiting for the phone — why, and that it goes on by itself;
 * 6. stopped by leaving Photos or by low memory — *Index now*;
 * 7. not asked — *Index now*.
 * ⚠⚠ *Pause* only while a pass runs (or an ask is starting) or waits to go on by itself: idle,
 * stopped and not-ready sections have none (next to *Index now* it reads as a second way to
 * start). *Resume* whenever the user's pause is on.
 * ⚠ The coverage line wherever no pass runs; idle, it is the first line itself.
 */
internal fun photosIndexing(state: ModelsUiState, device: String): PhotosModelsScreen.Indexing? {
    if (!state.capabilities.indexing) return null
    val list: ModelsAvailability.Listed = state.availability as? ModelsAvailability.Listed ?: return null
    if (list.models.isEmpty()) return null
    fun section(status: String, detail: String? = null, actions: List<PhotosModelsScreen.IndexAction> = emptyList(), withCoverage: Boolean = true, progress: PhotosModelsScreen.Progress? = null) =
        PhotosModelsScreen.Indexing(
            title = PhotosModelsCopy.INDEXING,
            status = status,
            detail = detail,
            coverage = if (withCoverage) coverageLine(state, device) else null,
            progress = progress,
            actions = actions,
        )
    if (state.anyModelUsable != true) return section(PhotosModelsCopy.INDEX_WHEN_READY)
    // ⚠ Not read is not off.
    val consent: CapabilityConsent? = state.consents.firstOrNull()
    if (!state.consentsRead) return section(PhotosModelsCopy.CONSENT_NOT_READ)
    val on: List<CapabilityConsent> = state.consents.filter { it.realm && it.device }
    if (consent == null || on.isEmpty()) {
        // ⚠ Every switch is named; which of them is off where is said only when there is one.
        val which: String? = when {
            consent == null || state.consents.size > 1 -> null
            !consent.realm -> PhotosModelsCopy.INDEX_SWITCH_OFF_EVERYWHERE
            else -> PhotosModelsCopy.indexSwitchOffHere(device)
        }
        return section(PhotosModelsCopy.indexNeedsTheSwitch(state.consents.map { PhotosModelsCopy.featureTitle(it.capability) }), which)
    }
    // ⚠⚠ What the operation asks (`StartMediaIndexPassImplementation`): a feature that is switched on
    // AND has a backend, i.e. its OWN model usable. Search's model ready and face grouping on (or the
    // reverse) is a press refused every time, so none is offered and the switched-on feature's model
    // is named. ⚠ With one feature this cannot fire: its model is `anyModelUsable`, asked above.
    if (on.none { state.usableFor(it.capability) == true }) {
        val waiting: CapabilityConsent = on.first()
        val title: String = PhotosModelsCopy.featureTitle(waiting.capability)
        return section(
            if (state.unsuitableFor(waiting.capability)) PhotosModelsCopy.indexCannotRun(title, device) else PhotosModelsCopy.indexWhenModelReady(title),
        )
    }
    if (state.indexingPaused) return section(PhotosModelsCopy.indexPaused(device), actions = listOf(resume))
    val progress: IndexProgressState = state.indexProgress
    if (progress.indexing) {
        val status: String = if (progress.total > 0) PhotosModelsCopy.indexingCount(progress.done.coerceAtMost(progress.total), progress.total) else PhotosModelsCopy.INDEX_LOOKING
        val bar: PhotosModelsScreen.Progress =
            if (progress.total > 0) PhotosModelsScreen.Progress.Bar(progress.done.coerceIn(0, progress.total).toDouble() / progress.total) else PhotosModelsScreen.Progress.Busy
        return section(status, PhotosModelsCopy.runningDetail(state.indexContinued), listOf(pause), withCoverage = false, progress = bar)
    }
    return when (val asked: ForegroundIndexStanding? = state.foregroundIndex) {
        is ForegroundIndexStanding.Waiting -> when (asked.why) {
            ForegroundIndexStanding.Waiting.Why.WARM -> section(PhotosModelsCopy.indexWarm(device), PhotosModelsCopy.INDEX_GOES_ON_COOLED, listOf(pause))
            ForegroundIndexStanding.Waiting.Why.LOW_POWER_MODE -> section(PhotosModelsCopy.INDEX_LOW_POWER, PhotosModelsCopy.INDEX_GOES_ON_LOW_POWER, listOf(pause))
            ForegroundIndexStanding.Waiting.Why.BATTERY_LOW -> section(PhotosModelsCopy.INDEX_BATTERY_LOW, PhotosModelsCopy.INDEX_GOES_ON_BATTERY, listOf(pause))
        }
        is ForegroundIndexStanding.Stopped -> section(
            when (asked.why) {
                ForegroundIndexStanding.Stopped.Why.LEFT_THE_APP -> PhotosModelsCopy.INDEX_STOPPED_LEFT
                ForegroundIndexStanding.Stopped.Why.LOW_MEMORY -> PhotosModelsCopy.indexStoppedMemory(device)
            },
            actions = startable(state),
        )
        // ⚠ Asked with no pass on the bar yet: starting, and nothing to press twice.
        ForegroundIndexStanding.Asked -> section(PhotosModelsCopy.STARTING, actions = listOf(pause))
        ForegroundIndexStanding.NotAsked, null -> when {
            // ⚠ A press being answered: nothing runs yet, so nothing to pause.
            state.startingIndex -> section(PhotosModelsCopy.STARTING)
            // ⚠ Idle: the coverage line is the first line, never a *"not indexing"* above it.
            else -> section(coverageLine(state, device) ?: PhotosModelsCopy.INDEX_PROMPT, actions = startable(state), withCoverage = false)
        }
    }
}

/** *Index now*, unless a press is being answered. ⚠ No *Pause*: nothing runs. */
private fun startable(state: ModelsUiState): List<PhotosModelsScreen.IndexAction> =
    listOfNotNull(indexNow.takeUnless { state.startingIndex })

/**
 * What the index holds, as one sentence, or `null` when nothing is known. ⚠ The update's line
 * first: after an iOS update a bare *0 of N* would read as a fault.
 */
private fun coverageLine(state: ModelsUiState, device: String): String? {
    val stored: StoredIndex? = state.storedIndex
    val reach: IndexCoverage? = state.coverage
    if (stored?.clearedByOsUpdate == true && reach == null) return PhotosModelsCopy.clearedByOsUpdate(stored.searchable)
    val searchable: Int? = stored?.searchable
    val line: String = when {
        searchable != null -> PhotosModelsCopy.canBeSearched(searchable, reach?.reachable, device)
        reach != null -> PhotosModelsCopy.canBeIndexed(reach.reachable, device)
        else -> return null
    }
    val away: Int = reach?.notOnDevice ?: 0
    return if (away > 0) "$line ${PhotosModelsCopy.indexNotOnDevice(away, device)}" else line
}

/** A press in the indexing section: the one place a kind becomes a call. */
fun photosIndexingPress(viewModel: ModelsViewModel, action: PhotosModelsScreen.IndexAction) = when (action.kind) {
    PhotosModelsScreen.IndexAction.Kind.INDEX_NOW -> viewModel.indexNow()
    PhotosModelsScreen.IndexAction.Kind.PAUSE -> viewModel.setIndexingPaused(true)
    PhotosModelsScreen.IndexAction.Kind.RESUME -> viewModel.setIndexingPaused(false)
}

private fun PhotosModelsScreen.Body.withoutTryAgain(): PhotosModelsScreen.Body = when (this) {
    PhotosModelsScreen.Body.Hidden -> this
    is PhotosModelsScreen.Body.Notice -> copy(tryAgain = null)
    is PhotosModelsScreen.Body.Rows -> copy(
        tryAgain = null,
        rows = rows.map { row -> row.copy(actions = row.actions.filter { it.kind != PhotosModelsScreen.Action.Kind.TRY_AGAIN }) },
    )
}

/**
 * The actions of [row] this state can take, the rest withheld: nothing is drawn that the view
 * model would refuse. ⚠ The same conditions `ModelsViewModel.download` and `.confirmDelete` ask.
 */
private fun PhotosModelsScreen.Row.takeable(state: ModelsUiState): PhotosModelsScreen.Row {
    val starts = setOf(PhotosModelsScreen.Action.Kind.DOWNLOAD, PhotosModelsScreen.Action.Kind.DOWNLOAD_WITH_MOBILE_DATA)
    // A store that cannot be changed takes neither a download nor a removal.
    val readOnly: Boolean = state.storeReadOnly != null
    // Where one download runs at a time, none is offered beside one that runs.
    val waits: Boolean = state.capabilities.oneDownloadAtATime && state.models.any { it.setup.download != null }
    return copy(
        actions = actions.filterNot { action ->
            (action.kind in starts && (readOnly || waits)) || (action.kind == PhotosModelsScreen.Action.Kind.REMOVE && readOnly)
        },
    )
}

private fun listed(state: ModelsUiState, list: ModelsAvailability.Listed, device: String, onWifi: Boolean?): PhotosModelsScreen.Body {
    val tryAgain: String = PhotosModelsCopy.TRY_AGAIN
    if (list.models.isEmpty() && list.downloads == ModelsAvailability.Downloads.KNOWN) {
        return when (list.catalog) {
            ModelsAvailability.Catalog.NOT_READ_YET -> PhotosModelsScreen.Body.Notice(PhotosModelsCopy.LOADING)
            ModelsAvailability.Catalog.READ, ModelsAvailability.Catalog.STALE ->
                PhotosModelsScreen.Body.Notice(PhotosModelsCopy.nothingPublished(device))
            ModelsAvailability.Catalog.UNAVAILABLE -> PhotosModelsScreen.Body.Notice(PhotosModelsCopy.CATALOG_UNAVAILABLE, tryAgain = tryAgain)
        }
    }
    // ⚠ The journal first: with it unreadable no row is fetching or absent, and that is why.
    val notice: String? = when {
        list.downloads == ModelsAvailability.Downloads.UNREADABLE -> PhotosModelsCopy.JOURNAL_UNREADABLE
        else -> when (list.catalog) {
            ModelsAvailability.Catalog.NOT_READ_YET, ModelsAvailability.Catalog.READ -> null
            ModelsAvailability.Catalog.STALE -> PhotosModelsCopy.CATALOG_STALE
            ModelsAvailability.Catalog.UNAVAILABLE -> PhotosModelsCopy.CATALOG_UNAVAILABLE
        }
    }
    return PhotosModelsScreen.Body.Rows(
        rows = list.models.map { row -> row(state, row, device, onWifi).takeable(state) },
        notice = notice,
        tryAgain = tryAgain.takeIf { notice != null },
    )
}

private val downloadAgain = PhotosModelsScreen.Action(PhotosModelsScreen.Action.Kind.DOWNLOAD, PhotosModelsCopy.DOWNLOAD_AGAIN)
private val cancel = PhotosModelsScreen.Action(PhotosModelsScreen.Action.Kind.CANCEL, PhotosModelsCopy.CANCEL)
private val remove = PhotosModelsScreen.Action(PhotosModelsScreen.Action.Kind.REMOVE, PhotosModelsCopy.REMOVE)
private val again = PhotosModelsScreen.Action(PhotosModelsScreen.Action.Kind.TRY_AGAIN, PhotosModelsCopy.TRY_AGAIN)
private val loadAgain = PhotosModelsScreen.Action(PhotosModelsScreen.Action.Kind.LOAD_AGAIN, PhotosModelsCopy.TRY_AGAIN)

/** The two ways to start a download, the second only where the choice exists. */
private fun starts(state: ModelsUiState, first: String, second: String): List<PhotosModelsScreen.Action> = listOfNotNull(
    PhotosModelsScreen.Action(PhotosModelsScreen.Action.Kind.DOWNLOAD, first),
    PhotosModelsScreen.Action(PhotosModelsScreen.Action.Kind.DOWNLOAD_WITH_MOBILE_DATA, second).takeIf { state.capabilities.cellularChoice },
)

private fun facts(model: OfferedModel, device: String): String = listOfNotNull(
    PhotosModelsCopy.downloadSize(model.downloadBytes),
    model.onDeviceBytes?.let { PhotosModelsCopy.onDevice(it, device) },
    model.licence.takeIf { it.isNotBlank() },
).joinToString(" · ")

/** What a row says of a download that did not start, or ended: under an absent model or an offered update. */
private fun lastSentence(last: DownloadSaid?, device: String): String? = when (last) {
    null, DownloadSaid.Cancelled -> null
    is DownloadSaid.NeedsSpace -> PhotosModelsCopy.needsSpace(last.neededBytes, last.freeBytes, device)
    DownloadSaid.FreeSpaceUnknown -> PhotosModelsCopy.FREE_SPACE_UNKNOWN
    DownloadSaid.NoTransport -> PhotosModelsCopy.NO_TRANSPORT
    DownloadSaid.TransfersUnknown -> PhotosModelsCopy.TRANSFERS_UNKNOWN
    DownloadSaid.JournalUnreadable -> PhotosModelsCopy.JOURNAL_UNREADABLE
    DownloadSaid.StoreRefused -> PhotosModelsCopy.STORE_REFUSED
    is DownloadSaid.Failed -> if (last.integrity) PhotosModelsCopy.INTEGRITY_FAILED else PhotosModelsCopy.DOWNLOAD_FAILED
}

/** A journalled download: its line, what follows from it, its bar, and whether asking again is a way out. */
private class Fetch(val status: String, val detail: String?, val progress: PhotosModelsScreen.Progress, val retry: Boolean)

private fun fetch(download: ModelSetup.Download, device: String, onWifi: Boolean?): Fetch {
    val written: Long? = download.writtenBytes
    val bar: PhotosModelsScreen.Progress =
        if (written != null && download.totalBytes > 0L) {
            PhotosModelsScreen.Progress.Bar((written.toDouble() / download.totalBytes.toDouble()).coerceIn(0.0, 1.0))
        } else {
            PhotosModelsScreen.Progress.Busy
        }
    val bytes: String = if (written != null && download.totalBytes > 0L) PhotosModelsCopy.downloaded(written, download.totalBytes) else PhotosModelsCopy.DOWNLOADING
    fun paused(sentence: String) = Fetch(sentence, bytes.takeIf { written != null }, bar, retry = true)
    return when (val held: DownloadSaid? = download.held) {
        // ⚠ Nothing is claimed of a bar that does not move: slow and stuck are not told apart.
        null, DownloadSaid.Cancelled ->
            if (!download.allowsCellular && onWifi == false) {
                Fetch(PhotosModelsCopy.WAITING_FOR_WIFI, PhotosModelsCopy.CELLULAR_NEEDS_A_NEW_START, bar, retry = false)
            } else {
                Fetch(bytes, PhotosModelsCopy.CAN_LEAVE, bar, retry = false)
            }
        is DownloadSaid.NeedsSpace -> paused(PhotosModelsCopy.pausedForSpace(held.neededBytes, held.freeBytes, device))
        DownloadSaid.FreeSpaceUnknown -> paused(PhotosModelsCopy.PAUSED_FREE_SPACE_UNKNOWN)
        DownloadSaid.NoTransport -> paused(PhotosModelsCopy.PAUSED_NO_TRANSPORT)
        DownloadSaid.TransfersUnknown -> paused(PhotosModelsCopy.PAUSED_TRANSFERS_UNKNOWN)
        DownloadSaid.StoreRefused -> paused(PhotosModelsCopy.PAUSED_STORE_REFUSED)
        DownloadSaid.JournalUnreadable -> paused(PhotosModelsCopy.JOURNAL_UNREADABLE)
        is DownloadSaid.Failed ->
            // ⚠ No retry for bytes that were not the catalog's: the same file would come again (I5).
            if (held.integrity) Fetch(PhotosModelsCopy.HELD_INTEGRITY_FAILED, bytes.takeIf { written != null }, bar, retry = false)
            else paused(PhotosModelsCopy.DOWNLOAD_FAILED)
    }
}

private fun row(state: ModelsUiState, row: ModelRow, device: String, onWifi: Boolean?): PhotosModelsScreen.Row {
    fun of(facts: String, status: String?, detail: String?, progress: PhotosModelsScreen.Progress?, actions: List<PhotosModelsScreen.Action>) =
        PhotosModelsScreen.Row(row.modelId, row.name, facts, status, detail, progress, actions)

    val setup: ModelSetup = row.setup
    val facts: String = when (setup) {
        is ModelSetup.Absent -> facts(setup.model, device)
        is ModelSetup.Fetching -> setup.model?.let { facts(it, device) } ?: PhotosModelsCopy.downloadSize(setup.download.totalBytes)
        is ModelSetup.Held -> listOfNotNull(PhotosModelsCopy.onDevice(setup.model.onDeviceBytes, device), setup.model.licence.takeIf { it.isNotBlank() })
            .joinToString(" · ")
    }
    // ⚠ The two marks first: a row something is being done to offers nothing.
    if (row.modelId in state.removing) return of(facts, PhotosModelsCopy.REMOVING, null, PhotosModelsScreen.Progress.Busy, emptyList())
    if (row.modelId in state.starting) return of(facts, PhotosModelsCopy.STARTING, null, PhotosModelsScreen.Progress.Busy, emptyList())

    return when (setup) {
        is ModelSetup.Absent -> when (setup.answered) {
            // ⚠⚠ No download the key would answer at once (§14.5 item 0): 743 MB for nothing.
            ModelSetup.Answered.UNSUITABLE -> of(facts, unsuitableSentence(state, row.modelId, device), null, null, emptyList())
            ModelSetup.Answered.STOPPED_LOADING -> of(facts, PhotosModelsCopy.stoppedLoading(device), null, null, listOf(loadAgain))
            null -> when (val pressed: ModelSetup.Pressed? = setup.pressed) {
                // ⚠ The call's own, while it lives: its caller stopped waiting, the call did not.
                ModelSetup.Pressed.Starting -> of(facts, PhotosModelsCopy.STARTING, null, PhotosModelsScreen.Progress.Busy, emptyList())
                is ModelSetup.Pressed.Refused, null -> {
                    val last: DownloadSaid? = setup.last
                    // ⚠ Worded as a retry only where asking again can fetch something else (I5).
                    val first: String = if (last is DownloadSaid.Failed && !last.integrity) PhotosModelsCopy.TRY_AGAIN else PhotosModelsCopy.DOWNLOAD
                    val status: String? = lastSentence(last, device)
                        ?: (pressed as? ModelSetup.Pressed.Refused)?.let { refused -> refusalMessage(refused.why)?.let { PhotosModelsCopy.message(it, device) } }
                    of(facts, status, null, null, starts(state, first, PhotosModelsCopy.USE_MOBILE_DATA))
                }
            }
        }
        is ModelSetup.Fetching -> {
            val fetch: Fetch = fetch(setup.download, device, onWifi)
            of(facts, fetch.status, fetch.detail, fetch.progress, listOfNotNull(again.takeIf { fetch.retry }, cancel.takeIf { state.capabilities.cancel }))
        }
        is ModelSetup.Held -> held(state, row.modelId, setup, facts, device, onWifi, ::of)
    }
}

/** What a row says of a model this phone could not run, for the feature it serves. */
private fun unsuitableSentence(state: ModelsUiState, modelId: String, device: String): String = when (state.capabilityOf(modelId)) {
    InferenceCapability.FACE_CLUSTERING -> PhotosModelsCopy.facesUnsuitable(device)
    else -> PhotosModelsCopy.unsuitable(device)
}

private fun held(
    state: ModelsUiState,
    modelId: String,
    setup: ModelSetup.Held,
    facts: String,
    device: String,
    onWifi: Boolean?,
    of: (String, String?, String?, PhotosModelsScreen.Progress?, List<PhotosModelsScreen.Action>) -> PhotosModelsScreen.Row,
): PhotosModelsScreen.Row {
    /** The standing's sentence, what follows from it, and what it offers beside the removal. */
    class Stands(val status: String, val detail: String? = null, val busy: Boolean = false, val actions: List<PhotosModelsScreen.Action> = emptyList())

    val stands: Stands = when (val standing: Standing = setup.standing) {
        Standing.NotReadYet -> Stands(PhotosModelsCopy.STANDING_NOT_READ, actions = listOf(again))
        Standing.Unreadable -> Stands(PhotosModelsCopy.STANDING_UNREADABLE, actions = listOf(again))
        is Standing.Preparing -> when (val how: Standing.Preparing.How = standing.how) {
            Standing.Preparing.How.Now -> Stands(PhotosModelsCopy.PREPARING_NOW, busy = true)
            Standing.Preparing.How.Pending -> Stands(PhotosModelsCopy.PREPARING, busy = true)
            Standing.Preparing.How.NotInForeground -> Stands(PhotosModelsCopy.PREPARING_NEEDS_FOREGROUND, actions = listOf(again))
            is Standing.Preparing.How.Failed -> Stands(
                PhotosModelsCopy.PREPARE_FAILED,
                how.space?.let { PhotosModelsCopy.needsSpace(it.neededBytes, it.freeBytes, device) },
                actions = listOf(again),
            )
            // ⚠ A download of the model fetches only what is missing.
            Standing.Preparing.How.Incomplete -> Stands(PhotosModelsCopy.INCOMPLETE, actions = listOf(downloadAgain))
            Standing.Preparing.How.Unreadable -> Stands(PhotosModelsCopy.FILES_UNREADABLE, actions = listOf(again))
        }
        is Standing.Checking -> when (val how: Standing.Checking.How = standing.how) {
            Standing.Checking.How.Running, Standing.Checking.How.Pending -> Stands(PhotosModelsCopy.checking(device), busy = true)
            // ⚠ No *check now*: nothing can do it (I5).
            is Standing.Checking.How.Paused -> Stands(
                PhotosModelsCopy.CHECK_PAUSED,
                when (how.why) {
                    Standing.Checking.Why.HOT -> PhotosModelsCopy.warm(device)
                    Standing.Checking.Why.MEMORY_PRESSURE -> PhotosModelsCopy.lowOnMemory(device)
                    Standing.Checking.Why.SLOW -> PhotosModelsCopy.CHECK_SLOW
                    Standing.Checking.Why.ALREADY_TRIED_THIS_TIME, Standing.Checking.Why.BACKING_OFF -> null
                },
            )
            // ⚠ Not *download again*: a download of an install that is whole fetches nothing (M12).
            Standing.Checking.How.InstallFailed -> Stands(PhotosModelsCopy.installFailed(device))
            Standing.Checking.How.NoProbe -> Stands(PhotosModelsCopy.CHECK_NO_PROBE)
            Standing.Checking.How.Unreadable -> Stands(PhotosModelsCopy.CHECK_UNREADABLE, actions = listOf(again))
        }
        Standing.Ready -> Stands(PhotosModelsCopy.READY)
        // ⚠ No download of the same bytes; an update is other bytes and is offered below (M9).
        Standing.Unsuitable -> Stands(unsuitableSentence(state, modelId, device))
        // ⚠ The one way out of a fail (§14.5 item 0): once more, by the user's press.
        Standing.StoppedLoading -> Stands(PhotosModelsCopy.stoppedLoading(device), actions = listOf(loadAgain))
    }
    val removal: List<PhotosModelsScreen.Action> = listOfNotNull(remove.takeIf { setup.offersRemoval(state.capabilities.cancel) })
    val working: PhotosModelsScreen.Progress? = PhotosModelsScreen.Progress.Busy.takeIf { stands.busy }
    return when (val update: ModelSetup.Update = setup.update) {
        ModelSetup.Update.None, ModelSetup.Update.Unknown -> of(facts, stands.status, stands.detail, working, stands.actions + removal)
        is ModelSetup.Update.Available -> {
            val pressed: ModelSetup.Pressed? = update.pressed
            // ⚠ The call's own, while it lives (as for an absent model).
            val starting: Boolean = pressed == ModelSetup.Pressed.Starting
            val refused: String? = (pressed as? ModelSetup.Pressed.Refused)?.let { refusalMessage(it.why)?.let { message -> PhotosModelsCopy.message(message, device) } }
            of(
                facts,
                stands.status,
                listOfNotNull(
                    stands.detail,
                    PhotosModelsCopy.updateAvailable(update.model.downloadBytes),
                    if (starting) PhotosModelsCopy.STARTING else lastSentence(update.last, device) ?: refused,
                ).joinToString(" "),
                if (starting) PhotosModelsScreen.Progress.Busy else working,
                // ⚠ One download per row: *Download again* and *Update* are the same call, and the update
                // is what it would fetch. ⚠⚠ Not offered again over bytes that failed their check: the
                // same file would come again (I5). A catalog with other bytes is another update.
                stands.actions.filter { it.kind != PhotosModelsScreen.Action.Kind.DOWNLOAD } +
                    (if (starting || (update.last as? DownloadSaid.Failed)?.integrity == true) emptyList() else starts(state, PhotosModelsCopy.UPDATE, PhotosModelsCopy.UPDATE_WITH_MOBILE_DATA)) +
                    removal,
            )
        }
        is ModelSetup.Update.Fetching -> {
            val fetch: Fetch = fetch(update.download, device, onWifi)
            // ⚠ The model's own standing stays said: it is installed while its update is fetched.
            of(
                facts, stands.status, listOfNotNull(fetch.status, fetch.detail).joinToString(" "), fetch.progress,
                listOfNotNull(again.takeIf { fetch.retry }, cancel.takeIf { state.capabilities.cancel }) + removal,
            )
        }
    }
}

/**
 * A press on [action] of the row of [modelId]: the one place a kind becomes a call, so the shell
 * forwards the press and chooses nothing.
 */
fun photosModelsPress(viewModel: ModelsViewModel, modelId: String, action: PhotosModelsScreen.Action) = when (action.kind) {
    PhotosModelsScreen.Action.Kind.DOWNLOAD -> viewModel.download(modelId, allowsCellular = false)
    PhotosModelsScreen.Action.Kind.DOWNLOAD_WITH_MOBILE_DATA -> viewModel.download(modelId, allowsCellular = true)
    PhotosModelsScreen.Action.Kind.CANCEL -> viewModel.cancel(modelId)
    PhotosModelsScreen.Action.Kind.REMOVE -> viewModel.confirmDelete(modelId)
    PhotosModelsScreen.Action.Kind.TRY_AGAIN -> viewModel.retry()
    PhotosModelsScreen.Action.Kind.LOAD_AGAIN -> viewModel.loadAgain(modelId)
}

/**
 * Whether the platform's path counts as Wi-Fi for a download that may not use mobile data
 * (§14.18.3, row 8a). ⚠ Wired counts: such a download runs over it. ⚠ Low Data Mode is not asked
 * (M7), nor whether the path is expensive: a hotspot joined over Wi-Fi is Wi-Fi to the transfer.
 *
 * @param satisfied the path can carry traffic at all.
 */
fun photosOnWifi(satisfied: Boolean, usesWifi: Boolean, usesWired: Boolean): Boolean = satisfied && (usesWifi || usesWired)

/**
 * One feature's own screen **as values** (`inference-service.md` §38's shape; M4): what it does,
 * and which of the user's devices do the work. As [PhotosModelsScreen], the shell decides nothing.
 */
data class PhotosFeatureScreen(
    val capability: InferenceCapability,
    val title: String,
    /** What the feature does. ⚠ Not `description`: that name collides with Swift's own on export. */
    val about: String,
    val devicesHeader: String,
    val thisDevice: Device,
    /** The realm's other devices. ⚠ Empty also while they are not known: [othersNotice] says which. */
    val others: List<Device>,
    /** Why no other device is listed though there may be some, or `null`. */
    val othersNotice: String?,
    /** *Turn off everywhere*, or `null` where there is nothing it would turn off, or nothing known to reach. */
    val everywhere: Everywhere?,
    /** [ModelsUiState.message], where it is this screen's to say. */
    val message: String?,
    /** The message [message] says, for [photosModelsMessageSeen]. */
    val messageShown: ModelsMessage? = null,
) {
    /**
     * @param key stable among the rows of one screen.
     * @param on what the switch shows.
     * @param press what a press on the switch does, or `null` where it can do nothing: the switch
     *   is then drawn disabled, and [status] says why.
     * @param question asked **before** [press] is carried out, or `null` where it is carried out at
     *   once. ⚠⚠ Face grouping's *off* asks, naming what is deleted (`ios-face-grouping.md` F6): it
     *   cannot be taken back. Search's does not: it deletes nothing.
     */
    data class Device(val key: String, val name: String, val status: String, val on: Boolean, val press: Press?, val question: PhotosModelsScreen.Question? = null)

    /** What a control of this screen does. Carried out by [photosFeaturePress], never by the shell. */
    sealed interface Press {
        data object TurnOnHere : Press
        data object TurnOffHere : Press
        data class TurnOffOther(val deviceId: String) : Press
        data object TurnOffEverywhere : Press
    }

    /** @param question asked before [press] is carried out. */
    data class Everywhere(val label: String, val press: Press, val question: PhotosModelsScreen.Question)
}

/**
 * [state] as the feature screen of [capability] draws it, or `null` where this screen offers no
 * switch for it (the shell then has nothing to show, and leaves).
 *
 * ⚠ Search's no deletes nothing, so only *Turn off everywhere* asks first. ⚠⚠ Face grouping's no
 * deletes that device's face data (the operation deletes on an explicit no, `inferenceConsentWithdrawn`),
 * so **every** off asks first and names what goes (`ios-face-grouping.md` F6), as Android's
 * `turnOffDeletes` does. An absent row is shown off, and deletes nothing: there is nothing to ask.
 */
fun photosFeatureScreen(state: ModelsUiState, capability: InferenceCapability, device: String): PhotosFeatureScreen? {
    val consent: CapabilityConsent = state.consents.firstOrNull { it.capability == capability } ?: return null
    // ⚠⚠ Until the answers were read the screen says so, and no switch can be pressed: the seed
    // is no answer, and a press on it would write the opposite of a default.
    val read: Boolean = state.consentsRead
    // ⚠ Only this screen's two: its view model raises no other.
    val shownHere: ModelsMessage? = when (val message = state.message) {
        ModelsMessage.ConsentNotSaved, ModelsMessage.OtherDeviceConsentNotSaved -> message
        else -> null
    }
    val idKnown: Boolean = state.thisDeviceKnown != false
    val deletes: Boolean = capability == InferenceCapability.FACE_CLUSTERING
    fun turnOff(title: String, text: String) = PhotosModelsScreen.Question(title, text, yes = PhotosModelsCopy.TURN_OFF, no = PhotosModelsCopy.KEEP_ON)
    val onHere: String =
        if (state.usableFor(capability) != true && !state.unsuitableFor(capability)) PhotosModelsCopy.STARTS_WHEN_READY else PhotosModelsCopy.DEVICE_ON
    val here = PhotosFeatureScreen.Device(
        key = "this",
        name = PhotosModelsCopy.thisDevice(device),
        status = when {
            !read -> PhotosModelsCopy.CONSENT_NOT_READ
            // ⚠ Never written under a placeholder id (§9.2): the switch is taken away, and says so.
            !idKnown -> PhotosModelsCopy.thisDeviceNotSetUp(device)
            consent.device && !consent.realm -> PhotosModelsCopy.ON_BUT_OFF_EVERYWHERE
            // ⚠⚠ What the press would delete, said before it is pressed.
            // The switch shows it is on; the line says what turning it off deletes.
            consent.device && deletes ->
                if (onHere == PhotosModelsCopy.DEVICE_ON) PhotosModelsCopy.facesOffDeletes(device) else "$onHere ${PhotosModelsCopy.facesOffDeletes(device)}"
            consent.device -> onHere
            else -> PhotosModelsCopy.DEVICE_OFF
        },
        // ⚠ `device`, not `realm && device`: a yes stored under a realm no is shown on, so that
        // it can be seen and switched off.
        on = consent.device,
        press = when {
            !read || !idKnown -> null
            consent.device -> PhotosFeatureScreen.Press.TurnOffHere
            else -> PhotosFeatureScreen.Press.TurnOnHere
        },
        question = turnOff(PhotosModelsCopy.facesTurnOffTitle(device), PhotosModelsCopy.facesTurnOffText(device))
            .takeIf { deletes && read && idKnown && consent.device },
    )
    // ⚠ Listed only from a roster that is known NOW: a row left by a read that has since failed
    // would be a switch that writes after a failed read.
    val listed: Boolean = read && state.capabilities.otherDevices && state.otherDevicesKnown
    val others: List<OtherDeviceConsent> = if (listed) state.otherDevices[capability].orEmpty() else emptyList()
    val everywhere: Boolean = listed && (consent.realm || consent.device || others.any { it.allowed })
    return PhotosFeatureScreen(
        capability = capability,
        title = PhotosModelsCopy.featureTitle(capability),
        about = if (deletes) PhotosModelsCopy.FACES_DESCRIPTION else PhotosModelsCopy.FEATURE_DESCRIPTION,
        devicesHeader = PhotosModelsCopy.DEVICES,
        thisDevice = here,
        others = others.map { other ->
            PhotosFeatureScreen.Device(
                key = other.deviceId,
                name = other.name.ifBlank { PhotosModelsCopy.UNNAMED_DEVICE },
                status = if (other.allowed) PhotosModelsCopy.DEVICE_ON else PhotosModelsCopy.OTHER_DEVICE_OFF,
                on = other.allowed,
                // ⚠ Off only from here (§38.2): a device's yes is given on that device.
                press = PhotosFeatureScreen.Press.TurnOffOther(other.deviceId).takeIf { other.allowed },
                question = turnOff(
                    PhotosModelsCopy.facesTurnOffOtherTitle(other.name.ifBlank { PhotosModelsCopy.UNNAMED_DEVICE }),
                    PhotosModelsCopy.FACES_TURN_OFF_OTHER_TEXT,
                ).takeIf { deletes && other.allowed },
            )
        },
        othersNotice = when {
            // ⚠ With no id of its own this device cannot tell itself from the others: its row says so.
            !state.capabilities.otherDevices || listed || !idKnown -> null
            // ⚠ Not read yet and could not be read are two sentences (I3): the first passes by itself.
            !state.otherDevicesRead -> PhotosModelsCopy.OTHER_DEVICES_NOT_READ
            else -> PhotosModelsCopy.OTHER_DEVICES_UNREADABLE
        },
        everywhere = PhotosFeatureScreen.Everywhere(
            label = if (deletes) PhotosModelsCopy.FACES_TURN_OFF_EVERYWHERE else PhotosModelsCopy.TURN_OFF_EVERYWHERE,
            press = PhotosFeatureScreen.Press.TurnOffEverywhere,
            question = if (deletes) {
                turnOff(PhotosModelsCopy.facesTurnOffEverywhereTitle, PhotosModelsCopy.FACES_TURN_OFF_EVERYWHERE_TEXT)
            } else {
                turnOff(PhotosModelsCopy.turnOffEverywhereTitle, PhotosModelsCopy.TURN_OFF_EVERYWHERE_TEXT)
            },
        ).takeIf { everywhere },
        message = shownHere?.let { PhotosModelsCopy.message(it, device, state.indexContinued) },
        messageShown = shownHere,
    )
}

/** A press on a control of the feature screen of [capability]: as [photosModelsPress]. */
fun photosFeaturePress(viewModel: ModelsViewModel, capability: InferenceCapability, press: PhotosFeatureScreen.Press) = when (press) {
    // ⚠ On is the realm's answer and this device's, in that order ([ModelsViewModel.setRealmConsent]).
    PhotosFeatureScreen.Press.TurnOnHere -> viewModel.setRealmConsent(capability, true)
    PhotosFeatureScreen.Press.TurnOffHere -> viewModel.setDeviceConsent(capability, false)
    is PhotosFeatureScreen.Press.TurnOffOther -> viewModel.turnOffOtherDevice(capability, press.deviceId)
    PhotosFeatureScreen.Press.TurnOffEverywhere -> viewModel.turnOffEverywhere(capability)
}
