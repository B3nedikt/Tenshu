package org.tenshu.inference.proxy.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.inference.InferenceCapability

/**
 * One artifact handed to a transport that outlives this process (`ios-on-device-inference.md`
 * §14.11).
 *
 * @param ref names the transfer for its whole life, across a relaunch. ⚠ It is also a file name:
 *   the transport delivers the body to `<downloads directory>/<ref>.delivered` and nowhere else.
 * @param allowsCellular D6. ⚠⚠ `false` is **enforced by the transport**: the request may use
 *   neither a cellular nor an "expensive" interface, so a transfer begun on Wi-Fi *waits* when
 *   the phone leaves it. It is never a hint the transport may weigh.
 */
data class ModelTransferRequest(
    val ref: String,
    val url: String,
    val allowsCellular: Boolean,
)

/**
 * A transfer's end, as the transport reports it to [BackgroundModelDownloader.transferFinished]:
 * the transport's own word on whether the report is still about the ref's holder, asked when
 * the report is **acted on**, which is later than when it was made.
 *
 * ⚠⚠ Without the first question a report is believed for what it says. One that waited behind
 * a cancel of its download and the start of the next was taken for the next one's: its failure
 * ended that download, and so did its success, since the file it had delivered was gone with
 * the cancel ("status 200 and delivered nothing").
 */
interface ModelTransferReport {

    /** Whether the transfer still holds its ref: no cancel of the ref was asked for since it ended. */
    fun stands(): Boolean

    /** The report was acted on, or found to be nobody's. Exactly once; the ref is free from here. */
    fun settled()

    /** For a transport that keeps no ledger: every report stands. */
    object Unquestioned : ModelTransferReport {
        override fun stands(): Boolean = true

        override fun settled() = Unit
    }
}

/**
 * The transport [BackgroundModelDownloader] drives: on iOS a background `URLSession`, whose
 * transfers run in `nsurlsessiond` while the app is suspended or dead.
 *
 * ⚠⚠ **It issues one plain GET per artifact and delivers one whole file.** Nothing above this
 * interface ever sends a `Range` or appends to bytes it already holds, so a server that answers a
 * resume with `200` and the whole body (seen once from the model CDN, 2026-10-11) cannot be
 * mis-assembled here: whatever the transport did internally, the delivered file is checked whole,
 * by size and sha256, before anything is installed.
 *
 * ⚠ Only a response with a 2xx status is ever delivered as a file. An error page is not a file.
 */
interface ModelTransferPort {

    /**
     * Hands [request] over and returns at once. The transfer may run much later.
     *
     * ⚠⚠ **A ref the transport already holds is not started a second time.** The transport is
     * the process's and outlives every graph, so it is the one place that can refuse: two
     * graphs of one process (one being torn down, its successor resuming the same journal) may
     * each decide, from an answer of [liveRefs] that was true when it was given, to start the
     * same ref.
     */
    fun start(request: ModelTransferRequest)

    /**
     * The refs somebody **holds**: a transfer running or waiting that no cancel was asked for,
     * **and one that has ended and whose report has not been settled yet**
     * ([BackgroundModelDownloader.transferFinished]): until then the ref is not free, and a
     * caller told it was would start a second transfer for a download about to be ended.
     * ⚠ Not a transfer whose cancel was asked for and has not reached it: a caller told that
     * one is live would start nothing for the download asked for after the cancel.
     *
     * ⚠⚠ `null` is *could not tell*, never *none*. A caller that reads it as none starts every
     * artifact a second time.
     */
    suspend fun liveRefs(): Set<String>?

    /**
     * Stops [ref]'s transfer, if it holds one.
     *
     * ⚠⚠ **A cancelled transfer reports nothing.** A ref may be started again right after it was
     * cancelled, and a late "it was cancelled" would be read as the new transfer's failure.
     */
    fun cancel(ref: String)
}

/** One artifact of a journalled download: what the signed catalog said it is. */
@Serializable
internal data class JournaledArtifact(
    val role: String,
    val path: String,
    val sha256: String,
    val byteSize: Long,
    val ref: String,
)

/**
 * A download as it was asked for: the **verified** catalog entry and the cellular choice.
 *
 * ⚠ No field has a default, so a record of another shape does not decode (see
 * [ModelDownloadJournal]). ⚠ There is no per-artifact state: where an artifact stands is derived
 * from the disk and the transport each time, never remembered here.
 */
@Serializable
internal data class JournaledDownload(
    val modelId: String,
    val name: String,
    val modelVersion: Int,
    val capabilities: List<String>,
    val labelSetVersion: Int,
    val runtime: String,
    val artifactVersion: Int,
    val licence: String,
    val allowsCellular: Boolean,
    val artifacts: List<JournaledArtifact>,
) {
    fun key(): ModelDownloadKey = ModelDownloadKey(
        modelId, modelVersion, labelSetVersion, licence,
        artifacts.map { ModelDownloadKey.Artifact(it.role, it.sha256, it.byteSize) },
    )

    /**
     * The same artifacts, byte for byte, whatever else of the entry moved. ⚠ What the transfers
     * and the staged files depend on, and nothing more: they are named by digest.
     */
    fun sameBytesAs(other: JournaledDownload): Boolean =
        modelId == other.modelId && key().artifacts == other.key().artifacts

    fun toEntry(): ModelEntryData = ModelEntryData(
        modelId = modelId,
        name = name,
        modelVersion = modelVersion,
        capabilities = capabilities.mapNotNull { id -> InferenceCapability.entries.firstOrNull { it.id == id } },
        labelSetVersion = labelSetVersion,
        runtime = runtime,
        artifactVersion = artifactVersion,
        artifacts = artifacts.map { ModelArtifactData(it.role, it.path, it.sha256, it.byteSize) },
        licence = licence,
    )
}

/** What the journal file holds, with the ways of holding nothing kept apart. */
internal sealed interface JournalRead {
    /** No file, or a blank one. */
    data object Absent : JournalRead

    class Held(val downloads: List<JournaledDownload>) : JournalRead

    /** The file is there and its bytes could not be had. What it names is unknown. */
    data object Unreadable : JournalRead

    /** The bytes were read and are not this build's record. */
    data object Undecodable : JournalRead
}

/**
 * The durable record of downloads handed to a transport that outlives the process: one small
 * JSON file in the owner's private [FileDatabase], under the `inference/` tree.
 *
 * ⚠⚠ **Written before a transfer is started, and never cached.** Every operation of
 * [BackgroundModelDownloader] reads it again, so nothing in memory can say what the disk does
 * not, and a process that starts with no memory of a download finds everything it needs to
 * verify and install it here.
 *
 * ⚠⚠ **A read has four results, and the caller decides for each** (the model index's contract,
 * `ios-on-device-inference.md` §13.7, on a file that guards less):
 * - *absent* and *held* are what they say;
 * - *unreadable*: `IosFileDatabase.readBytes` answers `null` for a file it could not read as for
 *   one that is not there, so after a `null` the file's presence is asked separately. Nothing
 *   is started, deleted, cancelled or written on the strength of it;
 * - *undecodable*: no journal, **by decision**. Unlike the index, nothing here names verified
 *   bytes: without its digests a delivered file can never be verified, so there is nothing in
 *   an unreadable-by-shape journal to protect, and refusing would leave a phone that updated
 *   mid-download with a Models screen that never works again.
 */
internal class ModelDownloadJournal(
    private val files: FileDatabase,
    private val path: String,
) {
    fun read(): JournalRead {
        val bytes: ByteArray? = try {
            files.readBytes(path)?.get()
        } catch (failure: Exception) {
            println("ModelDownloadJournal -> could not be read: $failure")
            return JournalRead.Unreadable
        }
        if (bytes == null) {
            val present: Boolean = try {
                files.fileSize(path) != null
            } catch (failure: Exception) {
                return JournalRead.Unreadable
            }
            return if (present) JournalRead.Unreadable else JournalRead.Absent
        }
        val text: String = bytes.decodeToString()
        if (text.isBlank()) return JournalRead.Absent
        val decoded: List<JournaledDownload> = runCatching { json.decodeFromString(serializer, text) }.getOrNull()
            ?: return JournalRead.Undecodable
        return JournalRead.Held(decoded)
    }

    /** ⚠ Throws when the write fails: the caller then does none of what the record would vouch for. */
    fun write(downloads: List<JournaledDownload>) {
        files.writeRawByteArray(path, json.encodeToString(serializer, downloads).encodeToByteArray())
    }

    private companion object {
        val json = Json
        val serializer = ListSerializer(JournaledDownload.serializer())
    }
}
