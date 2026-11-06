package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.file.File
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.sync.common.LocalWriteMetadata
import org.tenshu.sync.common.PersistedTombstone
import org.tenshu.sync.common.ResolvedDataId
import org.tenshu.sync.common.SyncMetadata
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test

/**
 * A diagnostic, shaped as a test so it needs no build wiring: prints the **wire id** of a file
 * path in the personal realm, and the store hashes of the sync sidecar rows keyed by it.
 *
 * Why it exists (`docs/proposals/drive-folder-files-dangling-member.md` §7): a file row's
 * `SyncMetadata`, `ResolvedDataId` and `LocalWriteMetadata` are keyed by the entity's **wire id**
 * — `HMAC(K_entity, "tenshu/file_<path>")`, `K_entity` derived from the realm's volume key — so
 * they cannot be found by walking a store copy for the file's local hash. With the wire id in
 * hand they are point lookups (`scripts/store-relation-audit.py --has …`), and the relay's
 * `sync_block` / `delete_block` rows for the entity are addressable by `target_entity_id_hash`.
 * That is what decides whether a lost row's whole write-set vanished with it, and whether a
 * neighbouring row was written again later.
 *
 * ⚠ The personal realm's keys derive from the recovery phrase and nothing else, so this runs
 * offline and opens no store. The phrase is read from a **file** named by
 * `TENSHU_WIRE_ID_PHRASE_FILE` (never from the command line — shell history) and is never
 * printed; delete the file afterwards.
 *
 * ```
 * printf '%s' '<recovery phrase>' > /tmp/phrase && chmod 600 /tmp/phrase
 * TENSHU_WIRE_ID_PHRASE_FILE=/tmp/phrase \
 * TENSHU_WIRE_ID_PATHS='/Backup/OnePlus CPH2581/WhatsApp Images/IMG-20240410-WA0004.jpg' \
 *   ./gradlew --no-daemon :sync-service-proxy:jvmTest --tests '*WireIdsForPathsTool*' -i \
 *   | grep '^WIRE '
 * rm /tmp/phrase
 * ```
 *
 * ⚠ `--no-daemon`: a test JVM inherits the *daemon's* environment, and a daemon already
 * running started without these variables. ⚠ Wire ids are not secrets — they are what the relay
 * stores in cleartext — so the output may be pasted into a PR; the phrase may not.
 *
 * Without the variables it passes and prints nothing, so it costs the suite nothing.
 */
class WireIdsForPathsTool {

    @Test
    fun printWireIdsForPaths() {
        val phraseFile = System.getenv("TENSHU_WIRE_ID_PHRASE_FILE") ?: return
        val paths = System.getenv("TENSHU_WIRE_ID_PATHS")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: return
        val phrase = java.io.File(phraseFile).readText().trim()

        val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry()).apply {
            schemaRegistry.registerConfig(syncServiceCommonModule)
            schemaRegistry.registerConfig(fileServiceCommonModule)
        }
        val tContext = testProxy.tContext
        val syncManager = SyncManagerImpl(
            context = tContext,
            cryptoManager = tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
        ).apply { initialize(phrase) }
        val resolver = WireIdResolver(syncManager)

        fun sidecar(typeId: String, wireId: String): Long =
            DataId.StringDataId.create(context = tContext, type = typeId, keyValue = wireId).getDataIdHash()

        for (path in paths) {
            val fileId = DataId.StringDataId.create(context = tContext, type = File.ID, keyValue = path)
            val wireId = resolver.of(fileId)
            println("WIRE path=$path")
            println("WIRE   fileRowHash=${fileId.getDataIdHash()}")
            println("WIRE   wireId=$wireId")
            println("WIRE   syncMetadataRow=${sidecar(SyncMetadata.ID, wireId)}")
            println("WIRE   resolvedDataIdRow=${sidecar(ResolvedDataId.ID, wireId)}")
            println("WIRE   localWriteMetadataRow=${sidecar(LocalWriteMetadata.ID, wireId)}")
            println("WIRE   persistedTombstoneRow=${sidecar(PersistedTombstone.ID, wireId)}")
        }
    }
}
