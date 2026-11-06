package app.tenshu.takeout

import app.tenshu.takeout.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Classifying a Takeout export's entries (B1, TB-D2).
 *
 * ⚠⚠ The test that matters most is the German one: a path-only router imports nothing from a
 * localised export **and reports success**, which is the worst failure shape available — the user
 * did everything right and Tenshu says it worked.
 */
class TakeoutRouterTest {

    private fun entry(name: String) = ZipEntry(name, 0, 0, 0, 0)

    private fun classify(
        vararg names: String,
        contents: Map<String, String> = emptyMap(),
    ): TakeoutScan = TakeoutRouter.classify(names.map(::entry)) { zip ->
        contents[zip.name]?.encodeToByteArray()
    }

    @Test
    fun aTimelineExportIsFoundByItsContent_notItsFolder() {
        val scan = classify(
            "Takeout/Location History (Timeline)/Semantic Location History/2023/2023_JANUARY.json",
            contents = mapOf(
                "Takeout/Location History (Timeline)/Semantic Location History/2023/2023_JANUARY.json"
                    to "{\"timelineObjects\":[]}",
            ),
        )

        assertEquals(TakeoutEntryKind.TIMELINE, scan.entries.single().kind)
    }

    @Test
    fun aGermanExportIsUnderstoodJustAsWell() {
        // ⚠⚠ Google localises the folder names to the account's language. Matching on
        // "Location History" would import NOTHING here and report success.
        val name = "Takeout/Standortverlauf/Semantischer Standortverlauf/2023/2023_JANUAR.json"
        val scan = classify(name, contents = mapOf(name to "{\"timelineObjects\":[]}"))

        assertEquals(TakeoutEntryKind.TIMELINE, scan.entries.single().kind)
    }

    @Test
    fun theNewOnDeviceVintageIsRecognisedToo() {
        val scan = classify(
            "Timeline.json",
            contents = mapOf("Timeline.json" to "{\"semanticSegments\":[{}]}"),
        )

        // ⚠ The markers are the two `GoogleTimelineParser` itself sniffs, so the router and the
        // parser agree on what a timeline file is by construction rather than by coincidence.
        assertEquals(TakeoutEntryKind.TIMELINE, scan.entries.single().kind)
    }

    @Test
    fun photosAndTheirSidecarsAreTold_apart() {
        val scan = classify(
            "Takeout/Google Photos/2023/IMG_1234.jpg",
            "Takeout/Google Photos/2023/IMG_1234.jpg.json",
            "Takeout/Google Photos/2023/IMG_5678.HEIC.supplemental-metadata.json",
            "Takeout/Google Photos/2023/clip.MP4",
        )

        assertEquals(
            listOf(
                TakeoutEntryKind.PHOTO,
                TakeoutEntryKind.PHOTO_SIDECAR,
                TakeoutEntryKind.PHOTO_SIDECAR,
                TakeoutEntryKind.PHOTO,
            ),
            scan.entries.map { it.kind },
        )
    }

    @Test
    fun aPhotoIsNeverSniffed() {
        // ⚠ An export is thousands of photos and a handful of JSONs. Sniffing every entry would
        // read (and on Android, copy) gigabytes to learn what the extension already said.
        var peeked = 0
        TakeoutRouter.classify(
            listOf(entry("Takeout/Google Photos/a.jpg"), entry("Takeout/Google Photos/b.heic")),
        ) { peeked++; null }

        assertEquals(0, peeked)
    }

    @Test
    fun whatWeDeliberatelyDoNotImportIsCountedAndNamed() {
        val scan = classify(
            "Takeout/Chrome/History.json",
            "Takeout/YouTube/watch-history.html",
            "Takeout/Timeline.json",
            contents = mapOf(
                "Takeout/Chrome/History.json" to "{\"Browser History\":[]}",
                "Takeout/Timeline.json" to "{\"semanticSegments\":[]}",
            ),
        )

        // ⚠⚠ "Understood 1 of 3" is a bug report a user can send us; a blank timeline is not.
        assertEquals(3, scan.total)
        assertEquals(1, scan.understood)
        assertEquals(
            listOf("Takeout/Chrome/History.json", "Takeout/YouTube/watch-history.html"),
            scan.unmatched,
        )
    }

    @Test
    fun anUnreadableEntryIsUnknownRatherThanAssumed() {
        // The peek returns null (a corrupt entry). Guessing "timeline" from the folder is exactly
        // what TB-D2 forbids.
        val scan = classify("Takeout/Location History/2023_JANUARY.json")

        assertEquals(TakeoutEntryKind.UNKNOWN, scan.entries.single().kind)
    }

    @Test
    fun theUnmatchedSampleIsBounded() {
        val many = (1..50).map { "Takeout/Other/file$it.txt" }.toTypedArray()

        val scan = classify(*many)

        assertEquals(50, scan.total)
        assertEquals(0, scan.understood)
        assertTrue(scan.unmatched.size <= 10, "a wall of names is not a report")
    }
}
