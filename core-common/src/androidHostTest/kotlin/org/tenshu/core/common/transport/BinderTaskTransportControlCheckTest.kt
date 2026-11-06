package org.tenshu.core.common.transport

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IInterface
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import app.tenshu.control.android.IProxyInterface
import kotlinx.coroutines.runBlocking
import org.tenshu.files.util.ParcelFileDescriptorCache
import org.tenshu.files.util.SharedMemoryCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An app talks to the real Control only (threat model T-C-20). When Control is not installed, any
 * sideloaded app can take the name `app.tenshu.control.android`. It must neither receive this app's
 * calls nor deliver callbacks into it.
 */
class BinderTaskTransportControlCheckTest {

    private class FakeContext : ContextWrapper(null) {
        var binds = 0
        var unbinds = 0
        var connection: ServiceConnection? = null
        override fun getApplicationContext() = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            binds++
            connection = conn
            return true
        }
        override fun unbindService(conn: ServiceConnection) {
            unbinds++
        }
    }

    private val context = FakeContext()

    private fun transport(controlIsFirstParty: Boolean) = BinderTaskTransport(
        context,
        ParcelFileDescriptorCache(),
        SharedMemoryCache(),
        controlCheck = { controlIsFirstParty },
    )

    @Test
    fun theRealControlIsBound() {
        transport(controlIsFirstParty = true).bind()
        assertEquals(1, context.binds)
    }

    @Test
    fun anAppHoldingControlsNameButNotItsKeyIsNeverBound() {
        val transport = transport(controlIsFirstParty = false)
        transport.bind()
        assertEquals(0, context.binds, "nothing this app sends may reach an impostor")
        assertFalse(transport.isConnected)
    }

    /** ⚠⚠ The forged-callback case: Control's name on the caller, but not Control's key. */
    @Test
    fun aCallbackFromAnImpostorControlIsRefused() {
        val guard = ClientCallbackGuard(
            isControlUid = { isControlCaller(arrayOf(FirstPartySigning.CONTROL_PACKAGE), controlIsFirstParty = { false }) },
        )
        assertFailsWith<SecurityException> { guard.requireControl(IMPOSTOR_UID, "callback") }
    }

    @Test
    fun aCallbackFromTheRealControlIsAdmittedAndFromAStrangerIsNot() {
        assertTrue(isControlCaller(arrayOf(FirstPartySigning.CONTROL_PACKAGE), controlIsFirstParty = { true }))
        assertFalse(isControlCaller(arrayOf("com.example.flashlight"), controlIsFirstParty = { true }))
        assertFalse(isControlCaller(null, controlIsFirstParty = { true }))
    }

    /**
     * Control refusing this app arrives as a SecurityException, which is no RemoteException. Left
     * alone it would crash the app on its first read instead of showing that Control is unreachable.
     */
    @Test
    fun controlRefusingThisAppIsAFailureNotACrash() {
        val folded = assertFailsWith<IllegalStateException> {
            asNotConnected<Unit> { throw SecurityException("call: uid 10300 is not a first-party Tenshu app") }
        }
        assertTrue(folded.message!!.startsWith("Control refused this app"))
        assertTrue(folded.cause is SecurityException)
    }

    @Test
    fun aRemoteFailureStillFoldsAsBefore() {
        val folded = assertFailsWith<IllegalStateException> { asNotConnected<Unit> { throw RemoteException("x") } }
        assertNull(folded.cause as? SecurityException)
    }

    /** A Control that refuses this app, as the real one does a caller it does not count first-party. */
    private val refusingControl = object : IProxyInterface.Stub() {
        // The host `Binder` cannot look up a local interface, so hand the stub out directly.
        override fun queryLocalInterface(descriptor: String): IInterface = this
        private fun refuse(): Nothing = throw SecurityException("call: uid 10300 is not a first-party Tenshu app")
        override fun call(request: ByteArray?): ByteArray = refuse()
        override fun callCancellable(request: ByteArray?, taskId: String?): ByteArray = refuse()
        override fun cancel(taskId: String?) = refuse()
        override fun sendFile(fileDescriptor: ParcelFileDescriptor?, contentUri: String?) = refuse()
        override fun ipcRevision(): Int = refuse()
        override fun callCancellableRead(request: ByteArray?, readId: String?): ByteArray = refuse()
        override fun cancelRead(readId: String?) = refuse()
        override fun callCancellableUserInitiated(request: ByteArray?, taskId: String?): ByteArray = refuse()
    }

    /**
     * ⚠⚠ Left bound, a refused app still reads as connected and every caller retries into the
     * refusal at once (~17 calls/s measured on an emulator). The refusal must end the binding and
     * hold off the next bind, so the app behaves as it does with Control absent.
     */
    @Test
    fun aRefusalEndsTheBindingAndHoldsOffTheNextBind() {
        val transport = transport(controlIsFirstParty = true)
        transport.bind()
        context.connection!!.onServiceConnected(ComponentName(FirstPartySigning.CONTROL_PACKAGE, "ProxyService"), refusingControl)
        assertTrue(transport.isConnected)

        val failure = assertFailsWith<IllegalStateException> { runBlocking { transport.call(ByteArray(1)) } }
        assertTrue(failure.message!!.startsWith("Control refused this app"))
        assertFalse(transport.isConnected)
        assertEquals(1, context.unbinds)

        transport.bind()
        assertEquals(1, context.binds, "no rebind straight into the same refusal")
    }

    private companion object {
        const val IMPOSTOR_UID = 10_400
    }
}
