package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.number.TInteger
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.Size
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.FolderMerger
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression: **ingesting a block must not blank the value properties of an entity
 * embedded in one of its list fields.**
 *
 * `Folder.FILES` is `list(of = File.Schema)`, and a `SyncBlock` payload deserialises to
 * plain composites — not to `Entity` with its `properties`. `DbEntryMapper.writeValuesToDb`
 * serialises every list member into its *own* row, so ingesting a folder block used to
 * rewrite each file's row with `properties = emptyMap()`, wiping `File.ContentId`,
 * `contentHash`, `size`, the timestamps and the synced thumbnails that device held.
 *
 * The recovery was incidental — a *later* write happening to carry the full entity — so
 * where none followed the loss was permanent on that device. The visible symptom was
 * `ShareFolderIntoRealm` refusing with "no shareable content yet" immediately after an
 * upload that had reported success (`SharedRealmFileReadSystemTest`, ~40 % of runs).
 *
 * Deterministic by construction: the ingest is driven directly, so this needs no timing,
 * no relay and no second device. See docs/tech-debt.md § "Sync ingest wipes value
 * properties off entities embedded in a list field".
 */
class IngestPreservesEmbeddedEntityPropertiesTest {

    @Test
    fun ingestingAFolderBlockKeepsItsFilesValueProperties() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(fileServiceCommonModule)

        val context = proxy.tContext
        val crypto = context.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            context = context,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        syncManager.initialize("mock phrase")
        val keys = assertNotNull(syncManager.getDerivedKeys(), "derived keys after initialize")

        val mergerRegistry = SemanticMergerRegistry().apply { register(FolderMerger()) }
        val processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            mergerRegistry = mergerRegistry,
        )

        // The device's local state: a folder holding one file, and that file's row
        // carrying value properties — the shape `UploadFileImplementation` leaves behind
        // (it mints `contentId` and sets it in the same save that creates the file).
        val filePath = "/shared/doc.bin"
        proxy.executionContext.saveData(Folder.create(context, "/shared", files = listOf(File.create(context, filePath))))
        val fileEntity = Entity.create<File>(
            context = context,
            composite = File.create(context, filePath),
        ) {
            set(File.ContentId, "content-id-1")
            set(File.Size, 5L)
        }
        proxy.executionContext.saveData(fileEntity)
        val fileDataId = assertNotNull(proxy.executionContext.getDataId(fileEntity))

        fun storedContentId(): String? = proxy.executionContext.database
            .getData(fileDataId, asEntity = true)
            ?.let { proxy.executionContext.resolveProperty(it, propertyRef(File.ContentId.id)) as? TString }
            ?.get()

        fun storedSize(): Long? = proxy.executionContext.database
            .getData(fileDataId, asEntity = true)
            ?.let { proxy.executionContext.resolveProperty(it, propertyRef(File.Size.id)) as? TInteger }
            ?.get()

        assertEquals("content-id-1", storedContentId(), "precondition: the property is stored")

        // A peer's folder block for the same folder, newer, embedding the file as the
        // wire always does: a bare composite with no properties of its own.
        val payload = DataBlockPayload.create(
            context = context,
            propertyId = null,
            value = Folder.create(context, "/shared", files = listOf(File.create(context, filePath))),
        )
        // Envelope bound as AAD (sync-envelope-authentication §3.2) — an unbound block would be
        // dropped unopened and make this test vacuous.
        val block = org.tenshu.sync.common.EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = context,
            blockId = "folder-block",
            ownerId = "user-1",
            hlc = HybridLogicalClock.create(context, 2_000L, 0, "device-remote"),
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(
                BinarySerializer().serialize(payload),
                proxy.versionStampBytes(),
            ),
            identityKey = keys.sharedIdentityKey,
        )

        processor.processSyncBlock(block)

        // The block said nothing about the file's value properties, so it must not have
        // taken them away. Before the fix both reads came back null.
        assertEquals(
            "content-id-1",
            storedContentId(),
            "ingesting a folder block must not wipe its files' contentId",
        )
        assertEquals(
            5L,
            storedSize(),
            "…nor any other value property on the embedded entity",
        )
    }
}
