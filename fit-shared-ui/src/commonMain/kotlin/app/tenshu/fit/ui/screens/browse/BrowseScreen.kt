package app.tenshu.fit.ui.screens.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.CategorySummary
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_browse_import_hint
import app.tenshu.fit.shared.resources.fit_browse_unavailable
import app.tenshu.fit.shared.resources.fit_browse_unavailable_update
import app.tenshu.liststate.ListLoadPhase
import app.tenshu.ui.feedback.ErrorBanner
import app.tenshu.fit.shared.resources.fit_browse_import_title
import app.tenshu.fit.shared.resources.fit_browse_title
import app.tenshu.fit.ui.components.CategoryCard
import app.tenshu.fit.ui.components.FitIcons
import app.tenshu.fit.ui.components.FitTopBar
import org.jetbrains.compose.resources.stringResource

/**
 * Browse (docs/proposals/fit-app.md screen 9; §12) — seven category cards in a 2-column grid;
 * empty categories dim to "Not set up". A prompt to connect a source shows while a category a data
 * source can fill is still empty ([hasImportableGap]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    viewModel: BrowseViewModel,
    onOpenCategory: (CategoryId) -> Unit,
    onImport: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    // Whether the unavailable banner may claim to be retrying (§8.6) — same rule as Home.
    val waitingWillFix by viewModel.waitingWillFix.collectAsState()
    // ⚠ Gated on LOADED. The prompt asks the user to go connect a data source because categories
    // are empty — a conclusion only an *answered* read can support. Before this it fired on the
    // first frame of every launch (nothing has arrived yet ⇒ every category "empty") and on any
    // failed read, telling people to reconnect a source that was already connected.
    val hasImportableGap = state.categoriesLoad == ListLoadPhase.LOADED && hasImportableGap(state.categories)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FitTopBar(
                title = stringResource(Res.string.fit_browse_title),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A read that broke after answering keeps its cards and adds the banner; one that
            // broke before answering has no cards, and the banner is all there is. Either way the
            // screen never silently presents "Not set up" as the answer.
            if (state.categoriesLoad == ListLoadPhase.FAILED) {
                // ⚠⚠ Same honesty rule as Home (§8.6): "Still trying…" is true of a transport
                // outage and false of a schema refusal, which waiting never resolves.
                ErrorBanner(
                    text = stringResource(
                        if (waitingWillFix) Res.string.fit_browse_unavailable
                        else Res.string.fit_browse_unavailable_update
                    )
                )
            }
            if (hasImportableGap) ImportPrompt(onImport)
            state.categories.chunked(2).forEach { rowCategories ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowCategories.forEach { summary ->
                        CategoryCard(
                            summary = summary,
                            onClick = { onOpenCategory(summary.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowCategories.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Whether a category the prompt can do something about is still empty: a store-backed one
 * (anything but Nutrition/Cycle, which have no store) that a data source can fill.
 *
 * ⚠ Not Medical — the Android twin of the iOS fix (`fit-ios/Sources/BrowseView.swift`). No source
 * here fills it: medical records are not a Health Connect data type (`HealthConnectImporter`
 * imports none) and no file import yields them, so counting it kept "Connect a data source — Import
 * your activity, sleep and body data" up for good, beside a connected Health Connect whose
 * activity, sleep and body cards were all full.
 */
internal fun hasImportableGap(categories: List<CategorySummary>): Boolean = categories.any {
    it.isEmpty && it.id != CategoryId.Nutrition && it.id != CategoryId.Cycle && it.id != CategoryId.Medical
}

/** Nudges the user to Profile → Data sources to fill the still-empty store-backed categories (§12 B2b). */
@Composable
private fun ImportPrompt(onImport: () -> Unit) {
    Surface(
        onClick = onImport,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FitIcons.category(CategoryId.Activity),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.fit_browse_import_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(Res.string.fit_browse_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Icon(
                FitIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
