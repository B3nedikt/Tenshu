package app.tenshu.ui.layout

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * A navigation destination's label that **fits its item in every language** — the rail's fixed
 * 96 dp and the bottom bar's fifth of a phone.
 *
 * The ladder, in order of preference:
 * 1. the theme's size on one line;
 * 2. wrapped **at a space** onto [maxLines] centred lines (multi-word labels only);
 * 3. shrunk in 0.5 sp steps, down to [NavLabelMinFontSize], until the longest *word* fits;
 * 4. only then ellipsized.
 *
 * ⚠⚠ Wrapping alone is not enough, and neither is the stock [TextAutoSize.StepBased]. A single
 * word wider than the line — German "Einstellungen", "Aufzeichnen" — is broken *between letters*
 * by the line breaker, and StepBased counts that as fitting, so it keeps the full size and the
 * item clips the stray last letters: the store captures showed "Aufzeichne" (2026-11-20). Hence
 * [NavLabelAutoSize], which rejects any size whose layout breaks inside a word.
 *
 * All labels of one chrome pass the same [group], so they render at **one** size — the one the
 * longest needs. A shrunk label beside full-size neighbours reads as a mistake.
 *
 * The colour and style come from the item (`LocalContentColor` / `LocalTextStyle`, as Material's
 * `Text` reads them), so selection colours and the theme's label typography still apply.
 */
@Composable
internal fun NavLabel(text: String, maxLines: Int, group: NavLabelGroup) {
    val style = LocalTextStyle.current
    val maxSize = style.fontSize.takeIf { it != TextUnit.Unspecified } ?: 12.sp
    // A fresh key per call site, so two items with the same text still count separately.
    val key = remember { Any() }
    DisposableEffect(group, key) { onDispose { group.forget(key) } }
    val cap = group.shared
    // ⚠⚠ Keyed on the cap: `BasicText` does not re-run `autoSize` when only the instance changes
    // (it keeps the size it chose against the old one), so a smaller neighbour reporting in left
    // this label at its own size and the bar mixed sizes. `labelsInOneChrome_shareOneSize` pins it.
    key(cap) {
        BasicText(
            text = text,
            style = style.merge(color = LocalContentColor.current, textAlign = TextAlign.Center),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            autoSize = NavLabelAutoSize(
                minFontSize = NavLabelMinFontSize,
                maxFontSize = maxSize,
                cap = cap,
                group = group,
                key = key,
            ),
        )
    }
}

/**
 * The labels of one nav chrome. Each label records the largest size *it* fits at; every label
 * renders at the smallest of those.
 *
 * ⚠ A label's own fit is measured against the theme's size, never against the shared one, so the
 * record cannot feed back into itself: one extra layout pass after the last label reports, and it
 * is stable.
 */
@Stable
internal class NavLabelGroup {
    private val fits = mutableStateMapOf<Any, Float>()

    /** The size every label in the group renders at; `null` until one has measured. */
    val shared: Float? get() = fits.values.minOrNull()

    fun record(key: Any, size: Float) {
        if (fits[key] != size) fits[key] = size
    }

    fun forget(key: Any) {
        fits.remove(key)
    }
}

@Composable
internal fun rememberNavLabelGroup(): NavLabelGroup = remember { NavLabelGroup() }

/**
 * The floor [NavLabel] will shrink to before it ellipsizes. Material's smallest type-scale entry
 * is 11 sp; a step below it is still legible at a nav label's weight, and it is what lets
 * "Einstellungen" sit in a fifth of a 360 dp phone.
 */
internal val NavLabelMinFontSize = 9.sp

private val NavLabelStep = 0.5.sp

/**
 * Finds the largest font size, from [maxFontSize] down to [minFontSize], at which the label
 * neither ellipsizes nor breaks inside a word — [minFontSize] if none, letting the ellipsis show
 * as the last resort — records it in [group], and renders at the group's shared size.
 */
internal data class NavLabelAutoSize(
    val minFontSize: TextUnit,
    val maxFontSize: TextUnit,
    /** The group's shared size, in sp; `null` until a label has measured. */
    val cap: Float?,
    val group: NavLabelGroup,
    val key: Any,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(
        constraints: Constraints,
        text: AnnotatedString,
    ): TextUnit {
        val own = largestFit(constraints, text)
        group.record(key, own)
        return minOf(own, cap ?: own).sp
    }

    private fun TextAutoSizeLayoutScope.largestFit(
        constraints: Constraints,
        text: AnnotatedString,
    ): Float {
        var size = maxFontSize.value
        while (size > minFontSize.value) {
            if (fits(text.text, performLayout(constraints, text, size.sp))) return size
            size -= NavLabelStep.value
        }
        return minFontSize.value
    }
}

private fun fits(text: String, layout: TextLayoutResult): Boolean =
    !layout.hasVisualOverflow &&
        (0 until layout.lineCount).none { layout.isLineEllipsized(it) } &&
        !breaksInsideAWord(text, layout)

/** Whether any line ends between two letters of one word — the break that clips in a nav item. */
internal fun breaksInsideAWord(text: String, layout: TextLayoutResult): Boolean =
    (0 until layout.lineCount - 1).any { line ->
        val end = layout.getLineEnd(line)
        end in 1 until text.length && !text[end - 1].isWordBoundary() && !text[end].isWhitespace()
    }

private fun Char.isWordBoundary() = isWhitespace() || this == '-' || this == '/' || this == '­'
