package app.tenshu.fit.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bloodtype
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.MedicalRecordKind
import app.tenshu.fit.model.Source

/**
 * Fit's glyph set (Ph4, docs/proposals/fit-app.md D10) — material-icons-extended
 * outlined stand-ins mapped 1:1 to the Lucide names in fit-agent.md §2; the Lucide
 * swap is a deferred affordance (same as Timeline).
 */
object FitIcons {
    val Home: ImageVector get() = Icons.Outlined.Home
    val History: ImageVector get() = Icons.Outlined.History
    val Browse: ImageVector get() = Icons.Outlined.GridView
    val Profile: ImageVector get() = Icons.Outlined.Person
    val Record: ImageVector get() = Icons.Outlined.RadioButtonChecked

    val Heart: ImageVector get() = Icons.Outlined.MonitorHeart
    val Flame: ImageVector get() = Icons.Outlined.LocalFireDepartment
    val Distance: ImageVector get() = Icons.Outlined.Straighten
    val Route: ImageVector get() = Icons.Outlined.Route
    val Clock: ImageVector get() = Icons.Outlined.Schedule
    val Gps: ImageVector get() = Icons.Outlined.MyLocation
    val Bluetooth: ImageVector get() = Icons.Outlined.Bluetooth
    val Steps: ImageVector get() = Icons.AutoMirrored.Outlined.DirectionsWalk
    val Play: ImageVector get() = Icons.Outlined.PlayArrow
    val Pause: ImageVector get() = Icons.Outlined.Pause
    val Stop: ImageVector get() = Icons.Outlined.Stop
    val Check: ImageVector get() = Icons.Outlined.Check
    val Lock: ImageVector get() = Icons.Outlined.Lock
    val Shield: ImageVector get() = Icons.Outlined.VerifiedUser
    val TrendingUp: ImageVector get() = Icons.AutoMirrored.Outlined.TrendingUp
    val TrendingDown: ImageVector get() = Icons.AutoMirrored.Outlined.TrendingDown
    val File: ImageVector get() = Icons.AutoMirrored.Outlined.InsertDriveFile
    val Info: ImageVector get() = Icons.Outlined.Info
    val Warning: ImageVector get() = Icons.Outlined.WarningAmber

    val ChevronRight: ImageVector get() = Icons.AutoMirrored.Outlined.KeyboardArrowRight
    val ChevronLeft: ImageVector get() = Icons.AutoMirrored.Outlined.KeyboardArrowLeft
    val ChevronDown: ImageVector get() = Icons.Outlined.KeyboardArrowDown
    val Plus: ImageVector get() = Icons.Outlined.Add

    /** The stepper's decrement half (FP-3) — Material 3 ships no stepper control. */
    val Minus: ImageVector get() = Icons.Outlined.Remove
    val More: ImageVector get() = Icons.Outlined.MoreVert
    val ArrowUp: ImageVector get() = Icons.Outlined.ArrowUpward
    val ArrowDown: ImageVector get() = Icons.Outlined.ArrowDownward

    fun activity(type: ActivityType): ImageVector = when (type) {
        ActivityType.Running -> Icons.AutoMirrored.Outlined.DirectionsRun
        ActivityType.Walking -> Icons.AutoMirrored.Outlined.DirectionsWalk
        ActivityType.Cycling -> Icons.AutoMirrored.Outlined.DirectionsBike
        ActivityType.Strength -> Icons.Outlined.FitnessCenter
        ActivityType.Other -> Icons.Outlined.Bolt
    }

    fun source(source: Source): ImageVector = when (source) {
        Source.Recorded -> Icons.Outlined.RadioButtonChecked
        Source.HealthConnect -> Icons.Outlined.MonitorHeart
        Source.AppleHealth -> Icons.Outlined.MonitorHeart
        Source.File -> Icons.AutoMirrored.Outlined.InsertDriveFile
        Source.Manual -> Icons.Outlined.Person
        // Auto-detected from a Timeline movement trace — the walk you never pressed start on.
        Source.Detected -> Icons.Outlined.Route
    }

    /**
     * A *Data sources* row's glyph (`fit-data-sources-restructure.md` §4).
     *
     * ⚠ Deliberately separate from [source], which maps workout **provenance**
     * (`app.tenshu.fit.model.Source`). The two vocabularies answer different questions — "where
     * did this row come from" vs "what is feeding this realm" — and collapsing them is what the
     * whole restructure undid.
     */
    fun dataSource(kind: DataSourceKind): ImageVector = when (kind) {
        // Fit's own recordings: the same glyph provenance uses for `Source.Recorded`, because it
        // is literally the same data seen from the other end.
        DataSourceKind.TenshuHealth -> Icons.Outlined.RadioButtonChecked
        DataSourceKind.HealthConnect, DataSourceKind.AppleHealth -> Icons.Outlined.MonitorHeart
    }

    /** The batch importer — an archive, not a connection. */
    val Archive: ImageVector get() = Icons.AutoMirrored.Outlined.InsertDriveFile

    fun category(id: CategoryId): ImageVector = when (id) {
        CategoryId.Activity -> Icons.Outlined.Bolt
        CategoryId.Body -> Icons.Outlined.Straighten
        CategoryId.Vitals -> Icons.Outlined.MonitorHeart
        CategoryId.Sleep -> Icons.Outlined.Bedtime
        CategoryId.Nutrition -> Icons.Outlined.Restaurant
        CategoryId.Cycle -> Icons.Outlined.WaterDrop
        CategoryId.Medical -> Icons.Outlined.Medication
    }

    val Vaccine: ImageVector get() = Icons.Outlined.Vaccines
    val Allergy: ImageVector get() = Icons.Outlined.Bloodtype

    fun importSource(source: ImportSource): ImageVector = when (source) {
        ImportSource.HealthConnect -> Icons.Outlined.MonitorHeart
        ImportSource.AppleHealth -> Icons.Outlined.MonitorHeart
        ImportSource.ImportFile -> Icons.AutoMirrored.Outlined.InsertDriveFile
        ImportSource.StartFresh -> Icons.Outlined.RadioButtonChecked
    }

    fun medical(kind: MedicalRecordKind): ImageVector = when (kind) {
        MedicalRecordKind.Medication -> Icons.Outlined.Medication
        MedicalRecordKind.Immunization -> Icons.Outlined.Vaccines
        MedicalRecordKind.Allergy -> Icons.Outlined.Warning
        MedicalRecordKind.Condition -> Icons.Outlined.MedicalServices
    }

    /** The onboarding per-category consent icons reuse the Browse category glyphs. */
    fun importCategory(id: CategoryId): ImageVector = category(id)
}
