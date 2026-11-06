package app.tenshu.timeline.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import app.tenshu.takeout.StreamingTakeoutArchive
import app.tenshu.takeout.TakeoutArchive
import app.tenshu.timeline.data.FileTimelineImporter
import app.tenshu.timeline.data.TakeoutPhotoImporter
import app.tenshu.timeline.data.importTakeout
import org.tenshu.core.UserInitiatedElement
import app.tenshu.timeline.data.TimelineImportControl
import app.tenshu.timeline.data.TimelineImportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Android [TimelineImportControl] (B2) — **the first way a phone can import a Takeout export
 * at all**. Until this existed, `FileTimelineImporter` had exactly one caller and it was a desktop
 * file dialog, so the platform where the export actually lands could do nothing with it.
 *
 * ⚠ A holder, for the same reason as Fit's `AndroidHealthFilePicker`: the Storage Access Framework
 * launcher belongs to an `Activity`, while the DI singleton the UI resolves outlives it. The
 * handover is keyed by owner, so a dying Activity cannot clear a newer one's launcher and leave the
 * Import row silently doing nothing.
 */
class AndroidTimelineImportControl(
    private val resolver: ContentResolver,
    private val scope: CoroutineScope,
    private val importer: () -> FileTimelineImporter,
    private val photoImporter: () -> TakeoutPhotoImporter,
) : TimelineImportControl {

    private val _status = MutableStateFlow<TimelineImportStatus>(TimelineImportStatus.Idle)
    override val status: StateFlow<TimelineImportStatus> = _status.asStateFlow()

    private val _includePhotos = MutableStateFlow(false)
    override val includePhotos: StateFlow<Boolean> = _includePhotos.asStateFlow()

    override fun setIncludePhotos(include: Boolean) {
        _includePhotos.value = include
    }

    private var owner: Any? = null
    private var launch: (() -> Unit)? = null

    fun attach(owner: Any, launch: () -> Unit) {
        this.owner = owner
        this.launch = launch
    }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        this.launch = null
    }

    override fun pick() {
        launch?.invoke()
    }

    override fun dismiss() {
        _status.value = TimelineImportStatus.Idle
    }

    /** Called by the Activity's picker result. Runs on the app scope, so it survives a rotation. */
    fun onPicked(uri: Uri) {
        val name = displayName(uri)
        _status.value = TimelineImportStatus.Running(name)
        scope.launch {
            val result = runCatching {
                // The user picked this archive: its writes are user-started, so Control may hold its
                // foreground service until the import is done (control-background-work-off-fgs.md §5.1).
                withContext(Dispatchers.IO + UserInitiatedElement) {
                    importTakeout(
                        archive = archiveFor(uri, name),
                        timeline = importer(),
                        photos = photoImporter().takeIf { _includePhotos.value },
                    )
                }
            }
            _status.value = result.fold(
                onSuccess = { TimelineImportStatus.Done(name, it) },
                onFailure = {
                    Log.e(TAG, "timeline import failed", it)
                    TimelineImportStatus.Failed(
                        name,
                        // ⚠ Names Control, because "could not be written" on this platform almost
                        // always means the proxy is not set up — not that the file was bad.
                        "Import failed — the entries could not be written. Is Tenshu Control set " +
                            "up on this device? (${it.message})",
                    )
                },
            )
        }
    }

    /**
     * ⚠⚠ **Streamed, never copied.** A SAF `content://` cannot seek and a ZIP's index is at the end
     * of the file, so the seekable reader would have needed the whole export copied into cache
     * first — tens of gigabytes, for an import that reads a few megabytes of it.
     * [StreamingTakeoutArchive] re-opens the stream per walk instead.
     */
    private fun archiveFor(uri: Uri, name: String): TakeoutArchive =
        if (name.endsWith(".zip", ignoreCase = true)) {
            StreamingTakeoutArchive { resolver.openInputStream(uri) ?: error("could not open $name") }
        } else {
            // ⚠ A bare `Timeline.json` is a legitimate thing to have — the new on-device export is
            // one file, and a user may have unzipped already. One entry, one walk.
            TakeoutArchive { wanted, consume ->
                if (wanted(name)) {
                    consume(name, resolver.openInputStream(uri).use { it?.readBytes() })
                }
            }
        }

    /** The document's user-visible name — the extension is how we tell an archive from a JSON. */
    private fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val at = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (at >= 0) cursor.getString(at)?.let { return it }
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private companion object {
        const val TAG = "TimelineImport"
    }
}
