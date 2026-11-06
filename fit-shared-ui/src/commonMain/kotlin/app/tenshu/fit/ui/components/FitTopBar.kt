package app.tenshu.fit.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_back
import app.tenshu.ui.chrome.AppTopAppBar
import org.jetbrains.compose.resources.stringResource

/**
 * Fit's screen bar — [AppTopAppBar] with Fit's back chevron.
 *
 * Every Fit screen carries one of these (fit-app.md §18.15). Before that sweep Fit was the
 * repo's outlier: the five tab screens put a bare `Text` headline above a `verticalScroll` and
 * the pushed screens hand-rolled a `Row` of `IconButton` + `titleLarge` + `HorizontalDivider`.
 * Both were re-implementations of the M3 small top app bar that happened to miss the parts that
 * are not visible in a screenshot — the container colour, and the status-bar inset.
 *
 * ⚠ The **only** reason this wrapper exists rather than a direct `AppTopAppBar` call is the
 * chevron: `AppTopAppBar`'s generated back affordance is `Icons.AutoMirrored.Filled.ArrowBack`,
 * and Fit has always drawn [FitIcons.ChevronLeft]. Keeping it costs one `navigationIcon` lambda,
 * and keeping that lambda in one place is what stops five call sites from re-deriving it. If Fit
 * ever adopts the platform arrow, delete this file and call `AppTopAppBar` directly.
 *
 * ⚠⚠ The old hand-rolled bars drew a `HorizontalDivider` under themselves. That is deliberately
 * **not** carried over: M3 separates a bar from content scrolled beneath it with the container
 * colour (`Surface` → `SurfaceContainer`), which [scrollBehavior] drives. A permanent rule under
 * a bar that has not been scrolled is just a box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    AppTopAppBar(
        title = title,
        navigationIcon = onBack?.let { back ->
            {
                IconButton(onClick = back) {
                    Icon(FitIcons.ChevronLeft, contentDescription = stringResource(Res.string.fit_back))
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}
