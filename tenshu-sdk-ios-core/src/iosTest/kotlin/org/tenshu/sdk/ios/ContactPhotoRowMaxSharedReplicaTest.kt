@file:OptIn(SharedReplicaPlatformApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.client.FullCardRead
import org.tenshu.contact.client.fullVcard
import org.tenshu.contact.importContact
import org.tenshu.contact.test.ContactPhotoFixtures
import org.tenshu.contact.test.FakeInviteRelay
import org.tenshu.contact.test.createContactProxy
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.db.sqlite.ReplicaBuild
import org.tenshu.core.common.proxy.db.sqlite.ReplicaOpenResult
import org.tenshu.core.common.proxy.db.sqlite.RowTooLarge
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaStore
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaDeletion
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaWipeStart
import platform.Foundation.NSThread
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The S0b `rawVcard` blocker, closed (`ios-shared-store-sqlite.md` §4.2.1 / F6s, `contacts-app.md`
 * §9.10): over the **real** iOS SQLite store and its `ROW_MAX` (512 KiB), a contact whose card
 * carries a 2 MiB portrait now imports, where the whole card in one row is `RowTooLarge`.
 * Every name is made up.
 */
class ContactPhotoRowMaxSharedReplicaTest {

    private val build = ReplicaBuild(coreVersion = 22, coreFloor = 22, services = mapOf("example/contact" to 1), serviceFloors = mapOf("example/contact" to 1))

    /** The slice of `UIApplication` the binding's adapter uses: always foreground, always protected. */
    private class App : UiKitApplication {
        private val main: NSThread = NSThread.currentThread
        // ⚠ `==`, never `===`, on an NSThread (K/N).
        override val isMainThread: Boolean get() = NSThread.currentThread == main
        private var nextId = 0uL
        override fun isInBackground(): Boolean = false
        override fun beginBackgroundTask(name: String, onExpired: () -> Unit): ULong? = ++nextId
        override fun endBackgroundTask(id: ULong) = Unit
        override fun backgroundTimeRemainingSeconds(): Double = Double.MAX_VALUE
        override fun observeLifecycle(onDidEnterBackground: () -> Unit, onWillEnterForeground: () -> Unit) = Unit
        override fun runOnMainAsync(block: () -> Unit) = Unit
        override fun isProtectedDataAvailable(): Boolean = true
        override fun observeProtectedData(onChange: (Boolean) -> Unit) = Unit
    }

    private class Seams : SharedReplicaBinding.Seams {
        private val main: NSThread = NSThread.currentThread
        override val isMainThread: Boolean get() = NSThread.currentThread == main
        override fun isAppExtension(): Boolean = false
        override fun open(directory: String, build: ReplicaBuild, steps: org.tenshu.core.common.proxy.db.sqlite.ReplicaMigrationSteps, appName: String, foreground: Boolean, protectedDataAvailable: () -> Boolean): ReplicaOpenResult =
            SharedReplicaBinding.PlatformSeams.open(directory, build, steps, appName, foreground, protectedDataAvailable)
        override fun startWipe(directory: String, build: ReplicaBuild, appName: String, reason: String, protectedDataAvailable: () -> Boolean, beforeHandOff: (jobId: Long) -> Unit): SharedReplicaWipeStart =
            SharedReplicaBinding.PlatformSeams.startWipe(directory, build, appName, reason, protectedDataAvailable, beforeHandOff)
        override fun deleteFiles(directory: String): SharedReplicaDeletion = SharedReplicaBinding.PlatformSeams.deleteFiles(directory)
    }

    /** The real store, opened the way an app opens it: a binding installed at launch, then `open`. */
    private fun sqliteStore(): SharedReplicaStore {
        val directory = NSTemporaryDirectory() + "contact-rowmax-" + Random.nextLong().toString(16) + "/replica"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        val app = App()
        val binding = SharedReplicaBinding(
            directory = directory,
            appName = "app.example.contacts",
            seams = Seams(),
            adapterFactory = { onJobOutcome ->
                SharedReplicaUiKitAdapter(
                    app = app,
                    queue = DispatchReplicaWorkerQueue("org.tenshu.replica.worker.contact-" + Random.nextLong().toString(16)),
                    log = {},
                    onJobOutcome = onJobOutcome,
                )
            },
        )
        binding.installAtLaunch()
        return binding.open(build)
    }

    private val card = ContactPhotoFixtures.card(
        ContactPhotoFixtures.photoLine(ContactPhotoFixtures.image(2 * 1024 * 1024)),
        name = "Noor Haddad",
    )

    @Test
    fun aBigPhotoContactSavesWhereTheWholeCardIsRowTooLarge() {
        val store = sqliteStore() // on the test's thread: `installAtLaunch` is main-only
        // ⚠ Not runTest's 60 s default: this is real work — a 2 MB photo split, sealed and written
        // through the real SQLite replica on a debug K/N build — and GitHub's 3-core macOS runner
        // overran 60 s, failing as a bare `UncompletedCoroutinesError` with nothing wrong.
        runTest(timeout = 5.minutes) { importInto(store) }
    }

    private suspend fun importInto(store: SharedReplicaStore) {
        val harness = createContactProxy(FakeInviteRelay(), identityPhrase = "made-up phrase", objectStore = store, lock = store.databaseLock)
        val executor = LocalTaskExecutor(harness.proxy.executionContext)
        val service: ContactService = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = harness.proxy.schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = harness.proxy.tContext,
        ).getService(ContactService.ID)

        val imported = service.importContact(card)

        assertIs<TResult.Success<*>>(imported, "the import: $imported")
        val row = sources(harness.proxy.executionContext).single()
        assertTrue(row.rawVcard.get().length < 16 * 1024)
        assertEquals(FullCardRead.Ok(card), service.fullVcard(row.sourceKey.get(), row.rawVcard.get()))
    }

    @Test
    fun theSameCardInOneRowIsRefusedByTheStore() {
        // The v1 row, written as v1 wrote it — the case F6s names. The control that makes the test
        // above mean something: without the move this store refuses the card.
        val store = sqliteStore()
        val harness = createContactProxy(FakeInviteRelay(), identityPhrase = "made-up phrase", objectStore = store, lock = store.databaseLock)
        val ctx = harness.proxy.executionContext
        assertFailsWith<RowTooLarge> {
            ctx.saveData(ContactSource.create(ctx.context, sourceKey = "vcard:legacy", personId = "p-1", rawVcard = card))
        }
    }

    private fun sources(ctx: org.tenshu.core.common.proxy.ExecutionContext): List<ContactSource> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(context = ctx.context, type = TList.ID, relationId = ContactService.Relations.SOURCES),
            isEntity = false,
            elementSchemaId = ContactSource.ID,
        )
        return ctx.database.getArrayData(ref)?.mapNotNull { it as? ContactSource }?.toList().orEmpty()
    }
}
