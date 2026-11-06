package app.tenshu.control.inference

import org.tenshu.inference.InferenceCapability
import kotlinx.coroutines.flow.Flow

/** A model the verified catalog offers this device, as a screen states it before a download. */
data class OfferedModel(
    val modelId: String,
    val name: String,
    val modelVersion: Int,
    /** The whole checkpoint, as the catalog signs it. */
    val downloadBytes: Long,
    /** What it takes on this device once prepared, or `null` where nothing estimates it. */
    val onDeviceBytes: Long?,
    val licence: String,
)

/**
 * A model this device holds, from its install record.
 *
 * @param name the catalog's name while the catalog lists the model; otherwise the model's
 *   **id**. ⚠ The install record carries no name (it is a catalog field, and adding one is a
 *   reshape), so a model the catalog no longer lists, or one shown with no catalog, is called by
 *   its id. That is what Control's screen has always done (`ModelsRepository`, `facts()`).
 * @param onDeviceBytes what removing it frees, from the record's own sizes: no catalog needed.
 */
data class HeldModel(
    val modelId: String,
    val name: String,
    val modelVersion: Int,
    val onDeviceBytes: Long,
    val licence: String,
)

/** One row of a Models screen. [name] as [HeldModel.name]: the catalog's, or the id. */
data class ModelRow(val modelId: String, val name: String, val setup: ModelSetup)

/**
 * What a Models screen can say **about the list as a whole** (`ios-on-device-inference.md`
 * §14.18): whether there is one, and how far its three reads got.
 *
 * ⚠ Platform-neutral on purpose: nothing here names Core ML, a background session or a scene.
 * ⚠ Not-read-yet, could-not-be-read and there-is-none are different values everywhere below,
 * and nothing folds one into another.
 */
sealed interface ModelsAvailability {

    /**
     * This build or this device is not offered a model at all. ⚠ Made by whoever knows the
     * gates (a repository), never by a source of a running graph: a graph that was not
     * admitted has no source.
     */
    data class NotOffered(val why: Why) : ModelsAvailability {
        enum class Why { NOT_IN_THIS_BUILD, OS_TOO_OLD, NOT_ENOUGH_MEMORY }
    }

    /** The runtime this came from was torn down. ⚠ A failure, never an empty list. */
    data object NotConnected : ModelsAvailability

    /** What is installed, or where it stands, has not been read yet in this process. */
    data object Loading : ModelsAvailability

    /** What is installed could not be read. ⚠ No row, and nothing that writes, may be offered. */
    data object InstalledUnreadable : ModelsAvailability

    /**
     * @param models a row per model that is installed, being fetched, or offered. ⚠ Empty with
     *   [catalog] `READ` is *nothing is published for this device*; with `UNAVAILABLE` it is
     *   *the list could not be loaded*; with `NOT_READ_YET` nothing is claimed.
     */
    data class Listed(val catalog: Catalog, val downloads: Downloads, val models: List<ModelRow>) : ModelsAvailability

    enum class Catalog {
        NOT_READ_YET,
        READ,

        /** The last refresh failed; what is shown is the catalog as it was last read. */
        STALE,
        UNAVAILABLE,
    }

    /** What is known of downloads in progress, for the whole list. */
    enum class Downloads {
        KNOWN,

        /**
         * The download journal could not be read. ⚠ Whether any download exists is **not
         * known**: no row is shown as fetching *or* as absent on the strength of it, and a
         * cancel or a removal reads the same journal and may fail the same way.
         */
        UNREADABLE,
    }
}

/**
 * Where one model stands, **as one value a row renders**.
 *
 * ⚠⚠ **Which case this is was decided by what was read from the device**: the store, the
 * state of readiness, the download journal as its last step read it. What the process merely
 * remembers (how the last download ended, why a prepare failed, why a check waits) is carried
 * **inside** a case. It never chooses the case.
 * ⚠ A platform with no prepare step and no check fills [Held] with [Standing.Ready], and
 * leaves the details it has no source for `null`.
 */
sealed interface ModelSetup {

    /**
     * Not installed, and no download of it is known.
     *
     * @param answered this device has answered [model] already (its key, from the catalog's
     *   digests and this OS build, holds a fail): a download of it would be answered at once, so
     *   none is offered (§14.5 item 0). `null` where nothing is known to answer it.
     * @param pressed a download of it that **its own call** is still starting, or that it refused
     *   after the caller stopped waiting (see [Pressed]).
     */
    data class Absent(val model: OfferedModel, val last: DownloadSaid?, val answered: Answered? = null, val pressed: Pressed? = null) : ModelSetup

    /** Why [Absent.answered]. */
    enum class Answered {
        /** Three slow probes on this OS build (D12): no way out but another OS build. */
        UNSUITABLE,

        /** Loads Photos did not survive, and nothing else: a user may try once more. */
        STOPPED_LOADING,
    }

    /**
     * A press on *Download* whose caller stopped waiting (the bound on its wait) while **the call
     * goes on** (§14.17.0). ⚠ The call's own: it says so only while that call lives, and what
     * that call answered once nobody waited for it.
     */
    sealed interface Pressed {
        /** The call has not been told yet whether it was journalled. */
        data object Starting : Pressed

        /** The call was refused after its caller had stopped waiting, for [why]. */
        data class Refused(val why: ModelActionResult.Why) : Pressed
    }

    /**
     * Not installed; a download of it is journalled.
     *
     * @param model `null` when the catalog could not say what is being fetched: the row then
     *   has its id for a name ([ModelRow]) and [Download.totalBytes] for a size.
     */
    data class Fetching(val model: OfferedModel?, val download: Download) : ModelSetup

    /** The store holds it. [standing] is readiness's; [update] is the catalog's and the journal's. */
    data class Held(val model: HeldModel, val standing: Standing, val update: Update) : ModelSetup

    /** A download the journal names. */
    data class Download(
        val allowsCellular: Boolean,
        /** Bytes of the whole checkpoint held so far, or `null` before the first figure. */
        val writtenBytes: Long?,
        val totalBytes: Long,
        /** Why it is not moving on by itself, when the process was told so **of this record**. */
        val held: DownloadSaid?,
    )

    /** What the process was last told about a download of exactly this checkpoint. In memory. */
    sealed interface DownloadSaid {
        data class NeedsSpace(val neededBytes: Long, val freeBytes: Long) : DownloadSaid
        data object FreeSpaceUnknown : DownloadSaid
        data object NoTransport : DownloadSaid
        data object TransfersUnknown : DownloadSaid
        data object JournalUnreadable : DownloadSaid
        data object StoreRefused : DownloadSaid

        /** ⚠ [integrity]: the bytes were not the catalog's. Asking again fetches the same file. */
        data class Failed(val integrity: Boolean) : DownloadSaid
        data object Cancelled : DownloadSaid
    }

    /** Where an installed model stands. ⚠ The case is readiness's state and nothing else's. */
    sealed interface Standing {
        /**
         * Where **this** install stands has not been read yet: readiness has read nothing in
         * this process, or what it last said is about another install (an update has just been
         * committed). ⚠ Never the state of the install before it.
         */
        data object NotReadYet : Standing

        /** Readiness could not read where the model stands. Nothing is claimed; asking again may. */
        data object Unreadable : Standing

        data class Preparing(val how: How) : Standing {
            sealed interface How {
                /** A tower is being compiled now. */
                data object Now : How

                /** Nothing is known to have gone wrong; nothing is being compiled. */
                data object Pending : How
                data object NotInForeground : How

                /** [space] when the step was refused for want of room. */
                data class Failed(val role: String, val space: Space?) : How

                /** A file of the install is missing; a download of the model fetches it. */
                data object Incomplete : How
                data object Unreadable : How
            }
        }

        data class Checking(val how: How) : Standing {
            sealed interface How {
                /** A probe is running now. */
                data object Running : How

                /** A probe is owed and nothing is known to hold it. */
                data object Pending : How
                data class Paused(val why: Why) : How
                data object InstallFailed : How

                /** This build cannot check these artifacts. */
                data object NoProbe : How
                data object Unreadable : How
            }

            enum class Why { ALREADY_TRIED_THIS_TIME, BACKING_OFF, HOT, MEMORY_PRESSURE, SLOW }
        }

        data object Ready : Standing
        data object Unsuitable : Standing

        /**
         * `Unsuitable` because loads of the model were interrupted (Photos was closed while it
         * loaded them), and for no other reason (§14.5 item 0): the user may try once more.
         */
        data object StoppedLoading : Standing
    }

    data class Space(val neededBytes: Long, val freeBytes: Long)

    /** A newer version of an installed model. */
    sealed interface Update {
        /** The catalog offers what is installed, or no longer lists it. */
        data object None : Update

        /** The catalog was not read, or the downloads are not known. ⚠ None is claimed, none ruled out. */
        data object Unknown : Update

        /** @param last as [Absent.last]: what was last said of a download of exactly [model]. */
        data class Available(val model: OfferedModel, val last: DownloadSaid? = null, val pressed: Pressed? = null) : Update
        data class Fetching(val download: Download) : Update
    }
}

/**
 * The model can be used now: the store holds it and it stands [ModelSetup.Standing.Ready].
 * ⚠ Installed is not usable. On a platform with no prepare step and no check the two are one.
 */
val ModelSetup.usable: Boolean
    get() = this is ModelSetup.Held && standing == ModelSetup.Standing.Ready

/** The download this row shows a bar for: its own, or the update's. */
val ModelSetup.download: ModelSetup.Download?
    get() = when (this) {
        is ModelSetup.Absent -> null
        is ModelSetup.Fetching -> download
        is ModelSetup.Held -> (update as? ModelSetup.Update.Fetching)?.download
    }

/**
 * Whether a press on *Download* can do anything for this row: the model is absent, a newer
 * version is offered, or a file of the install is missing. ⚠ The one rule the view model's
 * guard and a screen's button both ask, so neither offers what the other refuses.
 */
val ModelSetup.offersDownload: Boolean
    get() = when (this) {
        is ModelSetup.Absent -> true
        is ModelSetup.Fetching -> false
        is ModelSetup.Held -> update is ModelSetup.Update.Available ||
            (standing as? ModelSetup.Standing.Preparing)?.how is ModelSetup.Standing.Preparing.How.Incomplete
    }

/**
 * Whether this row can be removed. Only what the store holds can; and while an update of it is
 * being fetched, only where a download can be ended first ([canEndADownload]).
 */
fun ModelSetup.offersRemoval(canEndADownload: Boolean): Boolean = when (this) {
    is ModelSetup.Absent, is ModelSetup.Fetching -> false
    is ModelSetup.Held -> canEndADownload || update !is ModelSetup.Update.Fetching
}

/**
 * What an action answered, **at once**. ⚠ Everything after `Accepted` is the row's state
 * ([ModelSetup]): no call stays suspended as a second channel for it, and a download that is
 * accepted and then cannot go on says so in [ModelSetup.Download.held], once.
 */
sealed interface ModelActionResult {
    data object Accepted : ModelActionResult

    /**
     * Not told yet, within the caller's bound: the call goes on, and is neither accepted nor
     * refused. ⚠ Nothing is claimed of it: whether it was journalled is the row's to show, from
     * the journal, when it is.
     */
    data object StillStarting : ModelActionResult
    data class Refused(val why: Why) : ModelActionResult

    sealed interface Why {
        /** As a row would say it. */
        data class Said(val said: ModelSetup.DownloadSaid) : Why

        /** A download of the same bytes runs under the other cellular choice. ⚠ The request's, never the record's. */
        data object OtherCellularChoice : Why
        data object NoCatalog : Why
        data object UnknownModel : Why
        data object NotConnected : Why

        /**
         * Something else is being done to the models that this cannot run beside: the model is
         * being removed, or the repository runs one download at a time and one is running.
         */
        data object Busy : Why

        /** A failure nobody named. */
        data object Unnamed : Why
    }
}

/**
 * The models of the device this process runs on, for a screen in the same process: the read
 * side of the proxy's readiness, download and prepare state, and the two calls no operation
 * carries (§14.18.4).
 *
 * ⚠ An interface for [IndexCoverageSource]'s reason: the answer is made in
 * `:control-shared-proxy`, from types this module must not see. So are its failures: nothing
 * here throws a type of the proxy's.
 */
interface OnDeviceModelSource {

    /**
     * ⚠ Emits at once. When the runtime behind it is torn down it emits
     * [ModelsAvailability.NotConnected] and **completes**: it does not stay at its last value.
     * ⚠ Never [ModelsAvailability.NotOffered]: see there.
     */
    val models: Flow<ModelsAvailability>

    /**
     * The capability ids each model the catalog lists serves, by model id, from the **same**
     * catalog read [models] shows. ⚠ Empty while the catalog was not read, or could not be:
     * nothing is said to serve a feature on the strength of a read that did not happen.
     */
    val serves: Flow<Map<String, List<String>>>

    /** Starts or joins a download. Returns when it was journalled, or refused. */
    suspend fun download(modelId: String, allowsCellular: Boolean): ModelActionResult

    /** Ends a journalled download of [modelId]. `Accepted` also when there was none. */
    suspend fun cancel(modelId: String): ModelActionResult

    /** Runs the launch's check once more, and reads what is installed and the catalog again. */
    suspend fun checkAgain()

    /**
     * The user asks to try [modelId] once more after loads Photos did not survive stopped it
     * ([ModelSetup.Standing.StoppedLoading], [ModelSetup.Answered.STOPPED_LOADING]). ⚠ Nothing for
     * a source that has no such fail.
     */
    suspend fun loadAgain(modelId: String) = Unit

    /**
     * The user has just switched [capability] off, and the write went through: what this device
     * holds loaded for it is let go now (the warmed text tower for search, §14.18.15), not at the
     * next return to the foreground. ⚠ Nothing for a source that warms nothing.
     */
    suspend fun consentWithdrawn(capability: InferenceCapability) = Unit
}

/**
 * Whether the device is on Wi-Fi, as the platform's own monitor says it (`NWPathMonitor` on
 * iOS). ⚠ A seam only (§14.18.3, row 8a): nothing in Kotlin implements it, and a screen that has
 * none says nothing about waiting for Wi-Fi.
 */
fun interface OnDeviceNetworkPath {
    /** `null` while the monitor has not answered. */
    fun onWifi(): Flow<Boolean?>
}
