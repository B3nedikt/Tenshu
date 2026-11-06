package app.tenshu.timeline.data.geocode

/**
 * Runs a backfill whenever the user turns place-name lookups on (`docs/proposals/place-geocoding.md`
 * §6 "backfill scheduling", v1 triggers): collecting [PlaceGeocodingControl.enabled], every `true`
 * emission — including the initial value at app start when the opt-in is already on — kicks
 * [backfill]. That is what names the places that existed *before* the opt-in (imported history,
 * visits captured while the toggle was off) and retries fruitless coordinates from earlier
 * sessions; freshly captured places are enriched inline by the writer hook instead. Collection is
 * sequential, so a backfill in flight is never doubled by a toggle bounce, and the enricher's own
 * consent re-check stops a run mid-way when the user opts back out.
 *
 * The platform owns the scope (Android: an application-lifetime scope in `TimelineApplication`);
 * the periodic sweep stays deferred to bgproc Phase 6 per the proposal.
 */
class GeocodingBackfillTrigger(
    private val control: PlaceGeocodingControl,
    private val backfill: suspend () -> Unit,
) {
    /** Suspends for the caller's lifetime, backfilling on each opt-in edge. */
    suspend fun run() {
        control.enabled.collect { enabled ->
            if (enabled) backfill()
        }
    }
}
