package app.tenshu.calendar.ui

import app.tenshu.error.ErrorCopyKey
import app.tenshu.error.errorCopyKey
import org.tenshu.core.TenshuException

/**
 * Why a Calendar screen could not do what was asked, as a value rather than words.
 *
 * ⚠ Words are the renderer's: the Compose shell reads `calendar_problem_*` from its string
 * resources, so a German launch says it in German. [english] is the SwiftUI shell's text, kept as
 * the `errorMessage` / `draftError` / `SubscribeDraft.error` it always read.
 *
 * ⚠⚠ Never an error's own message (`docs/agent.md` *Errors: the proxy emits codes, the app writes
 * the copy*): a `TenshuException`'s `message` is its id and `TError.messageOrNull()` is
 * developer-only diagnostics. A typed failure travels as its [ErrorCopyKey]; anything else as
 * nothing but the [CalendarAction].
 */
sealed interface CalendarProblem {

    /** An operation failed. */
    data class Failed(
        val action: CalendarAction,
        /** The typed error's copy key, when the failure was one — rendered from the app's catalogue. */
        val errorKey: ErrorCopyKey? = null,
    ) : CalendarProblem

    /** A calendar draft with a blank name. */
    data object NameMissing : CalendarProblem

    /** A calendar draft named like another calendar. */
    data class NameTaken(val name: String) : CalendarProblem

    /** The subscribe dialog with no address. */
    data object AddressMissing : CalendarProblem

    /** An address that is not `https://` or `webcal://`. */
    data object AddressNotSupported : CalendarProblem

    companion object {
        /** [action] failed with [thrown]. */
        fun failure(action: CalendarAction, thrown: Throwable): Failed =
            Failed(action, (thrown as? TenshuException)?.error?.errorCopyKey())
    }
}

/** What was being done when a [CalendarProblem.Failed] happened. */
enum class CalendarAction { LOAD_CALENDARS, SAVE, DELETE, SUBSCRIBE, UNSUBSCRIBE, STOP_SYNCING }

/**
 * The SwiftUI shell's words for [this].
 *
 * ⚠ The action's words alone for a failure: the error's own message used to be shown here, and for
 * a [TenshuException] that was its id (`tenshu/result/error/generic`).
 */
fun CalendarProblem.english(): String = when (this) {
    is CalendarProblem.Failed -> when (action) {
        CalendarAction.LOAD_CALENDARS -> "Could not load calendars"
        CalendarAction.SAVE -> "Could not save"
        CalendarAction.DELETE -> "Could not delete"
        CalendarAction.SUBSCRIBE -> "Could not subscribe"
        CalendarAction.UNSUBSCRIBE -> "Could not unsubscribe"
        CalendarAction.STOP_SYNCING -> "Could not stop syncing"
    }
    CalendarProblem.NameMissing -> "Give the calendar a name"
    is CalendarProblem.NameTaken -> "You already have a calendar called \"$name\""
    CalendarProblem.AddressMissing -> "Paste a calendar address"
    CalendarProblem.AddressNotSupported -> "Only https:// and webcal:// addresses can be subscribed to"
}
