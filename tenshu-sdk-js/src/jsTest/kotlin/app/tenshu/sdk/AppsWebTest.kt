@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import org.tenshu.core.app.registerApp
import org.tenshu.core.common.app.appService
import org.tenshu.identity.Device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Apps.observeRegistrations` / `Apps.removeApp` through the REAL runtime
 * (`docs/proposals/web-apex-home.md` §3.5, H6) — rows written with the native `RegisterApp` op must
 * come out of the exported JS surface, grouped by app, with each device named from the roster or
 * `null`, and a removal must take the app out of the live view.
 *
 * ⚠⚠ Through the live observer, not `joinRegistrations` alone: the failure this guards is the
 * relation never being maintained in a browser (no `initAppService` on web), which a pure join test
 * would pass against.
 */
// ⚠ Extends [SdkRuntimeTest]: this class builds the runtime and sorts before CalendarWebTest, so
// without the relay sink it baked in the production URL and failed every Calendar test.
class AppsWebTest : SdkRuntimeTest() {

    @Test
    fun registrationsAreGroupedByAppAndARemovalLeavesTheLiveView() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val apps = JsRuntime.tenshuClient.appService
        val a = "app.tenshu.apps-web-test-a"
        val b = "app.tenshu.apps-web-test-b"
        // ⚠ Device ids the roster cannot hold, so their names must come back null — never guessed.
        apps.registerApp(a, "apps-web-test-phone", verified = true)
        apps.registerApp(a, "apps-web-test-laptop", verified = false)
        apps.registerApp(b, "apps-web-test-phone", verified = true)

        val seen = Observed()
        val unsubscribe = Apps.observeRegistrations({ seen.onEmit(it) }, { seen.failed.complete(it) })
        try {
            val first = seen.awaitApps { it.containsKey(a) && it.containsKey(b) }
            val appA = first.getValue(a)
            assertEquals(
                listOf("apps-web-test-laptop", "apps-web-test-phone"),
                (appA.devices as Array<dynamic>).map { it.deviceId as String },
                "one entry per device, sorted",
            )
            (appA.devices as Array<dynamic>).forEach {
                assertNull(it.name, "a device the roster does not hold has no name: ${JSON.stringify(it)}")
            }
            assertEquals(false, appA.verified, "unverified when any row is")
            assertTrue((appA.installedAtMs as Double) > 0, "the earliest createdAt is the install date")

            val removed: dynamic = Apps.removeApp(a).await()
            assertEquals(true, removed.ok, "the removal must succeed: ${JSON.stringify(removed)}")
            val after = seen.awaitApps { !it.containsKey(a) }
            assertTrue(after.containsKey(b), "removing one app leaves the others")
        } finally {
            unsubscribe()
        }
    }

    /**
     * A device on the AUTHORISED roster is named from it; a revoked one is not — its row still counts
     * as a registration, but a surface must not offer "you use it on" a device that left the realm.
     */
    @Test
    fun anAuthorisedDeviceIsNamedAndARevokedOneIsNot() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val ec = JsRuntime.executionContext
        val pixel = "apps-web-test-pixel"
        val old = "apps-web-test-old-phone"
        ec.saveData(Device.create(ec.context, deviceId = pixel, name = "Pixel 9", publicKey = ByteArray(32)))
        ec.saveData(
            Device.create(ec.context, deviceId = old, name = "Old phone", publicKey = ByteArray(32), isAuthorized = false),
        )
        val appId = "app.tenshu.apps-web-test-named"
        val apps = JsRuntime.tenshuClient.appService
        apps.registerApp(appId, pixel, verified = true)
        apps.registerApp(appId, old, verified = true)

        val seen = Observed()
        val unsubscribe = Apps.observeRegistrations({ seen.onEmit(it) }, { seen.failed.complete(it) })
        try {
            val view = seen.awaitApps { v ->
                (v[appId]?.devices as Array<dynamic>?)?.any { it.name == "Pixel 9" } == true
            }
            val names = (view.getValue(appId).devices as Array<dynamic>).associate {
                (it.deviceId as String) to (it.name as String?)
            }
            assertEquals(mapOf(old to null, pixel to "Pixel 9"), names)
        } finally {
            unsubscribe()
        }
    }
}

private class Observed {
    val failed = CompletableDeferred<String>()
    private var waiting: Pair<(Map<String, dynamic>) -> Boolean, CompletableDeferred<Map<String, dynamic>>>? = null
    private var latest: Map<String, dynamic>? = null

    fun onEmit(result: dynamic) {
        if (result.ok != true) {
            failed.complete("read failed: ${JSON.stringify(result)}")
            return
        }
        val view = (result.apps as Array<dynamic>).associateBy { it.appId as String }
        latest = view
        waiting?.let { (match, done) -> if (match(view)) done.complete(view) }
    }

    suspend fun awaitApps(match: (Map<String, dynamic>) -> Boolean): Map<String, dynamic> {
        latest?.let { if (match(it)) return it }
        val done = CompletableDeferred<Map<String, dynamic>>()
        waiting = match to done
        return withTimeout(10_000) {
            kotlinx.coroutines.selects.select {
                done.onAwait { it }
                failed.onAwait { error(it) }
            }
        }
    }
}
