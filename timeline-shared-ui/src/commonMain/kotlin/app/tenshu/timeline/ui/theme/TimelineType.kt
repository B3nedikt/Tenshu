package app.tenshu.timeline.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle

/**
 * The Timeline heading voice: the "Chapters" direction sets every display / title /
 * heading in an **italic serif** (day dates, moment titles, place & trip names, section
 * titles, the interlude line). This is the concept's whole voice — see
 * `docs/design/timeline/timeline-agent.md` §3.
 *
 * Everything resolves the serif family through this ONE accessor so the open brand
 * decision (keep an editorial serif vs. fall back to the M3 sans headline styles) is a
 * single-line swap. Do NOT hardcode a family (e.g. Georgia) at any call site.
 *
 * Decided in **ADR 0021** (docs/adr/0021-timeline-editorial-serif.md): Timeline keeps the
 * italic serif (Option A), scoped to this app (NOT added to `:ui-core`'s `AppTypography`).
 * v1 ships the platform-generic serif ([FontFamily.Serif] → Android Noto Serif / desktop
 * system serif / iOS Times-family, each with a real italic) as the interim; swapping in a
 * curated bundled face is a one-line change here — `FontFamily(Res.font.<editorial_italic>)`
 * — or set [displayFamily] to `FontFamily.Default` to fall back to M3 sans (Option B).
 */
object TimelineType {

    /** The single point where the serif family is chosen (ADR 0021). */
    val displayFamily: FontFamily get() = FontFamily.Serif

    private fun TextStyle.serif(): TextStyle =
        copy(fontFamily = displayFamily, fontStyle = FontStyle.Italic)

    /** Trip / place hero titles and the largest day dates. */
    val hero: TextStyle @Composable get() = MaterialTheme.typography.displaySmall.serif()

    /** Day-chapter date headings ("Sunday, May 24"). */
    val date: TextStyle @Composable get() = MaterialTheme.typography.headlineMedium.serif()

    /** Named-moment titles, place-row names, section titles. */
    val title: TextStyle @Composable get() = MaterialTheme.typography.titleLarge.serif()

    /** The interlude / quiet one-liners (smaller running serif). */
    val body: TextStyle @Composable get() = MaterialTheme.typography.titleMedium.serif()
}
