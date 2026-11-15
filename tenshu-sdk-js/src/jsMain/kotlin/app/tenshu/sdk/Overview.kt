@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import org.tenshu.core.fold
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope
import org.tenshu.preference.StringListAdapter
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.set
import kotlin.js.Promise

/**
 * The apex overview's synced settings (`docs/proposals/web-apex-home.md` §3.5).
 *
 * ⚠ A preference KEY, not a new composite: the value rides an existing `RealmSetting` row
 * (`preferenceId` + `genericData` value), so adding it reshapes nothing — no service bump
 * (House Rule 9), and `SchemaShapeBaseline` does not move.
 */
internal object OverviewPreferences {
    /**
     * The apps the user chose to show, in their order. **REALM** scope: one list shared by every
     * browser (D2). ⚠ Empty means "never customised" — the apex then shows its default (D1); the
     * apex never writes an empty list, because Drive is always in it.
     *
     * ⚠ A `StringListAdapter` blob: `genericData` cannot hold arrays (core-preference-service.md).
     */
    val ShownApps = Preference(
        id = "apex.overview.shownApps",
        scope = PreferenceScope.REALM,
        default = emptyList<String>(),
        adapter = StringListAdapter,
    )
}

@JsExport
object Overview {

    /** `{ ok, appIds }` — the stored list (`[]` = never customised), or a failure. */
    fun shownApps(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        shownAppsResult(realmPref(OverviewPreferences.ShownApps))
    }

    /**
     * [shownApps], live — re-emits on a local write and on a row synced in from another device.
     * ⚠ Seeds with a one-shot read: the realm relation emits nothing on a realm that has never held
     * any setting, so a bare collect would wait forever. ⚠⚠ Every end but the caller's own cancel
     * reaches [onError] once, so a dead stream never freezes the overview on its last value.
     */
    fun observeShownApps(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit {
        val job = GlobalScope.launch {
            val failure = try {
                JsRuntime.awaitReady()
                // ⚠ The relation re-emits when ANY realm setting changes (Calendar, Fit, …); forward
                // only a changed list, or every one of them re-renders the dock and both launchers.
                var last: List<String>? = null
                val emit = { read: Read<List<String>> ->
                    if (read !is Read.Ok || read.value != last) {
                        last = (read as? Read.Ok)?.value
                        callback(shownAppsResult(read))
                    }
                }
                emit(realmPref(OverviewPreferences.ShownApps))
                realmPrefFlow(OverviewPreferences.ShownApps).collect { emit(it) }
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

    /**
     * Replaces the whole list — last write wins (§3.5). The apex has already put Drive first and kept
     * the ids it does not know; this layer stores what it is given, minus blanks and repeats.
     */
    fun setShownApps(appIds: Array<String>): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val clean = appIds.filter { it.isNotBlank() }.distinct()
        // ⚠ An unchanged list writes nothing: a no-op write still burns a sync block.
        val current = realmPref(OverviewPreferences.ShownApps)
        if (current is Read.Ok && current.value == clean) return@promise okResult()
        JsRuntime.tenshuClient.preferenceService.set(OverviewPreferences.ShownApps, clean).fold(
            onSuccess = { okResult() },
            onFailure = { failureResult("write_failed", it.toString()) },
        )
    }
}

private fun shownAppsResult(read: Read<List<String>>): dynamic = when (read) {
    is Read.Ok -> okResult { it.appIds = read.value.toTypedArray() }
    is Read.Failed -> failureResult("read_failed", read.error.toString())
}
