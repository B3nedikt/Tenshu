package app.tenshu.photos

import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.pageReadFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * How a folded page-read failure is classified (`ADR 0025`).
 *
 * ## ⚠⚠ The bug this pins
 *
 * `RemoteBinaryTaskExecutor` turns an unreachable proxy into a typed `TResult.Failure` rather than
 * letting it throw, so on Android a dead transport reaches the feed's `fold(onFailure)` — **not**
 * the `catch` below it. That arm reported [PhotoPageResult.Failed], which the shell renders as
 * *"Something went wrong loading your photos"* with a **Try again** button, while the line directly
 * above it had already told the reachability engine the proxy was unreachable. The
 * [PhotoPageResult.Unreachable] the `catch` produces was effectively dead code on the one platform
 * where it mattered. Found by uninstalling Control on a device, not by a test.
 */
class PageReadFailureTest {

    /** ⚠ `false` is authoritative — no call crosses a dead channel, so the failure IS the channel. */
    @Test
    fun aFailureWithNoChannelIsUnreachable() {
        assertEquals(
            PhotoPageResult.Unreachable,
            pageReadFailure(proxyConnected = false, detail = "Proxy not connected"),
        )
    }

    /**
     * ⚠⚠ `true` proves nothing about the read, only about the channel: a connected proxy still
     * refuses every read on a schema mismatch. So this stays a genuine failure and the shell's
     * reason supplies the words — reporting "unreachable" here would put the wrong banner over a
     * device whose proxy is answering perfectly.
     */
    @Test
    fun aFailureOverALiveChannelIsAGenuineReadFailure() {
        val result = pageReadFailure(proxyConnected = true, detail = "boom")
        assertIs<PhotoPageResult.Failed>(result)
        assertEquals("boom", result.message)
    }

    /**
     * ⚠⚠ The classification must not depend on the error's TEXT. The executor's connection failure
     * is a `GenericError` carrying the string "Proxy not connected", and matching on it would be a
     * rename away from a silent production-only regression — the shape of the relay-401 status-text
     * bug. Same words, opposite answers, decided entirely by the channel.
     */
    @Test
    fun theSameMessageClassifiesBothWays_becauseTheTextIsNotTheSignal() {
        assertEquals(
            PhotoPageResult.Unreachable,
            pageReadFailure(proxyConnected = false, detail = "Proxy not connected"),
        )
        assertIs<PhotoPageResult.Failed>(
            pageReadFailure(proxyConnected = true, detail = "Proxy not connected"),
        )
    }
}
