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

    @Test
    fun eachListIsItsOwnRowAndAnUnknownKeyFails() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val apps: dynamic = Overview.setList("shownApps", arrayOf("drive", "photos")).await()
        assertEquals(true, apps.ok, JSON.stringify(apps))
        val widgets: dynamic = Overview.setList("widgets", arrayOf("app:photos", "apps", "hidden:app:drive")).await()
        assertEquals(true, widgets.ok, JSON.stringify(widgets))
        val background: dynamic = Overview.setList("background", arrayOf("teal")).await()
        assertEquals(true, background.ok, JSON.stringify(background))

        assertEquals(listOf("drive", "photos"), (Overview.list("shownApps").await().appIds as Array<String>).toList())
        assertEquals(
            listOf("app:photos", "apps", "hidden:app:drive"),
            (Overview.list("widgets").await().appIds as Array<String>).toList(),
        )
        assertEquals(listOf("teal"), (Overview.list("background").await().appIds as Array<String>).toList())

        val unknownWrite: dynamic = Overview.setList("nope", arrayOf("x")).await()
        assertEquals(false, unknownWrite.ok)
        assertEquals("unknown_key", unknownWrite.error.kind)
        assertEquals(false, Overview.list("nope").await().ok)
    }
}
