@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.promise
import org.tenshu.core.TResult
import org.tenshu.sync.client.contentTransfer
import org.tenshu.sync.client.schemaFence
import org.tenshu.sync.client.syncService
import org.tenshu.sync.fencedServices
import org.tenshu.sync.syncNow
import kotlin.js.Promise

/** Bytes still waiting to reach the content plane, and whether a policy is holding them back. */
@JsExport
class ContentTransferJs internal constructor(
    val deferred: Boolean,
    val pendingFileCount: Int,
    val pendingBytes: Double,
)

/**
 * Whether this device is schema-fenced — its build is too old to read what the realm now writes,
 * so sync is held rather than corrupting data.
 */
@JsExport
class SchemaFenceJs internal constructor(
    val fenced: Boolean,
    /** The services holding the fence; empty when [fenced] is false. */
    val serviceIds: Array<String>,
)

/**
 * The remaining W3 signals. Like [SyncHealth], these are readers of rows the runtime has always
 * maintained — `initSyncService` calls `initContentTransferStatus`, and `SyncScheduler` keeps the
 * fence row — with nothing on web reading them until now.
 */
@JsExport
object SyncStatus {

    /** Pending content transfer, or `null` before the first row exists. */
    fun contentTransfer(): Promise<ContentTransferJs?> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.syncService.contentTransfer().first()?.let {
            ContentTransferJs(
                deferred = it.deferred.get(),
                pendingFileCount = it.pendingFileCount.get().toInt(),
                pendingBytes = it.pendingBytes.get().toDouble(),
            )
        }
    }

    /** Schema-fence state, or `null` before the first row exists. */
    fun schemaFence(): Promise<SchemaFenceJs?> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.syncService.schemaFence().first()?.let {
            SchemaFenceJs(fenced = it.fenced.get(), serviceIds = it.fencedServices().toTypedArray())
        }
    }

    /**
     * Ask the scheduler to drain now. Resolves `true` when the kick was signalled — **not** when
     * the drain succeeded; the drain runs on the next tick.
     *
     * ⚠ The outcome is returned rather than swallowed for the reason the native repository spells
     * out: a failed kick is otherwise indistinguishable from a successful one whose drain then
     * failed, which is what makes "the button does nothing" unfalsifiable from a bug report.
     */
    fun syncNow(): Promise<Boolean> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val result = JsRuntime.tenshuClient.syncService.syncNow()
        val ok = result !is TResult.Failure<*>
        if (!ok) {
            app.tenshu.diagnostics.Diagnostics.w("SyncNow", "kick failed: $result")
        }
        ok
    }
}
