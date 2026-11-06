package org.tenshu.core.common.transport

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import app.tenshu.control.android.IProxyInterface
import org.tenshu.files.util.ParcelFileDescriptorCache
import org.tenshu.files.util.SharedMemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wiring half of [ReconnectEdgeTest]: the real [BinderTaskTransport], driven through the
 * `ServiceConnection` callbacks the framework would deliver.
 *
 * ⚠⚠ An explicit `unbind()` ends this client's session in Control (`ProxyService.onUnbind`
 * unregisters it, streams and all), so the connect after the next `bind()` must announce — or every
 * stream the client still holds stays subscribed to nothing. Drive unbinds in
 * `MainActivity.onDestroy`, and that is how a dark-mode switch froze its Files listing and its
 * list/grid toggle (2026-10-16).
 */
class BinderTaskTransportRebindTest {

    private class FakeContext : ContextWrapper(null) {
        var connection: ServiceConnection? = null
        override fun getApplicationContext() = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            return true
        }
        override fun unbindService(conn: ServiceConnection) {}
    }

    private fun proxyBinder(): IBinder = object : IProxyInterface.Stub() {
        override fun call(request: ByteArray?) = ByteArray(0)
        override fun callCancellable(request: ByteArray?, taskId: String?) = ByteArray(0)
        override fun cancel(taskId: String?) {}
        override fun sendFile(fileDescriptor: ParcelFileDescriptor?, contentUri: String?) {}
        override fun ipcRevision() = 0
        override fun callCancellableRead(request: ByteArray?, readId: String?) = ByteArray(0)
        override fun cancelRead(readId: String?) {}
        override fun callCancellableUserInitiated(request: ByteArray?, taskId: String?) = ByteArray(0)
    }

    private val context = FakeContext()
    private val transport = BinderTaskTransport(
        context,
        ParcelFileDescriptorCache(),
        SharedMemoryCache(),
        controlCheck = { true },
    )
    private var announcements = 0
    private val name = ComponentName("app.tenshu.control.android", "ProxyService")

    private fun connect() {
        transport.bind()
        context.connection!!.onServiceConnected(name, proxyBinder())
    }

    init {
        transport.setOnReconnected { announcements++ }
    }

    @Test
    fun aFirstConnectIsSilent() {
        connect()
        assertEquals(0, announcements)
    }

    @Test
    fun aConnectAfterAnExplicitUnbindAnnounces() {
        connect()
        transport.unbind()
        connect()
        assertEquals(1, announcements, "the unbind ended the proxy session; its streams must be replayed")
    }

    @Test
    fun anUnbindWhileNotConnectedOwesNothing() {
        transport.unbind()
        connect()
        assertEquals(0, announcements)
    }

    /**
     * ⚠⚠ The reconnect callback runs **before** the connection signal wakes anyone
     * (`docs/tech-debt.md`, *"A call can beat the re-announce after a Control restart"*). The
     * callback re-arms the executor's announce; a caller woken first sent its call on the previous
     * session's announce and was refused `unannouncedSession`. And it runs **after**
     * `isConnected` flips, so the replay it launches does not see "not connected" and bind again.
     *
     * `Dispatchers.Unconfined` resumes the parked waiter inline, inside `complete()`, so the event
     * order below is the order `onServiceConnected` does its work in.
     */
    @Test
    fun aReconnectReArmsBeforeItWakesTheWaiters() {
        val events = mutableListOf<String>()
        transport.setOnReconnected { events += "reconnected (isConnected=${transport.isConnected})" }
        connect()
        transport.unbind()
        transport.bind()
        CoroutineScope(Dispatchers.Unconfined).launch {
            transport.awaitConnected(60_000)
            events += "waiter woken"
        }
        context.connection!!.onServiceConnected(name, proxyBinder())
        assertEquals(listOf("reconnected (isConnected=true)", "waiter woken"), events)
    }
}
