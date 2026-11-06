package org.tenshu.contact.proxy

import kotlinx.coroutines.CompletableDeferred
import org.tenshu.core.collection.list.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.ContentId
import org.tenshu.contact.Photos
import org.tenshu.contact.client.FullCardRead
import org.tenshu.contact.client.fullVcard
import org.tenshu.contact.common.mergeIntoVCard
import org.tenshu.contact.common.readContactCard
import org.tenshu.contact.createPerson
import org.tenshu.contact.deletePerson
import org.tenshu.contact.importContact
import org.tenshu.contact.mergePersons
import org.tenshu.contact.saveContactSource
import org.tenshu.contact.test.ContactPhotoFixtures
import org.tenshu.contact.test.FakeInviteRelay
import org.tenshu.contact.test.createContactProxy
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.TByteArray
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The photo writer under cancellation and concurrency, the edit that keeps
 * its photos, the sweep, and the re-fold margin (`contacts-app.md` §9.10). Every name is made up.
 */
@OptIn(ExperimentalEncodingApi::class)
class ContactPhotoConcurrencyTest {

    /** The entity block drain, optionally held at [awaitEmitIdle] until [release] completes. */
    private class Blocks(private val hold: Boolean = false) : SyncBlockEmitter {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override fun emitPropertyBlock(targetEntityId: DataId, propertyId: String, value: Data) = Unit
        override suspend fun awaitEmitIdle() {
            if (!hold) return
            entered.complete(Unit)
            release.await()
        }
    }

    /** Records the journal and the uploads; holds the FIRST upload until [release] when asked. */
    private class Content(private val holdFirstUpload: Boolean = false) : FileContentSyncEmitter {
        val journaled = mutableListOf<String>()
        val uploaded = mutableListOf<String>()
        /** Paths the journal still owes (an offline device: journalled, not yet drained). */
        val queued = mutableSetOf<String>()
        /** Runs once, inside the first `hasQueuedContentAt` — between the sweep's snapshot and its transaction. */
        var onFirstQueuedQuery: (suspend () -> Unit)? = null
        override suspend fun hasQueuedContentAt(filePath: String): Boolean {
            onFirstQueuedQuery?.let { hook -> onFirstQueuedQuery = null; hook() }
            return filePath in queued
        }
        val firstUploadEntered = CompletableDeferred<DataId>()
        val release = CompletableDeferred<Unit>()
        override suspend fun journalFileContent(targetEntityId: DataId, propertyId: String, filePath: String) {
            journaled += filePath
        }
        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String): Boolean {
            val first = uploaded.isEmpty()
            uploaded += filePath
            if (holdFirstUpload && first) {
                firstUploadEntered.complete(targetEntityId)
                release.await()
            }
            return true
        }
        override suspend fun shareContentToRealm(contentId: String, targetRealmOwnerId: String, targetPropertyId: String) = false
    }

    private fun harnessWith(
        blocks: Blocks = Blocks(),
        content: Content = Content(),
        fileDatabase: org.tenshu.core.common.proxy.FileDatabase? = null,
    ) = createContactProxy(
        FakeInviteRelay(),
        identityPhrase = "made-up phrase",
        fileDatabase = fileDatabase,
        syncBlockEmitterHolder = SyncBlockEmitterHolder().also {
            it.emitter = blocks
            it.fileContentEmitter = content
        },
    )

    private fun serviceOf(harness: org.tenshu.contact.test.ContactTestHarness): ContactService {
        val executor = LocalTaskExecutor(harness.proxy.executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = harness.proxy.schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = harness.proxy.tContext,
        ).getService(ContactService.ID)
    }

    private fun <T : Data> TResult<out T?>.expect(what: String): T {
        if (this is TResult.Failure<*>) error("$what failed: $error")
        return checkNotNull((this as TResult.Success<out T?>).value) { "$what returned null" }
    }

    private fun stored(harness: org.tenshu.contact.test.ContactTestHarness, sourceKey: String): Entity<*>? =
        harness.proxy.executionContext.database.getData(
            contactSourceDataId(harness.proxy.executionContext, sourceKey),
            asEntity = true,
        ) as? Entity<*>

    private fun contentIdOf(harness: org.tenshu.contact.test.ContactTestHarness, sourceKey: String): String? =
        stored(harness, sourceKey)?.get(ContactSource.ContentId)?.get()?.takeIf { it.isNotBlank() }

    private fun cardOf(harness: org.tenshu.contact.test.ContactTestHarness, sourceKey: String): String? =
        storedCardOf(stored(harness, sourceKey))

    private fun bigCard(seed: Int = 7, name: String = "Ilse Varga") =
        ContactPhotoFixtures.card(ContactPhotoFixtures.photoLine(ContactPhotoFixtures.image(600 * 1024, seed)), name = name)

    // ── the row and its upload intent are one span ──

    @Test
    fun `a cancel in the entity-before-bytes wait still leaves the upload journalled`() = runTest {
        val blocks = Blocks(hold = true)
        val content = Content()
        val harness = harnessWith(blocks, content)
        val ctx = harness.proxy.executionContext

        val job = launch { writeContactSource(ctx, harness.components, "vcard:k1", "p-1", bigCard()) }
        blocks.entered.await()
        job.cancel()
        blocks.release.complete(Unit)
        job.join()

        val contentId = assertNotNull(contentIdOf(harness, "vcard:k1"), "the row was saved")
        assertEquals(listOf(contactPhotosPath(contentId)), content.journaled, "the durable intent — the drain uploads it")
        assertNotNull(harness.components.fileDatabase.readBytes(contactPhotosPath(contentId)))
    }

    // ── an edit on the device that holds the photos ──

    @Test
    fun `an edit where the photos are local keeps the content id and uploads nothing new`() = runTest {
        val content = Content()
        val harness = harnessWith(content = content)
        val service = serviceOf(harness)
        val card = bigCard()
        val personId = service.importContact(card).expect("importContact").get()
        val sourceKey = harness.proxy.executionContext.database
            .let { org.tenshu.contact.proxy.impl.sourceRows(harness.proxy.executionContext).single().sourceKey.get() }
        val contentId = contentIdOf(harness, sourceKey)!!
        assertEquals(1, content.uploaded.size)

        val storedCard = cardOf(harness, sourceKey)!!
        val edit = readContactCard(storedCard).copy(note = "Same photo, new note")
        service.saveContactSource(sourceKey, personId, mergeIntoVCard(storedCard, edit)).expect("saveContactSource")

        assertEquals(contentId, contentIdOf(harness, sourceKey), "same photos, same content id")
        assertEquals(1, content.uploaded.size, "no second upload")
        val full = assertIs<FullCardRead.Ok>(service.fullVcard(sourceKey, cardOf(harness, sourceKey)!!))
        assertEquals(mergeIntoVCard(card, edit), full.card, "byte-exact: the edit of the full card")
    }

    // ── superseded blobs go at the sweep, and only when nothing names them ──

    @Test
    fun `a superseded blob a peer names again survives the sweep`() = runTest {
        val harness = harnessWith()
        val service = serviceOf(harness)
        val ctx = harness.proxy.executionContext
        val files = harness.components.fileDatabase
        val c1Card = bigCard(seed = 1)
        val personId = service.importContact(c1Card).expect("importContact").get()
        val sourceKey = org.tenshu.contact.proxy.impl.sourceRows(ctx).single().sourceKey.get()
        val c1 = contentIdOf(harness, sourceKey)!!
        val c1Stored = cardOf(harness, sourceKey)!!

        // This device moves the card to new photos (C2)…
        service.saveContactSource(sourceKey, personId, bigCard(seed = 2)).expect("saveContactSource")
        val c2 = contentIdOf(harness, sourceKey)!!
        assertTrue(c1 != c2)
        assertNotNull(files.readBytes(contactPhotosPath(c1)), "C1 is a candidate, not deleted at the save")
        // …and an offline peer's edit of the C1 card wins last-writer: the row names C1 again.
        saveContactSourceRow(ctx, sourceKey, personId, c1Stored, c1, WriteOrigin.SYNC_INGEST)

        sweepSupersededContactPhotos(ctx, harness.components)

        assertNotNull(files.readBytes(contactPhotosPath(c1)), "named again: kept")
        assertNull(files.readBytes(contactPhotosPath(c2)), "named by nothing: swept")
        assertEquals(FullCardRead.Ok(c1Card), service.fullVcard(sourceKey, c1Stored))
    }

    // ── the re-fold of an offline edit does not trip the budget ──

    @Test
    fun `an offline edit of a card just under the budget is not refused for the re-fold`() = runTest {
        val harness = harnessWith()
        val service = serviceOf(harness)
        val ctx = harness.proxy.executionContext
        // One big photo that moves, and five unfolded medium ones (~50 KiB of line each) that stay:
        // the stored card sits just under 256 KiB, and the merge's 75-octet re-fold pushes it over.
        val mediums = (1..5).map { "PHOTO;ENCODING=b;TYPE=JPEG:" + Base64.encode(ContactPhotoFixtures.image(38_000, seed = it)) }
        val card = ContactPhotoFixtures.card(ContactPhotoFixtures.photoLine(ContactPhotoFixtures.image(600 * 1024)), *mediums.toTypedArray())
        val personId = service.importContact(card).expect("importContact").get()
        val sourceKey = org.tenshu.contact.proxy.impl.sourceRows(ctx).single().sourceKey.get()
        val storedCard = cardOf(harness, sourceKey)!!
        assertTrue(storedCard.encodeToByteArray().size < 256 * 1024, "stored: ${storedCard.length}")
        harness.components.fileDatabase.deleteFile(contactPhotosPath(contentIdOf(harness, sourceKey)!!))

        val edited = mergeIntoVCard(storedCard, readContactCard(storedCard).copy(note = "Offline edit"))
        assertTrue(edited.encodeToByteArray().size > 256 * 1024, "the re-fold pushed it over: ${edited.length}")
        service.saveContactSource(sourceKey, personId, edited).expect("saveContactSource")

        assertEquals("Offline edit", readContactCard(cardOf(harness, sourceKey)!!).note)
    }

    // ── the journal guard, and the candidate recorded between the sweep's two reads ──

    private suspend fun importOne(harness: org.tenshu.contact.test.ContactTestHarness, service: ContactService, seed: Int): Pair<String, String> {
        val before = org.tenshu.contact.proxy.impl.sourceRows(harness.proxy.executionContext).map { it.sourceKey.get() }.toSet()
        service.importContact(bigCard(seed = seed, name = "Person $seed")).expect("importContact")
        val key = org.tenshu.contact.proxy.impl.sourceRows(harness.proxy.executionContext).map { it.sourceKey.get() }.single { it !in before }
        return key to contentIdOf(harness, key)!!
    }

    private fun peerTombstone(harness: org.tenshu.contact.test.ContactTestHarness, sourceKey: String) =
        harness.proxy.executionContext.deleteData(contactSourceDataId(harness.proxy.executionContext, sourceKey), WriteOrigin.SYNC_INGEST)

    @Test
    fun `a candidate whose upload is still journalled survives the sweep`() = runTest {
        val content = Content()
        val harness = harnessWith(content = content)
        val service = serviceOf(harness)
        val (key, c1) = importOne(harness, service, seed = 1)
        content.queued += contactPhotosPath(c1) // offline: journalled, never drained
        // A fresh blob is a candidate until a sweep sees a row naming it: start from a clean list.
        sweepSupersededContactPhotos(harness.proxy.executionContext, harness.components)
        peerTombstone(harness, key)

        sweepSupersededContactPhotos(harness.proxy.executionContext, harness.components)

        assertNotNull(harness.components.fileDatabase.readBytes(contactPhotosPath(c1)), "owed to the relay: kept")
    }

    @Test
    fun `a candidate recorded between the sweep's snapshot and its delete waits for the next run`() = runTest {
        val content = Content()
        val harness = harnessWith(content = content)
        val service = serviceOf(harness)
        val (keyA, _) = importOne(harness, service, seed = 1)
        val (keyB, cB) = importOne(harness, service, seed = 2)
        content.queued += contactPhotosPath(cB) // B's upload is journalled, not yet drained
        // A fresh blob is a candidate until a sweep sees a row naming it: start from a clean list.
        sweepSupersededContactPhotos(harness.proxy.executionContext, harness.components)
        peerTombstone(harness, keyA) // A's blob: the one candidate in the snapshot
        // B's delete lands while the sweep asks the journal about A — after the snapshot, before the
        // transaction, as a delete or a peer's tombstone can (neither takes the source write lock).
        content.onFirstQueuedQuery = { peerTombstone(harness, keyB) }

        sweepSupersededContactPhotos(harness.proxy.executionContext, harness.components)

        assertNotNull(harness.components.fileDatabase.readBytes(contactPhotosPath(cB)), "never asked about: not deleted this run")
        sweepSupersededContactPhotos(harness.proxy.executionContext, harness.components)
        assertNotNull(harness.components.fileDatabase.readBytes(contactPhotosPath(cB)), "the next run asks, and it is owed")
    }

    // ── a photo just under the threshold, inline in the stored row, re-folded by an edit ──

    @Test
    fun `an offline edit keeps an inline photo the re-fold pushes over the threshold`() = runTest {
        val harness = harnessWith()
        val service = serviceOf(harness)
        // 48,000 bytes of image is a 64,027-byte line unfolded — inline — and 66,622 once re-folded.
        val inline = "PHOTO;ENCODING=b;TYPE=JPEG:" + Base64.encode(ContactPhotoFixtures.image(48_000, seed = 3))
        assertEquals(64_027, inline.encodeToByteArray().size)
        val card = ContactPhotoFixtures.card(ContactPhotoFixtures.photoLine(ContactPhotoFixtures.image(600 * 1024)), inline)
        val personId = service.importContact(card).expect("importContact").get()
        val sourceKey = org.tenshu.contact.proxy.impl.sourceRows(harness.proxy.executionContext).single().sourceKey.get()
        val storedCard = cardOf(harness, sourceKey)!!
        assertTrue(inline in storedCard, "the medium photo stayed inline")
        harness.components.fileDatabase.deleteFile(contactPhotosPath(contentIdOf(harness, sourceKey)!!))

        val edited = mergeIntoVCard(storedCard, readContactCard(storedCard).copy(note = "Offline edit"))
        service.saveContactSource(sourceKey, personId, edited).expect("saveContactSource")

        assertEquals("Offline edit", readContactCard(cardOf(harness, sourceKey)!!).note)
    }

    // ── a blob written for a save that does not land is a candidate ──

    /** An in-memory byte store that runs [hook] once, after the first write of a contact photo blob. */
    private class HookedFiles : FileDatabase {
        val files = mutableMapOf<String, TByteArray>()
        var hook: (() -> Unit)? = null
        override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
            files[filePath] = org.tenshu.core.common.impl.TByteArrayDto(bytes)
        }
        override fun writeByteArray(filePath: String, bytes: TByteArray) {
            files[filePath] = bytes
            if (filePath.startsWith("/.contacts/photos/") && !filePath.endsWith(".superseded")) {
                hook?.let { h -> hook = null; h() }
            }
        }
        override fun readBytes(filePath: String): TByteArray? = files[filePath]
        override fun deleteFile(filePath: String) { files.remove(filePath) }
    }

    @Test
    fun `a blob written for a plan that never commits is swept`() = runTest {
        val files = HookedFiles()
        val harness = harnessWith(fileDatabase = files)
        val service = serviceOf(harness)
        val ctx = harness.proxy.executionContext
        val (key, c1) = importOne(harness, service, seed = 1)
        val personId = storedPersonIdOf(stored(harness, key))!!
        val c1Card = cardOf(harness, key)!!
        // A peer's row lands between this save's plan (which wrote a fresh blob) and its commit.
        val peerCard = mergeIntoVCard(c1Card, readContactCard(c1Card).copy(note = "From a peer"))
        files.hook = { saveContactSourceRow(ctx, key, personId, peerCard, c1, WriteOrigin.SYNC_INGEST) }

        service.saveContactSource(key, personId, bigCard(seed = 2)).expect("saveContactSource")

        val landed = contentIdOf(harness, key)!!
        val written = files.files.keys.filter { it.startsWith("/.contacts/photos/") && !it.endsWith(".superseded") }
        assertEquals(3, written.size, "C1, the abandoned plan's blob, and the committed one: $written")
        sweepSupersededContactPhotos(ctx, harness.components)
        val left = files.files.keys.filter { it.startsWith("/.contacts/photos/") && !it.endsWith(".superseded") }
        assertEquals(listOf(contactPhotosPath(landed)), left, "only the photo the row names is left")
    }

    // ── one lock order, and the committed content id carried to the journal and the emit ──

    /**
     * Per-content-key ownership the way `SyncObserver` models it: `withContentEmitOwnership` claims
     * the key of the content the row names NOW; the unbound journal and emit re-read the id off the
     * row and claim its key; the bound ones claim the key of the id they are given; a key already
     * owned by this coroutine is re-entered, any other key waits.
     */
    private class KeyedContent : FileContentSyncEmitter {
        /** The content id the row names now — `resolveContentId`'s answer. */
        var currentId: () -> String? = { null }
        val journaled = mutableListOf<String?>()
        val journaledHashes = mutableListOf<String?>()
        val uploaded = mutableListOf<String?>()
        private val locks = mutableMapOf<String, kotlinx.coroutines.sync.Mutex>()

        private class Owned(val ownedKey: String) : kotlin.coroutines.AbstractCoroutineContextElement(Owned) {
            companion object : kotlin.coroutines.CoroutineContext.Key<Owned>
        }

        private suspend fun <T> withKey(contentId: String?, block: suspend () -> T): T {
            val key = contentId ?: "holder"
            if (kotlinx.coroutines.currentCoroutineContext()[Owned]?.ownedKey == key) return block()
            val lock = locks.getOrPut(key) { kotlinx.coroutines.sync.Mutex() }
            return lock.withLock { kotlinx.coroutines.withContext(Owned(key)) { block() } }
        }

        override suspend fun <T> withContentEmitOwnership(targetEntityId: DataId, propertyId: String, block: suspend () -> T): T =
            withKey(currentId()) { block() }

        override suspend fun journalFileContent(targetEntityId: DataId, propertyId: String, filePath: String) {
            val id = currentId()
            withKey(id) { journaled += id }
        }

        override suspend fun journalFileContent(targetEntityId: DataId, propertyId: String, filePath: String, contentId: String, contentHash: String?) =
            withKey(contentId) {
                journaled += contentId
                journaledHashes += contentHash
            }

        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String): Boolean {
            val id = currentId()
            return withKey(id) { uploaded += id; true }
        }

        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String, contentId: String, contentHash: String?): Boolean =
            withKey(contentId) { uploaded += contentId; true }

        override suspend fun shareContentToRealm(contentId: String, targetRealmOwnerId: String, targetPropertyId: String) = false
    }

    @Test
    fun `two overlapping photo saves of one source both complete`() = runTest {
        val blocks = Blocks(hold = true)
        val content = KeyedContent()
        val harness = harnessWith(blocks = blocks, content = Content())
        harness.components.syncBlockEmitterHolder!!.fileContentEmitter = content
        val key = "vcard:overlap"
        content.currentId = { contentIdOf(harness, key) }
        val ctx = harness.proxy.executionContext

        // A commits its photos and parks in the entity-before-bytes wait, holding the source lock…
        // In `backgroundScope`: under a cycle these never finish, and the test must still end.
        val a = backgroundScope.launch { writeContactSource(ctx, harness.components, key, "p-1", bigCard(seed = 2)) }
        blocks.entered.await()
        // …B, of the same source, starts while A is parked (the row already names A's content)…
        val b = backgroundScope.launch { writeContactSource(ctx, harness.components, key, "p-1", bigCard(seed = 3)) }
        testScheduler.advanceUntilIdle()
        blocks.release.complete(Unit)

        // …and both finish. ⚠ Bounded, so a lock-order cycle fails here instead of stalling the suite.
        kotlinx.coroutines.withTimeout(10_000) { a.join(); b.join() }
        assertEquals(2, content.journaled.size, "both saves journalled: ${content.journaled}")
        assertEquals(contentIdOf(harness, key), content.journaled.last())
    }

    @Test
    fun `a peer row ingested during the wait does not take over this save's upload`() = runTest {
        val blocks = Blocks(hold = true)
        val content = KeyedContent()
        val harness = harnessWith(blocks = blocks, content = Content())
        harness.components.syncBlockEmitterHolder!!.fileContentEmitter = content
        val service = serviceOf(harness)
        val ctx = harness.proxy.executionContext
        val key = "vcard:bound"
        content.currentId = { contentIdOf(harness, key) }
        val card = bigCard(seed = 4)

        val save = launch { writeContactSource(ctx, harness.components, key, "p-1", card) }
        blocks.entered.await()
        val c1 = contentIdOf(harness, key)!!
        val c1Stored = cardOf(harness, key)!!
        // A peer's row naming other content lands while this save waits to journal.
        saveContactSourceRow(ctx, key, "p-1", c1Stored, "c2-from-a-peer", WriteOrigin.SYNC_INGEST)
        blocks.release.complete(Unit)
        save.join()

        assertEquals(listOf<String?>(c1), content.journaled, "journalled under the content it committed")
        assertEquals(listOf<String?>(c1), content.uploaded, "published under it too")
        // Where the row names C1 again (the peer's write lost after all), the export is byte-exact.
        saveContactSourceRow(ctx, key, "p-1", c1Stored, c1, WriteOrigin.SYNC_INGEST)
        assertEquals(FullCardRead.Ok(card), service.fullVcard(key, c1Stored))
    }

    // ── the intent carries the blob's hash, so the drain publishes it by its bytes ──

    @Test
    fun `a photo save journals the whole-file hash of the blob it wrote`() = runTest {
        val content = KeyedContent()
        val harness = harnessWith(content = Content())
        harness.components.syncBlockEmitterHolder!!.fileContentEmitter = content
        val key = "vcard:hashed"
        content.currentId = { contentIdOf(harness, key) }

        writeContactSource(harness.proxy.executionContext, harness.components, key, "p-1", bigCard(seed = 5))

        val blob = harness.components.fileDatabase.readBytes(contactPhotosPath(contentIdOf(harness, key)!!))!!.get()
        // Without it the drain retires a journalled-only photo as soon as a peer's row names other
        // content (`BoundContentEmitTest.aJournalledOnlyPhotoIsPublishedByItsBytesAfterTheRowMovesOn`).
        assertEquals(listOf<String?>(org.tenshu.core.common.util.wholeFileHashOf(blob)), content.journaledHashes)
    }

    // ── an import commits its person with its record, or neither ──

    @Test
    fun `an import cancelled at the source lock leaves no name-only person`() = runTest {
        val harness = harnessWith()
        val ctx = harness.proxy.executionContext
        // Another photo save holds the lock (it waits for its row's block with it held).
        harness.components.sourceWriteLock.lock()
        val import = launch {
            org.tenshu.contact.proxy.impl.ImportContactImplementation(harness.components).execute<Data?>(
                ctx,
                null,
                org.tenshu.core.common.proxy.executor.OperationImplementationFactory { null },
                null,
                mapOf(org.tenshu.contact.ImportContact.Params.RAW_VCARD to org.tenshu.core.common.impl.TStringDto(bigCard(name = "Wren Halloway"))),
            )
        }
        testScheduler.advanceUntilIdle()
        import.cancel() // a shutdown while it waits
        import.join()
        harness.components.sourceWriteLock.unlock()

        val persons = ctx.database.getArrayData(
            org.tenshu.core.common.proxy.ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = ctx.context,
                    type = org.tenshu.core.collection.list.TList.ID,
                    relationId = ContactService.Relations.PERSONS,
                ),
                isEntity = false,
                elementSchemaId = org.tenshu.contact.Person.ID,
            )
        )?.toList().orEmpty()
        assertTrue(persons.isEmpty(), "no person without its record: $persons")
        assertTrue(org.tenshu.contact.proxy.impl.sourceRows(ctx).isEmpty())
    }

    // ── the avatar commits with the person and the record ──

    private fun importJob(scope: kotlinx.coroutines.CoroutineScope, harness: org.tenshu.contact.test.ContactTestHarness, card: String) =
        scope.launch {
            org.tenshu.contact.proxy.impl.ImportContactImplementation(harness.components).execute<Data?>(
                harness.proxy.executionContext,
                null,
                org.tenshu.core.common.proxy.executor.OperationImplementationFactory { null },
                null,
                mapOf(org.tenshu.contact.ImportContact.Params.RAW_VCARD to org.tenshu.core.common.impl.TStringDto(card)),
            )
        }

    private fun rowsOf(harness: org.tenshu.contact.test.ContactTestHarness, relation: String, type: String) =
        harness.proxy.executionContext.database.getArrayData(
            org.tenshu.core.common.proxy.ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = harness.proxy.tContext,
                    type = org.tenshu.core.collection.list.TList.ID,
                    relationId = relation,
                ),
                isEntity = false,
                elementSchemaId = type,
            )
        )?.toList().orEmpty()

    /** Over the relocation threshold, under the avatar ceiling: moves AND is promoted. */
    private val portraitCard = ContactPhotoFixtures.card(
        ContactPhotoFixtures.photoLine(ContactPhotoFixtures.image(100 * 1024, seed = 6)),
        name = "Sabine Okafor",
    )

    @Test
    fun `an import cancelled during its upload leaves a whole contact with its avatar`() = runTest {
        val content = Content(holdFirstUpload = true)
        val harness = harnessWith(content = content)
        val import = importJob(this, harness, portraitCard)
        content.firstUploadEntered.await()
        import.cancel() // a shutdown mid-upload
        import.join()

        assertEquals(1, rowsOf(harness, ContactService.Relations.PERSONS, org.tenshu.contact.Person.ID).size, "the person")
        assertEquals(1, org.tenshu.contact.proxy.impl.sourceRows(harness.proxy.executionContext).size, "its record")
        assertEquals(1, rowsOf(harness, ContactService.Relations.AVATARS, org.tenshu.contact.PersonAvatar.ID).size, "and its avatar")
        assertEquals(1, content.journaled.size, "the upload is journalled: the drain finishes it after a restart")
    }

    @Test
    fun `a contact deleted during its import's upload leaves no avatar behind`() = runTest {
        val content = Content(holdFirstUpload = true)
        val harness = harnessWith(content = content)
        val service = serviceOf(harness)
        val import = importJob(this, harness, portraitCard)
        content.firstUploadEntered.await()
        val person = rowsOf(harness, ContactService.Relations.PERSONS, org.tenshu.contact.Person.ID)
            .single() as org.tenshu.contact.Person
        service.deletePerson(person.personId.get()).expect("deletePerson")

        content.release.complete(Unit)
        import.join()

        assertTrue(rowsOf(harness, ContactService.Relations.AVATARS, org.tenshu.contact.PersonAvatar.ID).isEmpty(), "no orphan avatar")
        assertTrue(rowsOf(harness, ContactService.Relations.PERSONS, org.tenshu.contact.Person.ID).isEmpty())
    }
}
