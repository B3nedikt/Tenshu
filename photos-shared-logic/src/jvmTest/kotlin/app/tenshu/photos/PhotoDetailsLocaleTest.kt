package app.tenshu.photos

import app.tenshu.photos.model.PhotoDetails
import app.tenshu.photos.model.apertureLabel
import app.tenshu.photos.model.exposureBiasLabel
import app.tenshu.photos.model.exposureLabel
import app.tenshu.photos.model.focalLengthLabel
import org.tenshu.core.file.MediaMetadata
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The info sheet's figures read in the user's language: `12,2 MP`, `f/1,8`, `+0,3 EV` in German.
 * JVM-only because it moves the process locale, which is what the labels read (they are properties,
 * so there is no language argument to pass).
 */
class PhotoDetailsLocaleTest {

    private val media = MediaMetadata(
        pixelWidth = 4032,
        pixelHeight = 3024,
        fNumber = 1.78,
        focalLengthMm = 5.7,
        focalLength35mm = 26,
        exposureTimeSeconds = 2.5,
        exposureBiasEv = 0.33,
    )

    private fun labels(locale: Locale): List<String?> {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            val details = PhotoDetails("/a.jpg", capturedAtMs = 0L, sizeBytes = 2_412_000L, media = media)
            return listOf(
                details.megapixels,
                details.size,
                media.apertureLabel,
                media.focalLengthLabel,
                media.exposureLabel,
                media.exposureBiasLabel,
                media.copy(fNumber = 2.0).apertureLabel,
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun englishUsesAPoint() {
        assertEquals(
            listOf("12.2 MP", "2.4 MB", "f/1.8", "5.7 mm (26 mm equivalent)", "2.5 s", "+0.3 EV", "f/2"),
            labels(Locale.ENGLISH),
        )
    }

    @Test
    fun germanUsesAComma() {
        assertEquals(
            listOf("12,2 MP", "2,4 MB", "f/1,8", "5,7 mm (26 mm equivalent)", "2,5 s", "+0,3 EV", "f/2"),
            labels(Locale.GERMAN),
        )
    }
}
