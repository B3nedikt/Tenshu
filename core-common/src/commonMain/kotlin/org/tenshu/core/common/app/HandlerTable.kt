package org.tenshu.core.common.app

/**
 * Which app shows a row of a given type (`control-cross-app-open.md` CO-D2).
 *
 * ⭐ **A compiled-in table is the right starting point, for the same reason MQ-D10's validator
 * was.** There are a handful of types and a handful of first-party apps, all shipped by us;
 * building the declaration machinery before one pair works would be inventing a protocol against a
 * hypothesis. When Rei lands, this becomes the *fallback* under the app's declared `handles` set
 * and the user's primary-handler choice — and no caller changes, because the resolution was never
 * the caller's business (CO-D1).
 *
 * ⚠⚠ **The values are `appId`s — the realm-wide identity** (`app.tenshu.calendar`), not Android
 * packages (`app.tenshu.calendar.android`) and not macOS bundle ids. Those are per-platform
 * renderings, resolved at the launch seam; putting one here would make the table platform-specific
 * for no gain (CO-D4).
 *
 * ⚠ Deliberately not a `Map` in a DI module: nothing about this is configurable at runtime, and a
 * hook nobody uses is a hook that will be wired wrongly. It becomes data at TT-4 and not before.
 *
 * ⚠⚠ **Public, and NOT in the `.proxy` package, since TT-4b** — Control's Apps screen renders the
 * choosable types so the user can swap the primary handler, and a settings screen cannot show a
 * routing it cannot read. ⭐ That keeps ONE table: a client-side copy of "which apps can show a
 * place" would be a second source of truth able to disagree with the resolver, which is the drift
 * `PrimaryHandler` avoids for ids by deriving them.
 *
 * ⚠ This is not CO-D1 leaking. A *caller* still addresses a `DataId` and never names an app; what
 * reads the table here is the management surface whose whole job is to show the routing.
 */
object HandlerTable {

    /**
     * ⚠⚠ **Every line here is a claim about two codebases**: the type, and a real receiving route
     * in the app named. A line without one is worse than no line at all — it turns an honest
     * `NO_HANDLER` into a launch that opens an app which then shows the user nothing.
     * `HandlerTableTest` pins the whole map for exactly that reason.
     */
    private val handlers: Map<String, List<String>> = mapOf(
        // The occurrence, not the stored event: what a citation addresses is the instance the user
        // tapped, which `calendar-service-common` mints as a `CompositeEntityDataId` (CO-D5).
        OCCURRENCE_TYPE to listOf("app.tenshu.calendar"),
        // TT-2a. ⚠ Reached by two callers already: Timeline's photo tap and Mia's file citations,
        // which became tappable the moment this line existed — the caller side needed no change,
        // which is CO-D1 doing its job.
        FILE_TYPE to listOf("app.tenshu.drive"),
        // TT-2b. ⚠⚠ The only type with two candidates, and the reason the value is a list:
        // Timeline answers "what happened here" (visits, photos, the Place spine) and Maps answers
        // "where is it". Timeline first because a cited place almost always came from a moment;
        // Maps because a user who has one and not the other should still get their place opened.
        PLACE_TYPE to listOf("app.tenshu.timeline", "app.tenshu.maps"),
        MOMENT_TYPE to listOf("app.tenshu.timeline"),
        WORKOUT_TYPE to listOf("app.tenshu.fit"),
        // ⚠⚠ **`tenshu/home/device/homeDevice` is deliberately NOT here.** Home has no screen that
        // shows one device — its routes are Home, Automations, Activity, Settings and the
        // automation editor — so a line for it would launch an app that then shows the user
        // nothing, which is exactly the failure the KDoc above warns about. A device citation
        // refuses with `NO_HANDLER` and says so, until Home grows a device detail route.
    )

    /**
     * The apps that can show [type], **best first** — empty when nothing does.
     *
     * ⭐⭐ **A list rather than one id, because two first-party apps can legitimately claim one
     * type.** A `Place` is the first: Timeline answers *"what happened here"* and Maps answers
     * *"where is it"*, and both are right. Ordering them and taking the first that is actually
     * installed is better than picking a winner — a user with only Maps still gets their place
     * opened, instead of being told nothing on this device can show it.
     *
     * ⭐ It is also the shape TT-4 needs. CO-D2 already says this table becomes the *fallback*
     * under the app's declared `handles` set and the user's primary-handler choice; a preference
     * order is what that choice re-orders. One id would have had to become a list anyway.
     *
     * ⚠ Preference, not capability: every candidate must have a real receiving route (§5f.2). A
     * fallback to an app that cannot show the row trades an honest refusal for an app that opens
     * and displays nothing.
     */
    fun handlersFor(type: String): List<String> = handlers[type].orEmpty()

    /**
     * The types the user could meaningfully be asked about — those with **more than one** candidate
     * (TT-4b).
     *
     * ⚠⚠ A "choice" between one option is not a choice, and rendering one would invite the user to
     * express a preference that can never change anything. Today this is `place` alone.
     *
     * ⚠ The surface must still filter by what is installed **here** before offering it: two
     * candidates of which one is absent is, on this device, also not a choice.
     */
    fun choosableTypes(): List<String> =
        handlers.entries.filter { it.value.size > 1 }.map { it.key }.sorted()

    /**
     * ⚠ Duplicated as a constant rather than depending on `:calendar-service-common` from `:core-
     * common`: the platform must not learn what a calendar is to route to one. The string is the
     * contract, and `OccurrenceId` on the calendar side is its other half — a test pins that they
     * agree, which is the cheap half of what a declared manifest will do properly at TT-4.
     */
    const val OCCURRENCE_TYPE = "tenshu/calendar/eventOccurrence"

    /**
     * ⚠ Duplicated for the same reason as [OCCURRENCE_TYPE] — the platform routes on the string and
     * must not learn what a file is to do it. `File.ID` is the other half; a test pins that they
     * agree.
     */
    const val FILE_TYPE = "tenshu/file"

    /** ⚠ Same deliberate duplication as the two above; each owning service pins its own half. */
    const val PLACE_TYPE = "tenshu/place/place"
    const val MOMENT_TYPE = "tenshu/timeline/moment"
    const val WORKOUT_TYPE = "tenshu/health/workout"

    /** ⚠ Named but NOT routed — see the table. Kept so the test can assert its absence by name. */
    const val DEVICE_TYPE = "tenshu/home/device/homeDevice"
}
