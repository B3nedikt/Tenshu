package app.tenshu.photos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.tenshu.photos.shared.resources.Res
import app.tenshu.photos.shared.resources.photos_date_day
import app.tenshu.photos.shared.resources.photos_date_month
import app.tenshu.photos.shared.resources.photos_date_short
import app.tenshu.photos.shared.resources.photos_density_day
import app.tenshu.photos.shared.resources.photos_density_month
import app.tenshu.photos.shared.resources.photos_density_mosaic
import app.tenshu.photos.shared.resources.photos_density_year
import app.tenshu.photos.shared.resources.photos_months
import app.tenshu.photos.shared.resources.photos_months_short
import app.tenshu.photos.shared.resources.photos_weekdays_short
import app.tenshu.photos.time.DateWords
import app.tenshu.photos.ui.screens.feed.FeedDensity
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * The [DateWords] [app.tenshu.photos.time.PhotoDates] spells dates with, in the app's language.
 *
 * ⚠ The patterns are read raw and filled here, because the result is a plain function the
 * feed's headers and scrubber call outside composition.
 */
@Composable
fun rememberDateWords(): DateWords {
    val months = stringArrayResource(Res.array.photos_months)
    val shortMonths = stringArrayResource(Res.array.photos_months_short)
    val weekdays = stringArrayResource(Res.array.photos_weekdays_short)
    val day = stringResource(Res.string.photos_date_day)
    val month = stringResource(Res.string.photos_date_month)
    val short = stringResource(Res.string.photos_date_short)
    return remember(months, shortMonths, weekdays, day, month, short) {
        if (months.size != 12 || shortMonths.size != 12 || weekdays.size != 7) {
            DateWords.English
        } else {
            DateWords(
                months = months,
                shortMonths = shortMonths,
                shortWeekdays = weekdays,
                day = { d, m, y -> day.fill("%1\$d" to "$d", "%2\$s" to m, "%3\$d" to "$y") },
                month = { m, y -> month.fill("%1\$s" to m, "%2\$d" to "$y") },
                short = { d, m -> short.fill("%1\$d" to "$d", "%2\$s" to m) },
            )
        }
    }
}

private fun String.fill(vararg args: Pair<String, String>): String =
    args.fold(this) { text, (placeholder, value) -> text.replace(placeholder, value) }

/** The density button's spoken label — [FeedDensity.description], in the app's language. */
@Composable
fun FeedDensity.label(): String = stringResource(
    when (this) {
        FeedDensity.Mosaic -> Res.string.photos_density_mosaic
        FeedDensity.Day -> Res.string.photos_density_day
        FeedDensity.Month -> Res.string.photos_density_month
        FeedDensity.Year -> Res.string.photos_density_year
    },
)
