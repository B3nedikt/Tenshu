package app.tenshu.photos.ui.screens.albumdetail

import app.tenshu.photos.model.NamingOutcome

/**
 * Every sentence of a person's page (IN-6 F3b, F6a, F6b) — Compose's `AlbumDetailScreen` and the
 * SwiftUI person page say the same words (`ios-face-grouping.md` F7).
 *
 * ⚠⚠ Naming is ADDITIVE: it writes "this photograph shows X" and removes nothing, so on a named
 * card the action adds a SECOND person and is never labelled Rename — the data layer cannot rename
 * (a person-wide rename is F6c's, `photos-people-and-pets.md` §6a), and there is no clearing a name.
 */
object PersonPageCopy {
    const val UNNAMED: String = "Unnamed"
    const val ADD_A_NAME: String = "Add a name"
    const val FINISH_NAMING: String = "Finish naming"
    const val ADD_ANOTHER_PERSON: String = "Add another person"

    /** The page's naming action: finishing an interrupted one, naming nobody, or adding another person. */
    fun nameAction(unfinished: Boolean, titleBlank: Boolean): String = when {
        unfinished -> FINISH_NAMING
        titleBlank -> ADD_A_NAME
        else -> ADD_ANOTHER_PERSON
    }

    /** The naming dialog's title — [initial] is the interrupted name being finished, or empty. */
    fun dialogTitle(initial: String): String = if (initial.isBlank()) "Who is this?" else FINISH_NAMING

    const val NAME_FIELD: String = "Name"

    /**
     * ⚠⚠ Says what naming DOES before the user types: the rows sync to their other devices (A40),
     * and every other derived thing in Photos is device-local.
     */
    const val NAME_SYNCS: String = "This name is saved with these photos and appears on your other devices."
    const val SAVE: String = "Save"
    const val CANCEL: String = "Cancel"
    const val OK: String = "OK"

    /**
     * The longest name a person may be given. ⚠ Generous for any real name, and far below the
     * transport budget: the name is repeated in every call the write is split into.
     */
    const val NAME_MAX_LENGTH: Int = 200

    fun failureTitle(outcome: NamingOutcome): String =
        if (outcome == NamingOutcome.PartiallySaved) "Only some photos were named" else "That name wasn't saved"

    /**
     * ⚠⚠ Two instructions, so two sentences: naming the consent switch for a Control that is merely
     * not running sends the user to check a setting that is already correct. ⚠⚠ A partial save says
     * what IS true: the saved rows are durable and already on the user's other devices.
     */
    fun failureText(outcome: NamingOutcome): String = when (outcome) {
        NamingOutcome.NoConsent -> "People is switched off for this device, so the name could not be saved."
        NamingOutcome.PartiallySaved -> "Some of these photos were named before it stopped. Try again to name the rest."
        else -> "The name could not be saved. Please try again."
    }

    /**
     * IN-6 F6c name suggestions: *"Is this Anna?"* on an unnamed group. ⚠⚠ A question — the
     * face grouping thinks so, and nothing is named until Yes.
     */
    fun suggestionQuestion(name: String): String = "Is this $name?"
    const val SUGGESTION_YES: String = "Yes"
    const val SUGGESTION_NO: String = "No"

    /** ⚠ Says the Yes syncs, as [NAME_SYNCS] does for a typed name — a Yes is a naming. */
    const val SUGGESTION_EXPLAINED: String = "Saying yes names these photos on all your devices."
    const val SUGGESTION_NOT_SAVED: String = "That answer wasn't saved. Please try again."

    /**
     * A display fold (the confident level): photographs shown as [name]'s because this device's face
     * grouping says so, carrying no name. ⚠⚠ Says they are unconfirmed and local — a fold syncs
     * nothing, and the page must not read as though it had.
     */
    fun unconfirmed(count: Int, name: String): String =
        if (count == 1) "1 photo matched by face on this device — not yet named $name."
        else "$count photos matched by face on this device — not yet named $name."
    const val CONFIRM: String = "Confirm"
    fun notThem(name: String): String = "Not $name"

    /** Google Photos' "[face] +": narrow to photographs of both. */
    const val WITH: String = "With…"
    const val PICKER_TITLE: String = "Photos with…"
    const val NO_PHOTOS_TOGETHER: String = "No photos of both yet"

    fun name(title: String): String = title.ifBlank { UNNAMED }
    fun withChip(second: String): String = "With ${name(second)}"
    fun removeWith(second: String): String = "Remove ${name(second)}"
    fun both(first: String, second: String): String = "${name(first)} & ${name(second)}"
}
