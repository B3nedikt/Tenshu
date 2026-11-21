package app.tenshu.calendar.ui.i18n

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * [one] for exactly 1, else [other] filled with [n] — the repo's one-vs-other keys rather than
 * `<plurals>` (docs/agent.md). ⚠ [one] takes no argument; its "1" is part of the sentence.
 */
@Composable
fun countOf(n: Int, one: StringResource, other: StringResource): String =
    if (n == 1) stringResource(one) else stringResource(other, n)
