package app.tenshu.timeline

import app.tenshu.takeout.StreamingTakeoutArchive
import app.tenshu.takeout.TakeoutArchive
import app.tenshu.timeline.data.FileTimelineImporter
import app.tenshu.timeline.data.TakeoutPhotoImporter
import app.tenshu.timeline.data.importTakeout
import app.tenshu.timeline.data.TimelineImportControl
import app.tenshu.timeline.data.TimelineImportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The desktop [TimelineImportControl] (B2) — the twin of the Android one, so the **Settings → Import**
 * row is the same row on both platforms.
 *
 * ⚠ A holder for the same reason: the AWT file dialog needs the window, which the `main` function
 * owns and the DI singleton does not. The guided first-run dialog still exists beside this; it is a
 * second door onto the same import, not a different one.
 */
class DesktopTimelineImportControl(
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

    private var choose: (() -> File?)? = null

    /** [choose] opens the platform dialog and returns what the user picked, or `null`. */
    fun attach(choose: () -> File?) {
        this.choose = choose
    }

    override fun pick() {
        val file = choose?.invoke() ?: return
        _status.value = TimelineImportStatus.Running(file.name)
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    importTakeout(
                        archive = archiveFor(file),
                        timeline = importer(),
                        photos = photoImporter().takeIf { _includePhotos.value },
                    )
                }
            }
            _status.value = result.fold(
                onSuccess = { TimelineImportStatus.Done(file.name, it) },
                onFailure = {
                    TimelineImportStatus.Failed(
                        file.name,
                        "Import failed — the entries could not be written. Is Tenshu Control set " +
                            "up on this device? (${it.message})",
                    )
                },
            )
        }
    }

    override fun dismiss() {
        _status.value = TimelineImportStatus.Idle
    }

    private fun archiveFor(file: File): TakeoutArchive =
        if (file.name.endsWith(".zip", ignoreCase = true)) {
            StreamingTakeoutArchive { file.inputStream() }
        } else {
            // A bare `Timeline.json` — the new on-device export is one file, and a user may have
            // unzipped already.
            TakeoutArchive { wanted, consume ->
                if (wanted(file.name)) consume(file.name, file.readBytes())
            }
        }
}
