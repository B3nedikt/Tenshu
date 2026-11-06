@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.promise
import kotlin.js.Promise

/**
 * The Maps web surface (`web-app-suite.md` Wave 3, Maps run A): the native `PlaceRepository`
 * (`maps-shared-logic`) as implemented by `PlaceProjectionRepository` (`maps-shared-data`), over
 * `PlacesBook.kt` — which reuses Timeline's place reads rather than copying them (see its header).
 *
 * | Native `PlaceRepository` | Here                                          |
 * |--------------------------|-----------------------------------------------|
 * | `places()` (Flow)        | [places] snapshot + [observePlaces] push      |
 * | `place(id)`              | [place] — a folded id follows to its survivor |
 * | `savedLists()` (empty)   | — no store (`maps-app.md` §Web scope 5a)      |
 * | `setSaved` (no-op)       | — no store (§Web scope 5a)                    |
 * | — (Timeline's)           | [renamePlace] — Timeline's write, delegated   |
 *
 * Every call answers `{ ok: true, … }` or `{ ok: false, error: { kind, message } }`, `kind` a
 * catalogue key. ⚠ A failed read is a failure, never an empty list (native folds it to empty behind
 * its reachability banner; the web has none). ⚠ The keys are Timeline's (`error_timeline_*`): the
 * reads and the write are Timeline's, so the vocabulary is too.
 *
 * ⚠⚠ Nothing here starts work: no `PlaceReconciler`, no geocoding, no timer (`PlacePlugin` KDoc).
 * The reads are relation reads; the push is the subscriber's own collector and ends with it.
 */
@JsExport
object Maps {

    /** Every surviving (un-folded) place: `{ ok: true, places }`, in store order. */
    fun places(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        mapsPlacesResult(JsRuntime.tenshuClient.mapsPlacesFlow().first())
    }

    /**
     * Pushes the [places] envelope on every place / merge / role change until the returned lambda is
     * called. ⚠ [onError] is terminal: a collector that throws or completes reports it, the
     * subscriber's own cancel does not.
     */
    fun observePlaces(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        Contacts.observeJob(onError) {
            JsRuntime.tenshuClient.mapsPlacesFlow().collect { callback(mapsPlacesResult(it)) }
        }

    /** One place: `{ ok: true, place }`, `place` **null** when unknown. A folded id is followed. */
    fun place(placeId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        mapsPlaceResult(JsRuntime.tenshuClient.mapsPlacesFlow().first(), placeId)
    }

    /**
     * Rename a place — [Timeline.renamePlace], the one write path: `labelSource = user`, every other
     * field kept. A blank name is `error_timeline_blank_name`, an unknown id
     * `error_timeline_place_not_found`. Unlike Timeline, a folded id is first followed to its survivor
     * (the place [place] shows), so a rename from a stale link is never a silent no-op.
     */
    fun renamePlace(placeId: String, name: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val target = mapsRenameTarget(JsRuntime.tenshuClient.mapsPlacesFlow().first(), placeId)
        Timeline.renamePlace(target, name).await()
    }
}
