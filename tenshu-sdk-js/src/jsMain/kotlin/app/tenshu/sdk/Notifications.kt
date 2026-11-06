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
import org.tenshu.core.notifications.Notification
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
}

private fun simpleNotification(uuid: String, timestampMs: Long): SimpleComposite =
    SimpleComposite(
        schema = Notification.Schema,
        fieldValues = mapOf(
            Notification.Fields.UUID to uuid.toTString(),
            Notification.Fields.TIMESTAMP to UnixInstantDto(timestampMs),
        ),
    )
