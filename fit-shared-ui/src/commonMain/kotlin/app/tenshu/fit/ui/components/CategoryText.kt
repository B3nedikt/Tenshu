package app.tenshu.fit.ui.components

import androidx.compose.runtime.Composable
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.CategoryTeaser
import app.tenshu.fit.model.FitFormat
import app.tenshu.fit.model.FlowLevel
import app.tenshu.fit.model.LastUpdated
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_category_activity
import app.tenshu.fit.shared.resources.fit_category_body
import app.tenshu.fit.shared.resources.fit_category_cycle
import app.tenshu.fit.shared.resources.fit_category_medical
import app.tenshu.fit.shared.resources.fit_category_nutrition
import app.tenshu.fit.shared.resources.fit_category_sleep
import app.tenshu.fit.shared.resources.fit_category_vitals
import app.tenshu.fit.shared.resources.fit_teaser_active_minutes
import app.tenshu.fit.shared.resources.fit_teaser_energy
import app.tenshu.fit.shared.resources.fit_flow_heavy
import app.tenshu.fit.shared.resources.fit_flow_light
import app.tenshu.fit.shared.resources.fit_flow_medium
import app.tenshu.fit.shared.resources.fit_flow_unspecified
import app.tenshu.fit.shared.resources.fit_teaser_cycle_day
import app.tenshu.fit.shared.resources.fit_teaser_no_entries
import app.tenshu.fit.shared.resources.fit_teaser_not_set_up
import app.tenshu.fit.shared.resources.fit_teaser_records
import app.tenshu.fit.shared.resources.fit_teaser_resting_hr
import app.tenshu.fit.shared.resources.fit_teaser_blood_oxygen
import app.tenshu.fit.shared.resources.fit_teaser_respiratory_rate
import app.tenshu.fit.shared.resources.fit_teaser_sleep
import app.tenshu.fit.shared.resources.fit_teaser_weight
import app.tenshu.fit.shared.resources.fit_updated_days_ago
import app.tenshu.fit.shared.resources.fit_updated_one_day_ago
import app.tenshu.fit.shared.resources.fit_updated_imported
import app.tenshu.fit.shared.resources.fit_updated_prefix
import app.tenshu.fit.shared.resources.fit_updated_today
import org.jetbrains.compose.resources.stringResource

/** One-decimal number without a locale format dependency (weights, etc.). */
fun oneDecimal(value: Double): String {
    val scaled = kotlin.math.round(value * 10).toInt()
    return "${scaled / 10}.${kotlin.math.abs(scaled % 10)}"
}

@Composable
fun categoryName(id: CategoryId): String = stringResource(
    when (id) {
        CategoryId.Activity -> Res.string.fit_category_activity
        CategoryId.Body -> Res.string.fit_category_body
        CategoryId.Vitals -> Res.string.fit_category_vitals
        CategoryId.Sleep -> Res.string.fit_category_sleep
        CategoryId.Nutrition -> Res.string.fit_category_nutrition
        CategoryId.Cycle -> Res.string.fit_category_cycle
        CategoryId.Medical -> Res.string.fit_category_medical
    },
)

/** Menstrual flow level → localized label (§12 B3b). */
@Composable
fun flowLabel(flow: FlowLevel): String = stringResource(
    when (flow) {
        FlowLevel.Light -> Res.string.fit_flow_light
        FlowLevel.Medium -> Res.string.fit_flow_medium
        FlowLevel.Heavy -> Res.string.fit_flow_heavy
        FlowLevel.Unspecified -> Res.string.fit_flow_unspecified
    },
)

@Composable
fun teaserText(teaser: CategoryTeaser): String = when (teaser) {
    is CategoryTeaser.ActiveMinutesToday -> stringResource(Res.string.fit_teaser_active_minutes, teaser.minutes)
    is CategoryTeaser.Weight -> stringResource(Res.string.fit_teaser_weight, oneDecimal(teaser.kg))
    is CategoryTeaser.RestingHeartRate -> stringResource(Res.string.fit_teaser_resting_hr, teaser.bpm)
    is CategoryTeaser.BloodOxygen -> stringResource(Res.string.fit_teaser_blood_oxygen, teaser.percent)
    is CategoryTeaser.RespiratoryRate -> stringResource(Res.string.fit_teaser_respiratory_rate, oneDecimal(teaser.perMinute))
    is CategoryTeaser.SleepDuration -> stringResource(Res.string.fit_teaser_sleep, durationLong(teaser.minutes * 60))
    is CategoryTeaser.Energy -> stringResource(Res.string.fit_teaser_energy, groupThousands(FitFormat.kjToKcal(teaser.kj)))
    is CategoryTeaser.CycleDay -> stringResource(Res.string.fit_teaser_cycle_day, teaser.day)
    is CategoryTeaser.RecordCount -> stringResource(Res.string.fit_teaser_records, teaser.count)
    CategoryTeaser.NoEntries -> stringResource(Res.string.fit_teaser_no_entries)
    CategoryTeaser.NotSetUp -> stringResource(Res.string.fit_teaser_not_set_up)
}

/** "Updated today" / "Updated 3 days ago" / "Updated Imported" / "Not set up". */
@Composable
fun updatedText(updated: LastUpdated): String = when (updated) {
    LastUpdated.Today -> stringResource(Res.string.fit_updated_prefix, stringResource(Res.string.fit_updated_today))
    is LastUpdated.DaysAgo -> stringResource(
        Res.string.fit_updated_prefix,
        // ⚠ One day is singular ("1 day ago"), never "1 days ago".
        if (updated.days == 1) {
            stringResource(Res.string.fit_updated_one_day_ago)
        } else {
            stringResource(Res.string.fit_updated_days_ago, updated.days)
        },
    )
    LastUpdated.Imported -> stringResource(Res.string.fit_updated_prefix, stringResource(Res.string.fit_updated_imported))
    LastUpdated.Never -> stringResource(Res.string.fit_teaser_not_set_up)
}
