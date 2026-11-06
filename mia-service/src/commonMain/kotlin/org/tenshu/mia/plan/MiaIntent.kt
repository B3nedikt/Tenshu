package org.tenshu.mia.plan

/**
 * The questions Mia can be asked, as a **closed set a model may choose from** (MQ-5, §16).
 *
 * ⚠⚠ **Reads only, and the two write intents are deliberately absent.** `CreateEvent` and
 * `ControlDevice` exist and are reachable — by the deterministic grammar, whose trigger is a
 * *verb* and is therefore auditable to the word (§15.2, §10.3). Handing that judgement to a model
 * is a different decision with a different blast radius: a misread read produces a wrong answer the
 * user sees and ignores, while a misread write produces a row that syncs or a light that changes.
 * ⭐ It is also cheap to keep: the candidate list is built here, so a model is never *shown* a
 * write, and `ClassifyIntentImplementation` refuses any id outside the list it was given.
 *
 * ⚠ [description] is the only part a model reads, and it is written for that reader — plain, one
 * clause, naming the noun a user would say. It is not user-facing copy and not localised; when the
 * planner is localised (MQ-D4's standing bill) these become per-language prompts.
 *
 * ⚠ [id] is a wire value: it crosses to `:inference-service` as an `IntentCandidate` and comes back
 * as an `IntentChoice`, so renaming one silently unteaches a model that was fine-tuned or prompted
 * against it. Change the description freely; change the id like a schema field.
 */
enum class MiaIntent(val id: String, val description: String) {
    FIND_PHOTOS(
        "findPhotos",
        "photos or pictures the user took, from a period of time",
    ),
    FIND_FILES_NAMED(
        "findFilesNamed",
        "files or documents whose name contains a word",
    ),
    FIND_PLACES(
        "findPlaces",
        "a place, cafe, restaurant or shop the user has been to, by name",
    ),
    FIND_PHOTOS_AT_PLACE(
        "findPhotosAtPlace",
        "photos taken at or near a named place or city",
    ),
    FIND_PHOTOS_OF_LABEL(
        "findPhotosOfLabel",
        "photos containing a thing, animal or object",
    ),
    FIND_PHOTOS_LIKE(
        "findPhotosLike",
        "photos that look like a described scene",
    ),
    RECALL_MOMENTS(
        "recallMoments",
        "what the user did, where they went, during a period of time",
    ),
    RECALL_AT_PLACE(
        "recallAtPlace",
        "when the user last visited a named place",
    ),
    FIND_WORKOUTS(
        "findWorkouts",
        "runs, rides, swims, walks, hikes and other workouts or training",
    ),
    FIND_DEVICES(
        "findDevices",
        "the state of smart home devices such as lights, plugs and sensors",
    ),
    QUERY_CALENDAR(
        "queryCalendar",
        "events, meetings and appointments on the calendar for a day or week",
    );

    companion object {
        /** The candidate set offered to a model — every entry, since writes are not entries. */
        val selectable: List<MiaIntent> get() = entries

        fun byId(id: String): MiaIntent? = entries.firstOrNull { it.id == id }
    }
}
