package app.tenshu.drive.ui

import org.tenshu.core.entity.DataId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What Drive will and will not open when another app names a row (TT-2a). */
class OpenTargetRoutingTest {

    private fun stringId(type: String, key: String) =
        DataId.StringDataId.create(context = null, type = type, keyValue = key)

    @Test
    fun aFileTargetYieldsItsPath() {
        assertEquals(
            "/Backup/Phone/Camera/IMG_1.jpg",
            filePathFromTarget(stringId("tenshu/file", "/Backup/Phone/Camera/IMG_1.jpg")),
        )
    }

    @Test
    fun aTargetOfAnotherTypeIsIgnored() {
        // ⚠⚠ The rule worth pinning. Reading `keyValue` off whatever arrives would open the file
        // viewer on a `placeId` — a wrong screen with a plausible title, which is far harder to
        // notice than a tap that refuses.
        assertNull(filePathFromTarget(stringId("tenshu/place/place", "place-7")))
        assertNull(filePathFromTarget(stringId("tenshu/timeline/moment", "moment-7")))
    }

    @Test
    fun aMultiPartIdIsNotAFile() {
        // A calendar occurrence carries `(eventId, recurrenceId)`. Even relabelled with a file's
        // type it is not something Drive can open, and the cast is what says so.
        val composite = DataId.CompositeEntityDataId.create(
            context = null,
            type = "tenshu/file",
            keyValues = listOf(stringId("tenshu/file/a", "x"), stringId("tenshu/file/b", "y")),
        )
        assertNull(filePathFromTarget(composite))
    }

    @Test
    fun anEmptyPathIsNotAFile() {
        // ⚠ `Destination.FilePreview("")` would open a viewer on nothing at all.
        assertNull(filePathFromTarget(stringId("tenshu/file", "")))
    }
}
