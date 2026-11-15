@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.toJs
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.tenshu.core.SimpleComposite
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.fold
import org.tenshu.core.file.notifications.ChangeLostNotification
import org.tenshu.core.file.notifications.FileAddedNotification
import org.tenshu.core.file.notifications.FileDeletedNotification
import org.tenshu.core.file.notifications.FolderAddedNotification
import org.tenshu.core.file.notifications.FolderDeletedNotification
import org.tenshu.core.notifications.AppRegisteredNotification
import org.tenshu.core.notifications.AppRemovedNotification
import org.tenshu.core.notifications.Notification
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.CancellationException
import org.tenshu.core.toTString
import org.tenshu.notifications.client.notificationService
import kotlin.js.Date

@JsExport
object Notifications {

    // Prototype demo: builds a Notification-shaped SimpleComposite stamped
    // with `Date.now()` and runs it through the schema walker. The demo
    // exercises the UnixInstant unwrap path (TInteger leaf → JS number) on
    // top of the Device-era TString unwrap.
    fun demo(): dynamic = simpleNotification("demo-notification-uuid", Date.now().toLong()).toJs()

    // Subscribes to the NotificationService's notifications list and re-emits
    // each merged TList<Notification> snapshot as a JS array of walked
    // Composites. Mirrors the `flow().collect { fold ... }` pattern used in
    // production (e.g. :control-shared/.../HomeScreenIntegrationTest); each
    // emission carries the FULL list, matching the protocol's
    // `Flow<TResult<TList<T>>>` shape after merging (see
    // :core/.../collection/list/Stream.kt).
    //
    // Caller pattern: callback + unsubscribe lambda. Cleanly composes with
    // React useEffect: `useEffect(() => Notifications.observe(setItems), [])`.
    // Cancelling the returned lambda cancels the launched collector, which
    // walks the Flow's `finally` block and `endStream`s the underlying
    // StreamInteractor.
    fun observe(callback: (Array<dynamic>) -> Unit): () -> Unit {
        val job = GlobalScope.launch {
            JsRuntime.awaitReady()
            val client = JsRuntime.tenshuClient
            client.notificationService.notifications.flow().collect { result ->
                result.fold(
                    onSuccess = { list ->
                        callback(list.toList().map { it.toJs() }.toTypedArray())
                    },
                    onFailure = {
                        // Surface as empty until the apex grows a JS-side
                        // error channel for stream failures.
                        callback(emptyArray())
                    },
                )
            }
        }
        return { job.cancel() }
    }

    /**
     * The newest [limit] notifications as display events — what the apex bell lists
     * (`web-apex-home.md` §3.4), the web twin of the desktop tray's `recentNotifications`
     * (`ControlDataBridge.notificationsResult`): `{ ok, events: [{ id, timestamp, kind, name,
     * otherCount }] }`, newest first, re-emitted on every change.
     *
     * ⚠⚠ A PORT of `NotificationMapper.mapToEventOrNull` (`:control-shared-logic`, which this module
     * does not depend on). A kind added there owes one here, or the bell silently drops it. Unknown
     * subtypes are skipped, never thrown: a newer device's notification must not blank the list.
     * ⚠ A failed read emits `{ ok: false }` — never an empty list. [onError] fires once if the stream
     * ends for any reason but the caller's own cancel.
     */
    fun observeRecent(limit: Int, callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit {
        val job = GlobalScope.launch {
            val failure = try {
                JsRuntime.awaitReady()
                JsRuntime.tenshuClient.notificationService.notifications.flow().collect { result ->
                    result.fold(
                        onSuccess = { list ->
                            val events = list.toList()
                                .mapNotNull { recentEventOrNull(it) }
                                .sortedByDescending { it.timestamp }
                                .take(limit.coerceIn(1, 50))
                            callback(okResult { r -> r.events = events.map { it.toJs() }.toTypedArray() })
                        },
                        onFailure = { callback(failureResult("read_failed", it.toString())) },
                    )
                }
                "observe_ended"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            onError(failure)
        }
        return { job.cancel() }
    }
}

private class RecentEvent(
    val id: String,
    val timestamp: Long,
    val kind: String,
    val name: String,
    val otherCount: Int,
) {
    fun toJs(): dynamic {
        val o: dynamic = js("({})")
        o.id = id
        o.timestamp = timestamp.toDouble()
        o.kind = kind
        o.name = name
        o.otherCount = otherCount
        return o
    }
}

// ⚠ Order matters exactly as in `NotificationMapper`: the folder subtypes are-a file subtype.
private fun recentEventOrNull(n: Notification): RecentEvent? {
    val id = n.uuid.get()
    val ts = n.timestamp.get()
    return when (n) {
        is FolderAddedNotification -> RecentEvent(id, ts, "folder_added", n.path.get(), 0)
        is FileAddedNotification -> {
            // The producer coalesces a bulk upload into one row and bumps `count` (null = pre-coalescing).
            val count = n.count?.get() ?: 1L
            if (count > 1L) RecentEvent(id, ts, "files_added", n.path.get(), (count - 1L).toInt())
            else RecentEvent(id, ts, "file_added", n.path.get(), 0)
        }
        is FolderDeletedNotification -> RecentEvent(id, ts, "folder_deleted", n.path.get(), 0)
        is FileDeletedNotification -> RecentEvent(id, ts, "file_deleted", n.path.get(), 0)
        is ChangeLostNotification -> RecentEvent(id, ts, "change_lost", n.fileName.get(), 0)
        is AppRegisteredNotification -> RecentEvent(id, ts, "app_registered", n.appName.get(), 0)
        is AppRemovedNotification -> RecentEvent(id, ts, "app_removed", n.appName.get(), 0)
        else -> null
    }
}

private fun simpleNotification(uuid: String, timestampMs: Long): SimpleComposite =
    SimpleComposite(
        schema = Notification.Schema,
        fieldValues = mapOf(
            Notification.Fields.UUID to uuid.toTString(),
            Notification.Fields.TIMESTAMP to UnixInstantDto(timestampMs),
        ),
    )
