package org.tenshu.core.common.transport

import android.os.DeadObjectException
import android.os.RemoteException
import android.os.TransactionTooLargeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Pins the crash class found on device 2026-08-03: an OOMing Control restarted every ~25 s, and
 * each death killed **Timeline** with
 * `FATAL EXCEPTION: DefaultDispatcher-worker-7 / android.os.DeadObjectException`.
 *
 * The mechanism was a type mismatch, not a missing catch. [RemoteBinaryTaskExecutor] already wraps
 * the transport call in `catch (e: IllegalStateException) -> connectionFailure(...)`, but a Binder
 * death arrives as a [RemoteException], which that catch does not match — so it escaped the
 * `viewModelScope.launch` that issued the read, and an uncaught coroutine exception is fatal on
 * Android. [asNotConnected] closes it by translating at the platform boundary, where the Android
 * types are actually visible.
 */
class BinderDeathMappingTest {

    @Test
    fun aSuccessfulTransactionIsPassedThroughUntouched() {
        val payload = byteArrayOf(1, 2, 3)
        assertSame(payload, asNotConnected { payload })
    }

    @Test
    fun aDeadProxyBecomesTheNotConnectedSignalTheExecutorAlreadyFolds() {
        val death = DeadObjectException()

        val mapped = assertFailsWith<IllegalStateException> {
            asNotConnected { throw death }
        }

        // IllegalStateException specifically: that is the type RemoteBinaryTaskExecutor.roundTrip
        // catches to produce a typed TResult.Failure. Any other type is a crash again.
        assertEquals("Proxy died mid-call", mapped.message)
        assertSame(death, mapped.cause, "the original death must stay diagnosable")
    }

    /**
     * ⚠ A non-death Binder failure must also not be fatal — but it is not a *disconnect*, so it
     * carries its own message rather than being mislabelled as one in the failure detail.
     */
    @Test
    fun otherBinderFailuresAreAlsoNonFatalButDistinctlyNamed() {
        val tooLarge = TransactionTooLargeException()

        val mapped = assertFailsWith<IllegalStateException> {
            asNotConnected { throw tooLarge }
        }

        assertEquals("Proxy call failed: TransactionTooLargeException", mapped.message)
        assertSame(tooLarge, mapped.cause)
    }

    /**
     * The guard that keeps the mapping honest: a [DeadObjectException] IS a [RemoteException] but
     * is NOT an [IllegalStateException]. If that ever stopped holding, the executor's existing
     * catch would have covered this all along and this translation would be dead weight.
     */
    @Test
    fun aBinderDeathIsNotAnIllegalStateExceptionOnItsOwn() {
        val death = DeadObjectException()
        assertIs<RemoteException>(death)
        // ⚠ Through `Any`, deliberately: the compiler can prove the negative statically and now
        // rejects the direct check as an error, which had quietly stopped this whole source set
        // from compiling. Erasing the static type keeps the assertion the runtime one it was
        // always meant to be.
        val erased: Any = death
        assertFalse(
            erased is IllegalStateException,
            "a Binder death is not an IllegalStateException — if it became one, the executor's " +
                "existing catch would already cover it and this translation is dead weight",
        )
    }

    /** Cancellation and other non-Binder throws must pass straight through, unwrapped. */
    @Test
    fun nonBinderThrowsArePassedThroughUnwrapped() {
        val boom = IllegalArgumentException("not an IPC problem")
        val thrown = assertFailsWith<IllegalArgumentException> {
            asNotConnected { throw boom }
        }
        assertSame(boom, thrown)
    }
}
