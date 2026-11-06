@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.PendingContentEmit
import org.tenshu.sync.proxy.SyncObserver
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins, end to end on the real runtime, that a refused `fetch` during the content emit leaves the
 * upload queued and the op successful.
 *
 * The content emit journals the upload before it sends a byte (`SyncObserver.emitFileContentOwned`,
 * "JOURNAL FIRST, ALWAYS"), so a refused chunk `PUT` is the drain's to retry and the op succeeds.
 * ⚠ On web ktor-client-js reports a refused `fetch` as `kotlin.Error("Fail to fetch")`, which is no
 * `Exception`: unmapped, it goes past `RestContentBlobStore.putChunk`'s catch and fails an upload
 * that is already saved and queued. The relay here is [TEST_RELAY_SINK_URL], so every `fetch` really
 * is refused by the browser. The mapping is the `TransportFailureMapping` HTTP plugin;
 * `TransportFailureMappingTest` pins the plugin itself.
 */
class ContentEmitFetchFailureTest : SdkRuntimeTest() {

    @Test
    fun anOfflineUploadWithARealm_succeeds_withItsContentQueued_andTheDrainRetriesIt() = GlobalScope.promise {
        JsRuntime.awaitReady()
        withRealm {
            val before = pendingContentEmits()
            val name = "ContentEmitFetchFailureTest-${Date.now().toLong()}.bin"

            val uploaded = FileSystem.uploadFile("/", name, ByteArray(2048) { 3 }).await()

            assertTrue(uploaded, "the file is saved and its upload queued: the op must answer success")
            val queued = pendingContentEmits() - before
            assertEquals(1, queued.size, "exactly one upload journalled for the drain")

            // The next sync tick's content drain: it tries the queued upload again (a resolved
            // entry is reported as progress), fails the same way, and keeps it for the tick after.
            val observer = assertNotNull(
                JsRuntime.syncBlockEmitterHolder.fileContentEmitter as? SyncObserver,
                "precondition: the realm wired the content emitter",
            )
            var attempted = 0
            val remaining = observer.drainDeferredContent(force = true) { completed, _ -> attempted = completed }
            assertTrue(attempted >= 1, "the drain attempted the queued upload")
            assertTrue(remaining >= 1, "a refused upload stays queued")
            assertTrue(queued.all { it in pendingContentEmits() }, "still pending after the failed retry")
        }
    }

    /** The emit keys in the content journal, the drain's work list. */
    private fun pendingContentEmits(): Set<String> {
        val ec = JsRuntime.executionContext
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = ec.context,
                type = PendingContentEmit.ID,
                relationId = SyncService.Collections.PENDING_CONTENT_EMITS,
            ),
            isEntity = false,
            elementSchemaId = PendingContentEmit.ID,
        )
        return ec.database.getArrayData(ref)?.filterIsInstance<PendingContentEmit>()
            ?.map { it.emitKey.get() }?.toSet().orEmpty()
    }

    /**
     * A realm, as a signed-in browser has one; `Realm.create` answers `ok: false` against the sink
     * but has enabled sync locally first. Locked again afterwards so the shared runtime's other
     * suites keep their realm-less state (`FitWebContractTest.withRealm`).
     */
    private suspend fun withRealm(body: suspend () -> Unit) {
        val created = !JsRuntime.syncManager.isEnabled()
        if (created) Realm.create(Bip39.generateMnemonic(), "fetch-failure-test-device").await()
        try {
            assertTrue(JsRuntime.syncManager.isEnabled(), "precondition: the runtime holds a realm")
            body()
        } finally {
            if (created) JsRuntime.syncManager.lock()
        }
    }
}
