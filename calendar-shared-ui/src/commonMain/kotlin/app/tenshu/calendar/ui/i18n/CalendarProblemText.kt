package app.tenshu.calendar.ui.i18n

import androidx.compose.runtime.Composable
import app.tenshu.calendar.shared.resources.Res
import app.tenshu.calendar.shared.resources.calendar_problem_address_missing
import app.tenshu.calendar.shared.resources.calendar_problem_address_not_supported
import app.tenshu.calendar.shared.resources.calendar_problem_delete
import app.tenshu.calendar.shared.resources.calendar_problem_load_calendars
import app.tenshu.calendar.shared.resources.calendar_problem_name_missing
import app.tenshu.calendar.shared.resources.calendar_problem_name_taken
import app.tenshu.calendar.shared.resources.calendar_problem_save
import app.tenshu.calendar.shared.resources.calendar_problem_stop_syncing
import app.tenshu.calendar.shared.resources.calendar_problem_subscribe
import app.tenshu.calendar.shared.resources.calendar_problem_unsubscribe
import app.tenshu.calendar.shared.resources.calendar_problem_with_detail
import app.tenshu.calendar.ui.CalendarAction
import app.tenshu.calendar.ui.CalendarProblem
import app.tenshu.error.ErrorCopyKey
import app.tenshu.ui.error.label
import org.jetbrains.compose.resources.stringResource

/**
 * [this] in the app's language.
 *
 * ⚠ A failure says what failed in our words, then — when it was a typed error — that error's copy
 * from `:ui-core`'s catalogue ([ErrorCopyKey.label], whose miss is the generic sentence). ⚠⚠ Never
 * the error's own message: that is developer diagnostics (docs/agent.md).
 */
@Composable
fun CalendarProblem.text(): String = when (this) {
    is CalendarProblem.Failed -> {
        val action = stringResource(
            when (action) {
                CalendarAction.LOAD_CALENDARS -> Res.string.calendar_problem_load_calendars
                CalendarAction.SAVE -> Res.string.calendar_problem_save
                CalendarAction.DELETE -> Res.string.calendar_problem_delete
                CalendarAction.SUBSCRIBE -> Res.string.calendar_problem_subscribe
                CalendarAction.UNSUBSCRIBE -> Res.string.calendar_problem_unsubscribe
                CalendarAction.STOP_SYNCING -> Res.string.calendar_problem_stop_syncing
            },
        )
        errorKey?.let { stringResource(Res.string.calendar_problem_with_detail, action, it.label()) } ?: action
    }
    CalendarProblem.NameMissing -> stringResource(Res.string.calendar_problem_name_missing)
    is CalendarProblem.NameTaken -> stringResource(Res.string.calendar_problem_name_taken, name)
    CalendarProblem.AddressMissing -> stringResource(Res.string.calendar_problem_address_missing)
    CalendarProblem.AddressNotSupported -> stringResource(Res.string.calendar_problem_address_not_supported)
}
