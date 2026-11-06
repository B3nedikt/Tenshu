package app.tenshu.photos.ui.screens.viewer

import app.tenshu.photos.data.ExifReadResult
import app.tenshu.photos.model.apertureLabel
import app.tenshu.photos.model.exposureBiasLabel
import app.tenshu.photos.model.exposureLabel
import app.tenshu.photos.model.focalLengthLabel

/**
 * One album the photograph is in, as the sheet lists it. ⚠ [albumId] is the identity, not [line]: two
 * albums may share a name and a count, and a list keyed by the words would see one row twice.
 */
data class AboutAlbum(val albumId: String, val line: String)

/** One labelled line of the About sheet — `suffix` is the parenthesised aside ("12.2 MP"). */
data class AboutRow(val label: String, val value: String, val suffix: String? = null)

/**
 * The photographic settings section — four states, and only one of them is rows.
 *
 * ⚠⚠ `Reading` shows nothing rather than a spinner, so the sheet does not appear to load a second
 * time. `NotOnDevice` is said in one line: a FACT about this device, not a failure, and not something
 * to hide — a panel that silently omits the settings for half a library reads as a parser that does
 * not work. `Silent` covers an unreachable proxy and a failed read: a metadata panel is not where the
 * user should learn the proxy is down; the shell's banner already says so.
 */
sealed interface AboutSettings {
    data object Reading : AboutSettings
    data object Silent : AboutSettings
    data class NotOnDevice(val message: String) : AboutSettings
    data class Rows(val rows: List<AboutRow>) : AboutSettings
}

/**
 * What the viewer's About sheet says about the photograph on screen, for both
 * shells.
 *
 * ⚠⚠ Decided here, drawn there. Which rows exist, what they are called and how an album line reads
 * are decided once, not inside Compose's `AboutSheet` and again in the iOS sheet: two copies of a
 * details panel drift into two apps that describe one photograph differently.
 *
 * ⚠ Every row is conditional on its value. The one always present is the path, because it is the only
 * thing the viewer knows without a read — and while the reads are in flight the sheet shows what it
 * has rather than a spinner.
 */
data class AboutSheetContent(
    /** The capture time, formatted by the shell's calendar-aware formatter. */
    val title: String,
    val fileName: String,
    /**
     * One line per album the photograph is in. ⚠ Empty — the section omitted — both while unread and
     * when it is in none: an empty "Albums" heading is a section that exists to say nothing.
     */
    val albums: List<AboutAlbum>,
    /**
     * Where it was taken, only when a NAMED place claims its coordinate (D2). ⚠⚠ Absent for most
     * photographs, and there is no honest fallback — not a country, not a bare coordinate.
     */
    val place: String?,
    /** Under "Details": dimensions, length, camera, size — each when known — and always the path. */
    val details: List<AboutRow>,
    val settings: AboutSettings,
) {
    companion object {
        /** [formatCapturedAt] is the shell's (`PhotoDates`), so a year is a LOCAL year. */
        fun of(state: PhotoViewerUiState, formatCapturedAt: (Long) -> String): AboutSheetContent {
            val photo = state.photo
            val details = state.details
            return AboutSheetContent(
                title = formatCapturedAt(photo.capturedAtMs),
                fileName = photo.path.substringAfterLast('/'),
                albums = state.albums.orEmpty().map { album ->
                    AboutAlbum(
                        albumId = album.albumId,
                        line = "${album.name}  ·  ${album.photoCount} " + if (album.photoCount == 1) "item" else "items",
                    )
                },
                place = state.place?.let { place ->
                    listOfNotNull(place.label.ifBlank { null }, place.city.ifBlank { null })
                        .distinct()
                        .joinToString(", ")
                        .ifBlank { null }
                },
                details = buildList {
                    details?.dimensions?.let { add(AboutRow("Dimensions", it, details.megapixels)) }
                    details?.duration?.let { add(AboutRow("Length", it)) }
                    details?.camera?.let { add(AboutRow("Camera", it)) }
                    details?.size?.let { add(AboutRow("Size", it)) }
                    add(AboutRow("Path", photo.path))
                },
                settings = settingsOf(state.exif),
            )
        }

        private fun settingsOf(exif: ExifReadResult?): AboutSettings = when (exif) {
            null -> AboutSettings.Reading
            ExifReadResult.NotOnDevice ->
                AboutSettings.NotOnDevice("Camera settings need the original, which isn't on this device.")
            ExifReadResult.Unreachable, is ExifReadResult.Failed -> AboutSettings.Silent
            is ExifReadResult.Read -> {
                val media = exif.media
                AboutSettings.Rows(
                    if (media.isEmpty) {
                        emptyList()
                    } else {
                        buildList {
                            media.exposureLabel?.let { add(AboutRow("Exposure", it)) }
                            media.apertureLabel?.let { add(AboutRow("Aperture", it)) }
                            media.isoSpeed?.let { add(AboutRow("ISO", it.toString())) }
                            media.focalLengthLabel?.let { add(AboutRow("Focal length", it)) }
                            media.lensModel?.let { add(AboutRow("Lens", it)) }
                            media.exposureBiasLabel?.let { add(AboutRow("Exposure bias", it)) }
                            media.dateTimeOriginal?.let { add(AboutRow("Taken", it)) }
                            media.software?.let { add(AboutRow("Software", it)) }
                        }
                    },
                )
            }
        }
    }
}
