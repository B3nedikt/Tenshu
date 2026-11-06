package app.tenshu.control.data

import org.tenshu.core.Data
import org.tenshu.core.file.notifications.ChangeLostNotification
import org.tenshu.core.file.notifications.FileAddedNotification
import org.tenshu.core.file.notifications.FileDeletedNotification
import org.tenshu.core.file.notifications.FolderAddedNotification
import org.tenshu.core.file.notifications.FolderDeletedNotification
import org.tenshu.core.notifications.Notification
import org.tenshu.core.number.toTInteger
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class NotificationMapperTest {

    private val mapper = NotificationMapper()

    /** Minimal hand-rolled composite — just the fields `mapToEventOrNull` reads. */
    private fun fileAddedNotification(
        uuid: String,
        timestampMs: Long,
        path: String,
        count: Long?,
    ): FileAddedNotification = object : FileAddedNotification {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Data> getOptionalField(reference: String): T? = when (reference) {
            Notification.Fields.UUID -> uuid.toTString() as T
            Notification.Fields.TIMESTAMP -> unixInstant(timestampMs) as T
            FileAddedNotification.Fields.PATH -> path.toTString() as T
            FileAddedNotification.Fields.COUNT -> count?.toTInteger() as T?
            else -> null
        }

        override fun <T : Data> getField(reference: String): T =
            getOptionalField(reference) ?: error("Field $reference not stubbed")
    }

    private fun unixInstant(ms: Long): UnixInstant = object : UnixInstant {
        override fun get(): Long = ms
        override fun toEpochMilliseconds(): Long = ms
        override val value: Data get() = this
    }

    // --- CR-2 B4 P3 (`content-plane-cr2-b4-spec.md` §2.4) ---

    @Test
    fun aChangeLostNotice_mapsToItsOwnEvent_withTheFileNameAndPath() {
        val notice = object : ChangeLostNotification {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Data> getOptionalField(reference: String): T? = when (reference) {
                Notification.Fields.UUID -> "u-lost".toTString() as T
                Notification.Fields.TIMESTAMP -> unixInstant(7_000) as T
                ChangeLostNotification.Fields.FILE_NAME -> "report.pdf".toTString() as T
                ChangeLostNotification.Fields.PATH -> "/work/report.pdf".toTString() as T
                else -> null
            }

            override fun <T : Data> getField(reference: String): T =
                getOptionalField(reference) ?: error("Field $reference not stubbed")
        }

        assertEquals(
            Event.FileEvent.ChangeLost("u-lost", Instant.fromEpochMilliseconds(7_000), "report.pdf", "/work/report.pdf"),
            mapper.mapToEventOrNull(notice),
        )
    }

    // --- mapToEventOrNull: producer-coalesced rows (notification-burst-aggregation §2.1) ---

    @Test
    fun coalescedRow_countGreaterThanOne_mapsToMultipleAdded() {
        val event = mapper.mapToEventOrNull(
            fileAddedNotification(uuid = "u1", timestampMs = 42_000, path = "/photos/last.jpg", count = 5)
        )

        assertTrue(event is Event.FileEvent.MultipleAdded)
        assertEquals("/photos/last.jpg", event.firstFileName)
        assertEquals(4, event.otherFilesCount)
        assertEquals("u1", event.id)
        assertEquals(Instant.fromEpochMilliseconds(42_000), event.timestamp)
    }

    @Test
    fun countOne_mapsToPlainAdded() {
        val event = mapper.mapToEventOrNull(
            fileAddedNotification(uuid = "u1", timestampMs = 42_000, path = "/a.txt", count = 1)
        )

        assertTrue(event is Event.FileEvent.Added)
        assertEquals("/a.txt", event.fileName)
    }

    @Test
    fun legacyRowWithoutCount_mapsToPlainAdded() {
        // A row minted before the `count` field existed reads it as null.
        val event = mapper.mapToEventOrNull(
            fileAddedNotification(uuid = "u1", timestampMs = 42_000, path = "/a.txt", count = null)
        )

        assertTrue(event is Event.FileEvent.Added)
    }

    // --- Folder vs file: the feed's title ("Folder Added" / "File Deleted") is chosen from the
    // event's `isFolder`, which only the notification's *type* can supply. ---

    private fun stubFields(uuid: String, path: String, pathField: String): Map<String, Data> = mapOf(
        Notification.Fields.UUID to uuid.toTString(),
        Notification.Fields.TIMESTAMP to unixInstant(42_000),
        pathField to path.toTString(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun folderAdded(path: String): FolderAddedNotification = object : FolderAddedNotification {
        val f = stubFields("u1", path, FileAddedNotification.Fields.PATH)
        override fun <T : Data> getOptionalField(reference: String): T? = f[reference] as T?
        override fun <T : Data> getField(reference: String): T = getOptionalField(reference) ?: error(reference)
    }

    @Suppress("UNCHECKED_CAST")
    private fun fileDeleted(path: String): FileDeletedNotification = object : FileDeletedNotification {
        val f = stubFields("u1", path, FileDeletedNotification.Fields.PATH)
        override fun <T : Data> getOptionalField(reference: String): T? = f[reference] as T?
        override fun <T : Data> getField(reference: String): T = getOptionalField(reference) ?: error(reference)
    }

    @Suppress("UNCHECKED_CAST")
    private fun folderDeleted(path: String): FolderDeletedNotification = object : FolderDeletedNotification {
        val f = stubFields("u1", path, FileDeletedNotification.Fields.PATH)
        override fun <T : Data> getOptionalField(reference: String): T? = f[reference] as T?
        override fun <T : Data> getField(reference: String): T = getOptionalField(reference) ?: error(reference)
    }

    @Test
    fun folderAdded_mapsToAddedWithIsFolder() {
        val event = mapper.mapToEventOrNull(folderAdded("/Taxes"))
        assertTrue(event is Event.FileEvent.Added)
        assertTrue(event.isFolder)
        assertEquals("/Taxes", event.fileName)
    }

    @Test
    fun fileAdded_mapsToAddedWithoutIsFolder() {
        val event = mapper.mapToEventOrNull(
            fileAddedNotification(uuid = "u1", timestampMs = 42_000, path = "/a.txt", count = 1)
        )
        assertTrue(event is Event.FileEvent.Added)
        assertFalse(event.isFolder)
    }

    @Test
    fun folderDeleted_mapsToDeletedWithIsFolder() {
        val event = mapper.mapToEventOrNull(folderDeleted("/Taxes"))
        assertTrue(event is Event.FileEvent.Deleted)
        assertTrue(event.isFolder)
        assertEquals("/Taxes", event.fileName)
    }

    @Test
    fun fileDeleted_mapsToDeletedWithoutIsFolder() {
        val event = mapper.mapToEventOrNull(fileDeleted("/old.txt"))
        assertTrue(event is Event.FileEvent.Deleted)
        assertFalse(event.isFolder)
    }

    // NOTE: the `aggregate(events)` tests are gone with the fold itself —
    // burst-collapsing is a producer concern now, pinned by
    // `UploadFileNotificationBurstTest` (`:file-service-proxy`). See
    // `docs/proposals/notification-burst-aggregation.md`.
}
