package app.tenshu.control.inference

import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.PreflightFinding
import org.tenshu.inference.proxy.PreflightKey
import org.tenshu.inference.proxy.PreflightOutcome
import org.tenshu.inference.proxy.PreflightReport
import org.tenshu.inference.proxy.PrepareKey
import org.tenshu.inference.proxy.PrepareOutcome
import org.tenshu.inference.proxy.PrepareRefusal
import org.tenshu.inference.proxy.PrepareReport
import org.tenshu.inference.proxy.ReadinessStanding
import org.tenshu.inference.proxy.catalog.InstalledModelData
import org.tenshu.inference.proxy.catalog.JournalledModelDownload
import org.tenshu.inference.proxy.catalog.ModelDownloadJournalView
import org.tenshu.inference.proxy.catalog.ModelDownloadOutcome
import org.tenshu.inference.proxy.catalog.ModelDownloadProgressSnapshot
import org.tenshu.inference.proxy.catalog.ModelDownloadRefusal
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.downloadKey

/** What the store answered about what is installed. ⚠ Three values; none is another's default. */
sealed interface InstalledRead {
    data object NotReadYet : InstalledRead

    /** The index could not be read, **or what is kept there is not an index** (`ModelStore.read`). */
    data object Unreadable : InstalledRead

    /**
     * @param record the model's install record, or `null`: the index was read and holds none.
     * @param prepareKey what a prepare of [record] is keyed by; [preflightKey] what a probe of
     *   it is. Read with the record, so a report is compared with **this** install.
     * @param onDeviceBytes what [record] takes on the device, from its own sizes.
     */
    data class Read(
        val record: InstalledModelData?,
        val prepareKey: PrepareKey?,
        val preflightKey: PreflightKey?,
        val onDeviceBytes: Long = record?.byteSize ?: 0L,
    ) : InstalledRead
}

/** What the catalog answered. ⚠ Three values, as [InstalledRead]. */
sealed interface CatalogRead {
    data object NotReadYet : CatalogRead
    data object Unavailable : CatalogRead

    /**
     * @param entry the catalog's entry for the model, or `null`: it was read and offers none.
     * @param stale a later refresh failed, and this is the catalog as it was last read.
     */
    data class Read(val entry: ModelEntryData?, val onDeviceBytes: Long?, val stale: Boolean = false) : CatalogRead
}

/**
 * Everything **read from the device** about the one model: the store, readiness (whose state
 * is the verdict file's and the markers'), the catalog, and the download journal **as the
 * downloader's last step read it** (a memory of that read, as old as that step: see
 * `BackgroundModelDownloader.journalView`). ⚠ What decides which case a row is. See [modelsOf].
 */
data class ModelOnDisk(
    val modelId: String,
    val retired: Boolean,
    /** Readiness's state **with the install it is about** (`ReadinessStanding`). */
    val readiness: ReadinessStanding,
    val installed: InstalledRead,
    val catalog: CatalogRead,
    val journal: ModelDownloadJournalView,
    /**
     * What the verdict's file says of the catalog's entry **as this device would install it**
     * (its digests and this OS build), for a model that is not installed; `null` where it was
     * not asked. ⚠ So that no download is offered that the key would answer at once (§14.5).
     */
    val offered: AiReadiness.KeyAnswer? = null,
)

/**
 * Everything the **process** was last told, or is doing now, each with the identity it is
 * about. ⚠ It fills in details of a case [ModelOnDisk] chose. It chooses none.
 */
data class ModelSaid(
    val download: ModelDownloadOutcome? = null,
    val progress: ModelDownloadProgressSnapshot? = null,
    /** The install a tower is being compiled for now (`ModelPreparer.compiling`). */
    val compiling: PrepareKey? = null,
    /** The install the running probe is of (`Preflight.probing`). */
    val probing: PreflightKey? = null,
    val prepare: PrepareReport? = null,
    /** The last call that read no install, with the install readiness was about then (`PrepareRefusal`). */
    val prepareRefusal: PrepareRefusal? = null,
    val preflight: PreflightReport? = null,
    /** This source's own download calls whose callers stopped waiting, by model ([ModelSetup.Pressed]). */
    val pressed: Map<String, ModelSetup.Pressed> = emptyMap(),
)

/**
 * The one value a Models screen renders (`ios-on-device-inference.md` §14.18.3).
 *
 * ⚠⚠ **What the process remembers never chooses a case.** Every branch below is taken on
 * [disk] alone: the three functions that read [said] are reached only after [disk] has decided
 * that the model is absent, being fetched or held, and each can return only that case
 * ([absent] an [ModelSetup.Absent], [running] a [ModelSetup.Download], [held] a
 * [ModelSetup.Held] whose standing is switched on readiness's state). An outcome that says
 * *installed* where the store holds no record, or a report about an install that is gone,
 * therefore has nowhere to say it.
 * ⚠ "Disk" is exact for the store and for readiness. For a download it is the journal **as a
 * step last read it**: the downloader keeps that in memory, replaced whole at every read.
 * ⚠ And every remembered value is used only where its key is the key of what the disk holds.
 * ⚠ Never [ModelsAvailability.NotOffered]: a graph that was not admitted has no source.
 */
fun modelsOf(disk: ModelOnDisk, said: ModelSaid): ModelsAvailability {
    if (disk.retired || disk.journal is ModelDownloadJournalView.Retired) return ModelsAvailability.NotConnected
    val installed: InstalledRead.Read = when (val read: InstalledRead = disk.installed) {
        InstalledRead.NotReadYet -> return ModelsAvailability.Loading
        // ⚠ Before anything else is looked at: nothing is offered over an index nobody could read.
        InstalledRead.Unreadable -> return ModelsAvailability.InstalledUnreadable
        is InstalledRead.Read -> read
    }
    val downloads: ModelsAvailability.Downloads = when (disk.journal) {
        ModelDownloadJournalView.NotLookedYet -> return ModelsAvailability.Loading
        ModelDownloadJournalView.Retired -> return ModelsAvailability.NotConnected
        is ModelDownloadJournalView.Unreadable -> ModelsAvailability.Downloads.UNREADABLE
        is ModelDownloadJournalView.Read -> ModelsAvailability.Downloads.KNOWN
    }
    val catalog: ModelsAvailability.Catalog = when (val read: CatalogRead = disk.catalog) {
        CatalogRead.NotReadYet -> ModelsAvailability.Catalog.NOT_READ_YET
        CatalogRead.Unavailable -> ModelsAvailability.Catalog.UNAVAILABLE
        is CatalogRead.Read -> if (read.stale) ModelsAvailability.Catalog.STALE else ModelsAvailability.Catalog.READ
    }
    val entry: ModelEntryData? = (disk.catalog as? CatalogRead.Read)?.entry
    val name: String = entry?.name?.ifBlank { null } ?: disk.modelId

    val record: InstalledModelData? = installed.record
    val setup: ModelSetup? = if (record != null) {
        held(record, name, installed, disk, said)
    } else {
        when (val journal: ModelDownloadJournalView = disk.journal) {
            is ModelDownloadJournalView.Read ->
                journal.named[disk.modelId]?.let { ModelSetup.Fetching(offered(disk.catalog), running(it, disk.modelId, said)) }
                    ?: entry?.let { absent(it, (disk.catalog as CatalogRead.Read).onDeviceBytes, disk.offered, said) }
            // ⚠ Whether a download exists is not known: the model is shown neither as being
            // fetched nor as absent. The list says so ([ModelsAvailability.Downloads.UNREADABLE]).
            else -> null
        }
    }
    return ModelsAvailability.Listed(catalog, downloads, listOfNotNull(setup?.let { ModelRow(disk.modelId, name, it) }))
}

private fun offered(catalog: CatalogRead): OfferedModel? =
    (catalog as? CatalogRead.Read)?.let { read -> read.entry?.offered(read.onDeviceBytes) }

private fun ModelEntryData.offered(onDeviceBytes: Long?): OfferedModel = OfferedModel(
    modelId = modelId,
    name = name.ifBlank { modelId },
    modelVersion = modelVersion,
    downloadBytes = byteSize,
    onDeviceBytes = onDeviceBytes,
    licence = licence,
)

/** ⚠ Returns an [ModelSetup.Absent] and nothing else, whatever [said] holds. */
private fun absent(entry: ModelEntryData, onDeviceBytes: Long?, offered: AiReadiness.KeyAnswer?, said: ModelSaid): ModelSetup.Absent {
    // ⚠ Only what was said of a download of exactly these bytes under this entry.
    val last: ModelDownloadOutcome? = said.download?.takeIf { it.key == entry.downloadKey() }
    val answered: ModelSetup.Answered? = when (offered) {
        is AiReadiness.KeyAnswer.Unsuitable -> if (offered.stoppedLoading) ModelSetup.Answered.STOPPED_LOADING else ModelSetup.Answered.UNSUITABLE
        // ⚠ Not asked, nothing for it, or not readable: a download is offered as before. A file
        // that could not be read answers nothing, and concludes nothing either way.
        AiReadiness.KeyAnswer.Open, AiReadiness.KeyAnswer.Unreadable, null -> null
    }
    return ModelSetup.Absent(entry.offered(onDeviceBytes), last?.said(), answered = answered, pressed = said.pressed[entry.modelId])
}

/** ⚠ Returns a [ModelSetup.Download] and nothing else: the journal named it. */
private fun running(named: JournalledModelDownload, modelId: String, said: ModelSaid): ModelSetup.Download {
    val total: Long = named.key.artifacts.sumOf { it.byteSize }
    return ModelSetup.Download(
        allowsCellular = named.allowsCellular,
        // ⚠ A bar is the record's: a figure against another total is another record's.
        writtenBytes = said.progress?.takeIf { it.modelId == modelId && it.totalBytes == total }?.writtenBytes,
        totalBytes = total,
        held = said.download?.takeIf { it.key == named.key }?.said()
            // ⚠ The journal names it, so it was neither cancelled nor installed to the end.
            ?.takeUnless { it == ModelSetup.DownloadSaid.Cancelled },
    )
}

/** What an outcome lets a screen say. ⚠ `Installed` says nothing here: the store says that. */
internal fun ModelDownloadOutcome.said(): ModelSetup.DownloadSaid? = when (this) {
    is ModelDownloadOutcome.Installed -> null
    is ModelDownloadOutcome.Cancelled -> ModelSetup.DownloadSaid.Cancelled
    is ModelDownloadOutcome.Failed -> ModelSetup.DownloadSaid.Failed(integrity)
    is ModelDownloadOutcome.Refused -> reason.said()
}

internal fun ModelDownloadRefusal.said(): ModelSetup.DownloadSaid = when (this) {
    is ModelDownloadRefusal.InsufficientStorage -> ModelSetup.DownloadSaid.NeedsSpace(neededBytes, freeBytes)
    ModelDownloadRefusal.FreeSpaceUnknown -> ModelSetup.DownloadSaid.FreeSpaceUnknown
    ModelDownloadRefusal.NoTransport -> ModelSetup.DownloadSaid.NoTransport
    ModelDownloadRefusal.JournalUnreadable -> ModelSetup.DownloadSaid.JournalUnreadable
    is ModelDownloadRefusal.StoreRefused -> ModelSetup.DownloadSaid.StoreRefused
    ModelDownloadRefusal.TransfersUnknown -> ModelSetup.DownloadSaid.TransfersUnknown
}

/** ⚠ Returns a [ModelSetup.Held] and nothing else: the store holds [record]. */
private fun held(
    record: InstalledModelData,
    name: String,
    installed: InstalledRead.Read,
    disk: ModelOnDisk,
    said: ModelSaid,
): ModelSetup.Held = ModelSetup.Held(
    model = HeldModel(record.modelId, name, record.modelVersion, installed.onDeviceBytes, record.licence),
    // ⚠⚠ Switched on what readiness READ **of this install**, and on nothing remembered.
    standing = when (val readiness: ReadinessStanding = disk.readiness) {
        ReadinessStanding.NotReadYet -> ModelSetup.Standing.NotReadYet
        ReadinessStanding.Unreadable -> ModelSetup.Standing.Unreadable
        is ReadinessStanding.For -> when {
            // ⚠⚠ Compared WHOLE with the key read beside the record: a state about another
            // install (the one an update has just replaced) is no state of this one, however
            // briefly the two are seen together.
            readiness.key != installed.preflightKey -> ModelSetup.Standing.NotReadYet
            else -> when (readiness.state) {
                AiReadinessState.Ready -> ModelSetup.Standing.Ready
                AiReadinessState.Unsuitable ->
                    if (readiness.stoppedLoading) ModelSetup.Standing.StoppedLoading else ModelSetup.Standing.Unsuitable
                AiReadinessState.AwaitingVerdict -> ModelSetup.Standing.Checking(checking(installed.preflightKey, said))
                // ⚠ A record readiness read and does not count as a model is one that is not prepared.
                AiReadinessState.Preparing, AiReadinessState.NoModel ->
                    ModelSetup.Standing.Preparing(preparing(installed.prepareKey, installed.preflightKey, said))
            }
        }
    },
    update = update(record, disk, said),
)

private fun preparing(key: PrepareKey?, standingKey: PreflightKey?, said: ModelSaid): ModelSetup.Standing.Preparing.How {
    // ⚠ What is happening now comes before what was last said.
    if (key != null && said.compiling == key) return ModelSetup.Standing.Preparing.How.Now
    // ⚠ Of THIS install: a report about other towers is another install's.
    val mine: PrepareOutcome? = said.prepare?.takeIf { key != null && it.key == key }?.outcome
    when (mine) {
        is PrepareOutcome.Failed -> return ModelSetup.Standing.Preparing.How.Failed(
            mine.role,
            mine.space?.let { ModelSetup.Space(it.neededBytes, it.freeBytes) },
        )
        is PrepareOutcome.Incomplete -> return ModelSetup.Standing.Preparing.How.Incomplete
        is PrepareOutcome.Unreadable -> return ModelSetup.Standing.Preparing.How.Unreadable
        else -> Unit
    }
    // The last call that read no install at all.
    // ⚠⚠ Only while it is about THIS install: it carries the key readiness was about when it
    // was made, and a refusal made before this install existed (or with no install read at
    // all: `about == null`) says nothing of it.
    val refused: PrepareOutcome? = said.prepareRefusal?.takeIf { it.about != null && it.about == standingKey }?.outcome
    return when (refused) {
        PrepareOutcome.NotInForeground -> ModelSetup.Standing.Preparing.How.NotInForeground
        is PrepareOutcome.Unreadable -> ModelSetup.Standing.Preparing.How.Unreadable
        else -> ModelSetup.Standing.Preparing.How.Pending
    }
}

private fun checking(key: PreflightKey?, said: ModelSaid): ModelSetup.Standing.Checking.How {
    // ⚠⚠ A probe that is running comes before any report: the report is the attempt BEFORE it.
    if (key != null && said.probing == key) return ModelSetup.Standing.Checking.How.Running
    val report: PreflightReport = said.preflight ?: return ModelSetup.Standing.Checking.How.Pending
    // ⚠⚠ A report says something of an install only when it carries that install's key. One
    // with no key read no install (Photos was not in the foreground; the files could not be
    // read): it is about no install, this one included, and would otherwise be carried over
    // to whatever is installed next.
    if (report.key == null || report.key != key) return ModelSetup.Standing.Checking.How.Pending
    return when (val outcome: PreflightOutcome = report.outcome) {
        is PreflightOutcome.Waiting -> when (outcome.cause) {
            PreflightOutcome.Waiting.Cause.FOREGROUND_SPENT ->
                ModelSetup.Standing.Checking.How.Paused(ModelSetup.Standing.Checking.Why.ALREADY_TRIED_THIS_TIME)
            PreflightOutcome.Waiting.Cause.BACKING_OFF ->
                ModelSetup.Standing.Checking.How.Paused(ModelSetup.Standing.Checking.Why.BACKING_OFF)
            // ⚠ A screen that is on screen is in the foreground.
            PreflightOutcome.Waiting.Cause.NOT_IN_FOREGROUND -> ModelSetup.Standing.Checking.How.Pending
        }
        is PreflightOutcome.Retry -> when (val finding: PreflightFinding = outcome.finding) {
            is PreflightFinding.Slow -> ModelSetup.Standing.Checking.How.Paused(ModelSetup.Standing.Checking.Why.SLOW)
            is PreflightFinding.Transient -> ModelSetup.Standing.Checking.How.Paused(
                when (finding.cause) {
                    PreflightFinding.Transient.Cause.HOT -> ModelSetup.Standing.Checking.Why.HOT
                    PreflightFinding.Transient.Cause.MEMORY_PRESSURE -> ModelSetup.Standing.Checking.Why.MEMORY_PRESSURE
                },
            )
            else -> ModelSetup.Standing.Checking.How.Pending
        }
        PreflightOutcome.InstallFailed -> ModelSetup.Standing.Checking.How.InstallFailed
        PreflightOutcome.NoProbe -> ModelSetup.Standing.Checking.How.NoProbe
        PreflightOutcome.Unreadable -> ModelSetup.Standing.Checking.How.Unreadable
        else -> ModelSetup.Standing.Checking.How.Pending
    }
}

private fun update(record: InstalledModelData, disk: ModelOnDisk, said: ModelSaid): ModelSetup.Update {
    when (val journal: ModelDownloadJournalView = disk.journal) {
        is ModelDownloadJournalView.Read ->
            journal.named[disk.modelId]?.let { return ModelSetup.Update.Fetching(running(it, disk.modelId, said)) }
        // ⚠ Not known: none is claimed, none is ruled out, and none is offered beside one that may run.
        else -> return ModelSetup.Update.Unknown
    }
    return when (val catalog: CatalogRead = disk.catalog) {
        // ⚠ With nothing to compare against, no update is claimed and none is ruled out.
        CatalogRead.NotReadYet, CatalogRead.Unavailable -> ModelSetup.Update.Unknown
        is CatalogRead.Read -> catalog.entry
            ?.takeIf { it.modelVersion > record.modelVersion }
            ?.let { entry ->
                // ⚠ Why the last download of exactly these bytes ended, as for an absent model: a
                // refused or failed update said nothing on its row, and was offered again as if
                // it had never been tried (Android's repository fills it the same way).
                ModelSetup.Update.Available(
                    entry.offered(catalog.onDeviceBytes),
                    said.download?.takeIf { it.key == entry.downloadKey() }?.said(),
                    pressed = said.pressed[disk.modelId],
                )
            }
            ?: ModelSetup.Update.None
    }
}
