@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The overview's shown-apps list round-trips through the REAL runtime's preference plane
 * (`docs/proposals/web-apex-home.md` §3.5) — through the exported JS surface the apex calls, not the
 * Kotlin helpers under it, so a broken envelope fails here and not in a browser.
 */
// Extends [SdkRuntimeTest]: it builds the runtime, so the relay must be pinned to the sink first.
class OverviewWebTest : SdkRuntimeTest() {

    @Test
    fun aWrittenListComesBackInOrderWithoutRepeatsOrBlanks() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val wrote: dynamic = Overview.setShownApps(arrayOf("drive", "fit", "", "photos", "fit")).await()
        assertEquals(true, wrote.ok, "the write must succeed: ${JSON.stringify(wrote)}")

        val read: dynamic = Overview.shownApps().await()
        assertEquals(true, read.ok, "the read must succeed: ${JSON.stringify(read)}")
        assertEquals(
            listOf("drive", "fit", "photos"),
            (read.appIds as Array<String>).toList(),
            "order kept; blanks and repeats dropped",
        )
    }
}
