package app.tenshu.calendar.android.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.tenshu.calendar.android.MainActivity
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.model.WidgetAgenda
import app.tenshu.calendar.model.WidgetAgendaBuilder
import app.tenshu.calendar.model.WidgetAgendaRow
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.context.GlobalContext

/**
 * The "what's next" home-screen widget (CA-7c, `calendar-app.md` §9).
 *
 * ⭐ The `MiaWidget` shape — Glance + `GlanceTheme` so it follows the launcher's
 * dynamic colours — with one structural difference: this one **reads data**, and
 * that read crosses IPC to Control.
 *
 * ⚠⚠ **Every decision about what to show lives in `WidgetAgendaBuilder`, not
 * here.** A Glance composable cannot be tested in this repo's environment, so the
 * widget draws an answer it did not compute. What is left in this file is
 * genuinely just layout, plus the one thing that cannot move: catching a failed
 * read and calling it [WidgetAgenda.NotConnected] rather than empty.
 */
class CalendarWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val agenda = loadAgenda()
        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(24.dp)
                        .clickable(actionStartActivity<MainActivity>())
                        .padding(16.dp),
                ) {
                    when (agenda) {
                        WidgetAgenda.Loading -> Note("Loading…")
                        // ⚠⚠ Not "Nothing today". A failed IPC read yields the same
                        // empty list a free day does, and saying the calendar is
                        // empty when the app cannot see it is the worse of the two
                        // lies — this one sends the user looking for lost events.
                        WidgetAgenda.NotConnected -> Note("Can't reach Tenshu Control")
                        WidgetAgenda.Empty -> Note("Nothing coming up")
                        is WidgetAgenda.Items -> agenda.rows.forEach { AgendaLine(it) }
                    }
                }
            }
        }
    }

    /**
     * ⚠⚠ The read is wrapped, and the catch is the whole reason this function
     * exists separately from the composable: Control may be un-provisioned, killed
     * by the OEM, or mid-restart, and any of those throws rather than returning
     * nothing.
     *
     * ⚠ Koin is read through `GlobalContext.getOrNull()` rather than injected: a
     * widget is instantiated by the framework, and on a cold start the launcher can
     * reach it before `CalendarApplication.onCreate` has run.
     */
    private suspend fun loadAgenda(): WidgetAgenda {
        val repository = GlobalContext.getOrNull()?.get<CalendarRepository>()
            ?: return WidgetAgenda.NotConnected
        val zone = TimeZone.currentSystemDefault()
        val now = Clock.System.now().toLocalDateTime(zone)
        return runCatching {
            WidgetAgendaBuilder.build(
                // ⚠ `includeHidden = false`: a calendar hidden on this device is
                // hidden on its home screen too, which is what "on this device"
                // has to mean for the setting to be worth anything.
                occurrences = repository.occurrences(
                    from = now.date,
                    to = LocalDatePlusDays(now.date, LOOK_AHEAD_DAYS),
                ),
                today = now.date,
                nowMinuteOfDay = now.hour * 60 + now.minute,
            )
        }.getOrElse { WidgetAgenda.NotConnected }
    }

    companion object {
        /** ⚠ Matches `WidgetAgendaBuilder`'s own horizon; a shorter window would starve it. */
        const val LOOK_AHEAD_DAYS = 7

        /**
         * Redraws every placed widget.
         *
         * ⚠⚠ Called from the app's lifecycle rather than from a scheduler, and that
         * is a real limitation rather than a choice: Calendar owns no background
         * scheduler — Control does — and a widget in Calendar's process cannot ask
         * Control to wake *it*. So the widget refreshes on the OS's own
         * `updatePeriodMillis` tick and whenever the user leaves the app. See the
         * proposal's CA-7c note on what that costs.
         */
        suspend fun refresh(context: Context) {
            runCatching { CalendarWidget().updateAll(context) }
        }
    }
}

@Suppress("FunctionName")
private fun LocalDatePlusDays(date: kotlinx.datetime.LocalDate, days: Int) =
    kotlinx.datetime.LocalDate.fromEpochDays(date.toEpochDays() + days)

@androidx.compose.runtime.Composable
private fun Note(text: String) {
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
        )
    }
}

@androidx.compose.runtime.Composable
private fun AgendaLine(row: WidgetAgendaRow) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // ⚠ An all-day event has no time, so the column carries the day marker
            // instead — otherwise a holiday and a 09:00 meeting look identical.
            text = when {
                row.time.isNotBlank() -> row.time
                row.tomorrowOrLater -> "→"
                else -> "all day"
            },
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
            ),
        )
        Spacer(GlanceModifier.width(10.dp))
        Text(
            text = row.title,
            maxLines = 1,
            style = TextStyle(
                // ⚠ A started event is dimmed, not hidden — see
                // `WidgetAgendaBuilder`, which is where that decision is made.
                color = if (row.started) {
                    GlanceTheme.colors.onSurfaceVariant
                } else {
                    GlanceTheme.colors.onSurface
                },
                fontSize = 14.sp,
                fontWeight = if (row.started) FontWeight.Normal else FontWeight.Medium,
            ),
        )
    }
}

class CalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalendarWidget()
}
