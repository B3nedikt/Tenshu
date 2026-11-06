package app.tenshu.timeline.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DirectionsTransit
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.ui.graphics.vector.ImageVector
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.MovementModes

/**
 * Timeline glyphs (Ph4, docs/proposals/timeline-app.md §2). The design's base set is
 * **Lucide** (stroke 1.75 @ 24). v1 maps to `material-icons-extended` approximations as a
 * stand-in; a curated Lucide set replaces these under the module's icons later (§2).
 *
 * TODO(icons): swap for the real Lucide glyph set — timeline-agent.md §2.
 */
object TimelineIcons {

    fun forMoment(kind: MomentKind): ImageVector = when (kind) {
        MomentKind.Activity -> Icons.AutoMirrored.Outlined.DirectionsRun
        MomentKind.Visit -> Icons.Outlined.Place
        MomentKind.Event -> Icons.Outlined.MusicNote
        MomentKind.Quiet -> Icons.Outlined.Home
        // A movement without a resolved mode still needs a glyph; walking is the safe default
        // (it is by far the commonest segment and reads as "some movement" rather than a claim).
        MomentKind.Movement -> Icons.AutoMirrored.Outlined.DirectionsWalk
    }

    /**
     * Per-mode movement glyph. Falls back to the walking figure for an unknown mode — the mode is a
     * raw wire string, so a newer peer can send one this build has never seen.
     */
    fun forMovement(mode: String?): ImageVector = when (mode) {
        MovementModes.RUN -> Icons.AutoMirrored.Outlined.DirectionsRun
        MovementModes.CYCLE -> Icons.AutoMirrored.Outlined.DirectionsBike
        MovementModes.DRIVE -> Icons.Outlined.DirectionsCar
        MovementModes.TRANSIT -> Icons.Outlined.DirectionsTransit
        MovementModes.FLIGHT -> Icons.Outlined.Flight
        else -> Icons.AutoMirrored.Outlined.DirectionsWalk
    }

    /** Activity-rollup icon by the mock's `icon` key (run / coffee / fork / music / pin / book). */
    fun forActivity(key: String): ImageVector = when (key) {
        "run" -> Icons.AutoMirrored.Outlined.DirectionsRun
        "coffee" -> Icons.Outlined.LocalCafe
        "fork" -> Icons.Outlined.Restaurant
        "music" -> Icons.Outlined.MusicNote
        "pin" -> Icons.Outlined.Place
        "book" -> Icons.AutoMirrored.Outlined.MenuBook
        else -> Icons.Outlined.Place
    }
}
