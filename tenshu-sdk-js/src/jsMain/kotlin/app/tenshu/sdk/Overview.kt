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
    val ShownApps = listPreference("apex.overview.shownApps")

    /**
     * The overview's widgets, in order, plus a `hidden:<widget>` entry per default widget the user
     * removed (web-apex-home.md §4.12). **REALM**. ⚠ Empty means "never edited": the apex derives its
     * default from [ShownApps]. Tokens the apex does not know are kept, never dropped.
     */
    val Widgets = listPreference("apex.overview.widgets")

    /**
     * The overview's background colour, one token (`blue`, … or `h<deg>c<thousandths>`, web-apex-home.md §4.14), as a
     * one-element list. **REALM**: the wallpaper follows the user, not the browser. Empty = default.
     */
    val Background = listPreference("apex.overview.background")

    /** The lists the apex may read and write, by the key it names them with. */
    val ByKey: Map<String, Preference<List<String>>> = mapOf(
        "shownApps" to ShownApps,
        "widgets" to Widgets,
        "background" to Background,
    )

    private fun listPreference(id: String) = Preference(
        id = id,
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

    /** `{ ok, appIds }` — one of the overview's stored lists, once; an unknown key fails. */
    fun list(key: String): Promise<dynamic> = GlobalScope.promise {
        val pref = OverviewPreferences.ByKey[key] ?: return@promise failureResult("unknown_key", key)
        JsRuntime.awaitReady()
        shownAppsResult(realmPref(pref))
    }

    /** [observeList] for the shown apps. */
    fun observeShownApps(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeList("shownApps", callback, onError)

    /** [setList] for the shown apps. */
    fun setShownApps(appIds: Array<String>): Promise<dynamic> = setList("shownApps", appIds)

    /**
     * One of the overview's stored lists ([OverviewPreferences.ByKey]), live — re-emits on a local
     * write and on a row synced in from another device, as `{ ok, appIds }`.
     * ⚠ Seeds with a one-shot read: the realm relation emits nothing on a realm that has never held
     * any setting, so a bare collect would wait forever. ⚠⚠ Every end but the caller's own cancel
     * reaches [onError] once, so a dead stream never freezes the overview on its last value.
     */
    fun observeList(key: String, callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit {
        val pref = OverviewPreferences.ByKey[key]
        if (pref == null) {
            onError("unknown_key")
            return {}
        }
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
                emit(realmPref(pref))
                realmPrefFlow(pref).collect { emit(it) }
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
     * Replaces a whole stored list — last write wins (§3.5). The apex has already shaped it (Drive
     * first, unknown ids kept); this layer stores what it is given, minus blanks and repeats.
     */
    fun setList(key: String, values: Array<String>): Promise<dynamic> = GlobalScope.promise {
        val pref = OverviewPreferences.ByKey[key] ?: return@promise failureResult("unknown_key", key)
        JsRuntime.awaitReady()
        val clean = values.filter { it.isNotBlank() }.distinct()
        // ⚠ An unchanged list writes nothing: a no-op write still burns a sync block.
        val current = realmPref(pref)
        if (current is Read.Ok && current.value == clean) return@promise okResult()
        JsRuntime.tenshuClient.preferenceService.set(pref, clean).fold(
            onSuccess = { okResult() },
            onFailure = { failureResult("write_failed", it.toString()) },
        )
    }
}

private fun shownAppsResult(read: Read<List<String>>): dynamic = when (read) {
    is Read.Ok -> okResult { it.appIds = read.value.toTypedArray() }
    is Read.Failed -> failureResult("read_failed", read.error.toString())
}
