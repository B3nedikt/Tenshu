package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.photos.time.DateWords
import app.tenshu.photos.ui.screens.feed.FeedDensity
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A German launch reads the feed's headers, the viewer's dates and the density button in German
 * (store-screenshots.md §10 found "October 2026" and "Grouped by month" in a German capture).
 */
@OptIn(ExperimentalTestApi::class)
class PhotosGermanCopyTest {

    private class Seen(val words: DateWords, val month: String, val year: String, val mosaic: String)

    private fun read(locale: Locale): Seen {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            var seen: Seen? = null
            runDesktopComposeUiTest {
                setContent {
                    seen = Seen(
                        rememberDateWords(),
                        FeedDensity.Month.label(),
                        FeedDensity.Year.label(),
                        FeedDensity.Mosaic.label(),
                    )
                }
                waitForIdle()
            }
            return seen!!
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun DateWords.render() = listOf(
        day(17, months[11], 2024),
        month(months[9], 2026),
        short(9, shortMonths[3]),
        "${shortWeekdays[3]}, " + day(9, shortMonths[9], 2026),
    )

    @Test
    fun german() {
        val seen = read(Locale.GERMANY)
        assertEquals(
            listOf("17. Dezember 2024", "Oktober 2026", "9. Apr.", "Do., 9. Okt. 2026"),
            seen.words.render(),
        )
        assertEquals("Nach Monat gruppiert. Gruppierung ändern.", seen.month)
    }

    /** English resources say exactly what [DateWords.English] and [FeedDensity.description] say,
     *  which the SwiftUI shell still reads. */
    @Test
    fun englishResourcesMatchTheSharedEnglish() {
        val seen = read(Locale.US)
        assertEquals(DateWords.English.render(), seen.words.render())
        assertEquals(FeedDensity.Month.description, seen.month)
        assertEquals(FeedDensity.Year.description, seen.year)
        assertEquals(FeedDensity.Mosaic.description, seen.mosaic)
    }
}
