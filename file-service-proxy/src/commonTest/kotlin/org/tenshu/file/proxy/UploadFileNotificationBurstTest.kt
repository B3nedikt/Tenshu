package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.FileService
import org.tenshu.core.file.notifications.FileAddedNotification
import org.tenshu.core.file.notifications.FolderAddedNotification
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.id
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.core.number.TInteger
import org.tenshu.core.time.UnixInstant
import org.tenshu.file.impl.FileAddedNotificationCoalescer
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Producer-side burst coalescing (`docs/proposals/notification-burst-aggregation.md`
 * §2.2, `FileAddedNotificationCoalescer`): uploads within the burst window share
 * ONE synced [FileAddedNotification] row whose `count` is bumped in place —
 * instead of one row per file, which made the synced row count unbounded for
 * bulk uploads (~920 rows from one photo backup) and ANR'd Control's events
 * feed. Asserts against the `NOTIFICATIONS` relation the
 * `NotificationRelationMaintainer` derives — the exact surface the events feed
 * reads.
 */
class UploadFileNotificationBurstTest {

    private fun env(proxy: TestProxy): TestEnv {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val tenshu = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return TestEnv(proxy, tenshu.getService(FileService.ID))
    }

    private inner class TestEnv(val proxy: TestProxy, val fileService: FileService) {

        suspend fun upload(name: String) = assertTrue(
            fileService.defaultFileSystem.uploadFile("/", name, byteArrayOf(1, 2, 3)) is TResult.Success
        )

        suspend fun createFolder(path: String) = assertTrue(
            fileService.defaultFileSystem.createFolder(path) is TResult.Success
        )

        /** The NOTIFICATIONS relation, read the way the maintainer writes it. */
        fun notifications(): List<Data> {
            val ref = ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = proxy.tContext,
                    type = TList.ID,
                    relationId = NotificationService.Relations.NOTIFICATIONS
                ),
                isEntity = false,
                elementSchemaId = Notification.ID
            )
            return proxy.executionContext.getArrayData(ref)?.toList() ?: emptyList()
        }

        fun fileAdded(): List<Data> = notifications().filter { it.typeId() == FileAddedNotification.ID }
    }

    private fun Data.typeId(): String = (this as? Entity<*>)?.type?.get() ?: id

    private fun Data.path(): String = field<TString>(FileAddedNotification.Fields.PATH)!!.get()

    private fun Data.count(): Long? = field<TInteger>(FileAddedNotification.Fields.COUNT)?.get()

    private fun Data.uuid(): String = field<TString>(Notification.Fields.UUID)!!.get()

    private fun Data.timestamp(): Long = field<UnixInstant>(Notification.Fields.TIMESTAMP)!!.get()

    private fun <T : Data> Data.field(reference: String): T? = when (this) {
        is Entity<*> -> runCatching { getFieldValue<T>(reference) }.getOrNull()
        is Composite -> runCatching { getField<T>(reference) }.getOrNull()
            ?: runCatching { getOptionalField<T>(reference) }.getOrNull()
        else -> null
    }

    @Test
    fun uploadsWithinWindow_coalesceIntoOneNotification() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val env = env(createFileProxy(backgroundScope, clock = clock))

        env.upload("a.txt")
        clock.advance(10_000); env.upload("b.txt")
        clock.advance(10_000); env.upload("c.txt")

        val rows = env.fileAdded()
        assertEquals(1, rows.size, "A burst must collapse to ONE notification row, not one per file")
        assertEquals(3L, rows[0].count())
        assertEquals("/c.txt", rows[0].path(), "The burst row carries the NEWEST file as headline")
        assertEquals(21_000L, rows[0].timestamp(), "The burst row's timestamp advances with each bump")
    }

    @Test
    fun uploadAfterWindowExpiry_startsAFreshNotification() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val env = env(createFileProxy(backgroundScope, clock = clock))

        env.upload("a.txt")
        clock.advance(FileAddedNotificationCoalescer.BURST_WINDOW_MS + 1)
        env.upload("b.txt")

        val rows = env.fileAdded()
        assertEquals(2, rows.size, "Past the window a new row starts")
        assertEquals(setOf("/a.txt", "/b.txt"), rows.map { it.path() }.toSet())
        assertEquals(listOf(1L, 1L), rows.map { it.count() })
        assertNotEquals(rows[0].uuid(), rows[1].uuid())
    }

    @Test
    fun uploadExactlyAtWindowEdge_stillCoalesces() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val env = env(createFileProxy(backgroundScope, clock = clock))

        env.upload("a.txt")
        clock.advance(FileAddedNotificationCoalescer.BURST_WINDOW_MS)
        env.upload("b.txt")

        val rows = env.fileAdded()
        assertEquals(1, rows.size, "The window is inclusive")
        assertEquals(2L, rows[0].count())
    }

    @Test
    fun singleUpload_carriesCountOne() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val env = env(createFileProxy(backgroundScope, clock = clock))

        env.upload("a.txt")

        val rows = env.fileAdded()
        assertEquals(1, rows.size)
        assertEquals(1L, rows[0].count())
        assertEquals("/a.txt", rows[0].path())
    }

    @Test
    fun interleavedFolderCreation_doesNotBreakTheBurst() = runTest {
        // Delta vs the old client-side fold (which broke a run on any
        // non-file-added row): the burst is a single advancing row; a folder
        // notification minted mid-burst simply coexists with it.
        val clock = FakeClock(initial = 1_000L)
        val env = env(createFileProxy(backgroundScope, clock = clock))

        env.upload("a.txt")
        clock.advance(5_000); env.createFolder("/photos")
        clock.advance(5_000); env.upload("b.txt")

        val fileRows = env.fileAdded()
        assertEquals(1, fileRows.size, "The folder notification must not split the file burst")
        assertEquals(2L, fileRows[0].count())

        val folderRows = env.notifications().filter { it.typeId() == FolderAddedNotification.ID }
        assertEquals(1, folderRows.size)
    }
}
