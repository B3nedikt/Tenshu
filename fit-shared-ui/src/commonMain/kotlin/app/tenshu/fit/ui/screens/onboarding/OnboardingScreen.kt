package app.tenshu.fit.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import app.tenshu.fit.model.Units
import app.tenshu.fit.ui.screens.onboarding.AboutYouField
import app.tenshu.fit.ui.screens.profile.BirthDateDialog
import app.tenshu.fit.ui.screens.profile.HeightDialog
import app.tenshu.fit.ui.screens.profile.SexDialog
import app.tenshu.fit.ui.screens.profile.WeightDialog
import app.tenshu.fit.ui.screens.profile.sexLabel
import app.tenshu.fit.ui.components.dateWithYear
import app.tenshu.fit.shared.resources.fit_about_you_add
import app.tenshu.fit.shared.resources.fit_about_you_body
import app.tenshu.fit.shared.resources.fit_about_you_continue
import app.tenshu.fit.shared.resources.fit_about_you_title
import app.tenshu.fit.shared.resources.fit_profile_birth_date
import app.tenshu.fit.shared.resources.fit_profile_height
import app.tenshu.fit.shared.resources.fit_profile_sex
import app.tenshu.fit.shared.resources.fit_profile_weight
import kotlinx.datetime.LocalDate
import org.tenshu.preference.BiologicalSex

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportCategory
import app.tenshu.fit.model.ImportProgress
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_back
import app.tenshu.fit.shared.resources.fit_onboarding_done
import app.tenshu.fit.shared.resources.fit_onboarding_estimated_count
import app.tenshu.fit.shared.resources.fit_onboarding_footer
import app.tenshu.fit.shared.resources.fit_onboarding_history_allow
import app.tenshu.fit.shared.resources.fit_onboarding_history_body
import app.tenshu.fit.shared.resources.fit_onboarding_history_later
import app.tenshu.fit.shared.resources.fit_onboarding_history_skip
import app.tenshu.fit.shared.resources.fit_onboarding_history_title
import app.tenshu.fit.shared.resources.fit_onboarding_empty_category
import app.tenshu.fit.shared.resources.fit_onboarding_import_selected
import app.tenshu.fit.shared.resources.fit_onboarding_continue
import app.tenshu.fit.shared.resources.fit_onboarding_import_complete_note
import app.tenshu.fit.shared.resources.fit_onboarding_import_complete_title
import app.tenshu.fit.shared.resources.fit_onboarding_importing_note
import app.tenshu.fit.shared.resources.fit_onboarding_importing_title
import app.tenshu.fit.shared.resources.fit_onboarding_permissions_subtitle
import app.tenshu.fit.shared.resources.fit_onboarding_permissions_title
import app.tenshu.fit.shared.resources.fit_onboarding_progress_counts
import app.tenshu.fit.shared.resources.fit_onboarding_promise
import app.tenshu.fit.shared.resources.fit_onboarding_queued
import app.tenshu.fit.shared.resources.fit_onboarding_source_apple_health
import app.tenshu.fit.shared.resources.fit_onboarding_source_apple_health_hint
import app.tenshu.fit.shared.resources.fit_onboarding_source_available
import app.tenshu.fit.shared.resources.fit_onboarding_source_file
import app.tenshu.fit.shared.resources.fit_onboarding_source_file_hint
import app.tenshu.fit.shared.resources.fit_onboarding_source_fresh
import app.tenshu.fit.shared.resources.fit_onboarding_source_fresh_hint
import app.tenshu.fit.shared.resources.fit_onboarding_source_health_connect
import app.tenshu.fit.shared.resources.fit_onboarding_source_health_connect_hint
import app.tenshu.fit.shared.resources.fit_onboarding_start_now
import app.tenshu.fit.shared.resources.fit_onboarding_title
import app.tenshu.fit.ui.components.FitIcons
import app.tenshu.fit.ui.components.categoryName
import app.tenshu.fit.ui.components.groupThousands
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Onboarding (docs/proposals/fit-app.md screens 1–3) — the full-screen gate above the main
 * shell (D5). One VM drives all three steps; this renders the current one. De-Googled is a
 * first-class path: Health Connect appears only when [ImportSource.HealthConnect] is in the
 * detected list, and "Start fresh" completes immediately (fit-agent.md §0.3).
 */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel) {
    val state by viewModel.uiState.collectAsState()
    when (state.step) {
        OnboardingStep.Welcome -> WelcomeStep(
            sources = state.sources,
            onChoose = viewModel::chooseSource,
        )
        OnboardingStep.Permissions -> PermissionsStep(
            categories = state.categories,
            selection = state.selection,
            onToggle = viewModel::toggleCategory,
            onBack = viewModel::back,
            onImport = viewModel::importSelected,
        )
        OnboardingStep.DeepHistory -> DeepHistoryStep(
            onAllow = viewModel::allowDeepHistory,
            onSkip = viewModel::skipDeepHistory,
            onBack = viewModel::back,
        )
        OnboardingStep.Importing -> ImportingStep(
            progress = state.progress.orEmpty(),
            fraction = state.overallFraction,
            complete = state.importComplete,
            onStart = viewModel::enterApp,
        )

        OnboardingStep.AboutYou -> AboutYouStep(
            missing = state.missingAboutYouFields,
            units = state.profile?.units ?: Units.Metric,
            onSaveBirthDate = viewModel::saveBirthDate,
            onSaveSex = viewModel::saveSex,
            onSaveHeight = viewModel::saveHeight,
            onSaveWeight = viewModel::saveWeight,
            onDone = viewModel::finishAboutYou,
        )
    }
}

/**
 * "A bit about you" (`fit-app.md` §18.6) — the values the app cannot compute without and the
 * platform could not supply.
 *
 * Every field is optional and the primary action is always enabled: a skipped answer costs the
 * user calorie accuracy and nothing else, so nothing here blocks entry to the app. Each field is
 * present only while [missing] says it is still unknown, so on a platform whose import filled them
 * in this screen is never reached at all.
 */
@Composable
private fun AboutYouStep(
    missing: Set<AboutYouField>,
    units: Units,
    onSaveBirthDate: (LocalDate?) -> Unit,
    onSaveSex: (BiologicalSex?) -> Unit,
    onSaveHeight: (Int) -> Unit,
    onSaveWeight: (Double) -> Unit,
    onDone: () -> Unit,
) {
    var editing by remember { mutableStateOf<AboutYouField?>(null) }
    var birthDate by remember { mutableStateOf<LocalDate?>(null) }
    var sex by remember { mutableStateOf<BiologicalSex?>(null) }
    var heightCm by remember { mutableStateOf<Int?>(null) }
    var weightKg by remember { mutableStateOf<Double?>(null) }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(Res.string.fit_about_you_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(Res.string.fit_about_you_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (AboutYouField.BirthDate in missing) {
                AboutYouRow(
                    label = stringResource(Res.string.fit_profile_birth_date),
                    value = birthDate?.let { dateWithYear(it) },
                    onClick = { editing = AboutYouField.BirthDate },
                )
            }
            if (AboutYouField.Sex in missing) {
                AboutYouRow(
                    label = stringResource(Res.string.fit_profile_sex),
                    value = sex?.let { sexLabel(it) },
                    onClick = { editing = AboutYouField.Sex },
                )
            }
            if (AboutYouField.Height in missing) {
                AboutYouRow(
                    label = stringResource(Res.string.fit_profile_height),
                    value = heightCm?.let { "$it cm" },
                    onClick = { editing = AboutYouField.Height },
                )
            }
            if (AboutYouField.Weight in missing) {
                AboutYouRow(
                    label = stringResource(Res.string.fit_profile_weight),
                    value = weightKg?.let { "$it kg" },
                    onClick = { editing = AboutYouField.Weight },
                )
            }

            Spacer(Modifier.weight(1f))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.fit_about_you_continue))
            }
        }
    }

    val dismiss = { editing = null }
    when (editing) {
        AboutYouField.BirthDate -> BirthDateDialog(birthDate, dismiss) {
            birthDate = it; onSaveBirthDate(it); dismiss()
        }
        AboutYouField.Sex -> SexDialog(sex, dismiss) { sex = it; onSaveSex(it) }
        AboutYouField.Height -> HeightDialog(heightCm, units, dismiss) { heightCm = it; onSaveHeight(it) }
        AboutYouField.Weight -> WeightDialog(weightKg, units, dismiss, onSave = { weightKg = it; onSaveWeight(it) })
        null -> Unit
    }
}

/** A "label … value or Add" row — the entry point to one About-you field's dialog. */
@Composable
private fun AboutYouRow(label: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value ?: stringResource(Res.string.fit_about_you_add),
            style = MaterialTheme.typography.bodyMedium,
            color = if (value == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WelcomeStep(sources: List<ImportSource>, onChoose: (ImportSource) -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 32.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(60.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        FitIcons.Heart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(Res.string.fit_onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.fit_onboarding_promise),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEach { source ->
                    SourceRow(source = source, onClick = { onChoose(source) })
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    FitIcons.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp).padding(top = 1.dp),
                )
                Text(
                    text = stringResource(Res.string.fit_onboarding_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun sourceTitle(source: ImportSource): StringResource = when (source) {
    ImportSource.HealthConnect -> Res.string.fit_onboarding_source_health_connect
    ImportSource.AppleHealth -> Res.string.fit_onboarding_source_apple_health
    ImportSource.ImportFile -> Res.string.fit_onboarding_source_file
    ImportSource.StartFresh -> Res.string.fit_onboarding_source_fresh
}

private fun sourceHint(source: ImportSource): StringResource = when (source) {
    ImportSource.HealthConnect -> Res.string.fit_onboarding_source_health_connect_hint
    ImportSource.AppleHealth -> Res.string.fit_onboarding_source_apple_health_hint
    ImportSource.ImportFile -> Res.string.fit_onboarding_source_file_hint
    ImportSource.StartFresh -> Res.string.fit_onboarding_source_fresh_hint
}

@Composable
private fun SourceRow(source: ImportSource, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        FitIcons.importSource(source),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(sourceTitle(source)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(sourceHint(source)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // A trailing status pill for the platform-provided source ("next to the item",
            // not floating between the title and the hint).
            if (source == ImportSource.HealthConnect || source == ImportSource.AppleHealth) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = stringResource(Res.string.fit_onboarding_source_available),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Icon(
                FitIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PermissionsStep(
    categories: List<ImportCategory>,
    selection: Set<CategoryId>,
    onToggle: (CategoryId) -> Unit,
    onBack: () -> Unit,
    onImport: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(FitIcons.ChevronLeft, contentDescription = stringResource(Res.string.fit_back))
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.fit_onboarding_permissions_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.fit_onboarding_permissions_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                categories.forEachIndexed { i, category ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CategoryConsentRow(
                        category = category,
                        checked = category.id in selection,
                        onToggle = { onToggle(category.id) },
                    )
                }
            }
            Box(Modifier.padding(16.dp)) {
                Button(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Text(
                        stringResource(Res.string.fit_onboarding_import_selected),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

/**
 * The deep-history offer (§15) — the one thing that must be asked *before* the first read, since a
 * `Recent` import cannot retroactively deepen itself.
 *
 * Framed as a real choice with an equal-weight decline, not a wall: "Not now" is a plain text
 * button beside the primary action, and the footnote says where to find it later. The step is
 * reached only when the grant is genuinely available and not already held (`needsHistoryStep`), so
 * it never appears as an unanswerable question.
 */
@Composable
private fun DeepHistoryStep(
    onAllow: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(FitIcons.ChevronLeft, contentDescription = stringResource(Res.string.fit_back))
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.fit_onboarding_history_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.fit_onboarding_history_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.padding(16.dp)) {
                Button(
                    onClick = onAllow,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Text(
                        stringResource(Res.string.fit_onboarding_history_allow),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.fit_onboarding_history_skip))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.fit_onboarding_history_later),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CategoryConsentRow(
    category: ImportCategory,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val empty = category.estimatedCount <= 0
    Surface(
        onClick = onToggle,
        enabled = !empty,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().alpha(if (empty) 0.5f else 1f),
    ) {
        Row(
            Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FitIcons.importCategory(category.id),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = categoryName(category.id),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (empty) stringResource(Res.string.fit_onboarding_empty_category)
                    else stringResource(Res.string.fit_onboarding_estimated_count, groupThousands(category.estimatedCount)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ConsentBox(checked = checked && !empty)
        }
    }
}

@Composable
private fun ConsentBox(checked: Boolean) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = if (checked) null else androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.size(24.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (checked) {
                Icon(
                    FitIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ImportingStep(
    progress: List<ImportProgress>,
    fraction: Float,
    complete: Boolean,
    onStart: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                app.tenshu.fit.ui.components.Ring(
                    value = (fraction * 100).toInt(),
                    goal = 100,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    size = 140.dp,
                    stroke = 12.dp,
                ) {
                    if (complete) {
                        Icon(
                            FitIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                    } else {
                        Text(
                            text = "${(fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(
                        if (complete) Res.string.fit_onboarding_import_complete_title
                        else Res.string.fit_onboarding_importing_title,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (complete) Res.string.fit_onboarding_import_complete_note
                        else Res.string.fit_onboarding_importing_note,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                progress.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ImportProgressRow(row)
                }
            }
            Box(Modifier.padding(16.dp)) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Text(
                        stringResource(
                            if (complete) Res.string.fit_onboarding_continue
                            else Res.string.fit_onboarding_start_now,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportProgressRow(row: ImportProgress) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp).alpha(if (row.total == 0) 0.55f else 1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FitIcons.importCategory(row.id),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = categoryName(row.id),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    Res.string.fit_onboarding_progress_counts,
                    groupThousands(row.imported),
                    groupThousands(row.total),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            row.done && row.total > 0 -> Icon(
                FitIcons.Check,
                contentDescription = stringResource(Res.string.fit_onboarding_done),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            row.imported == 0 -> Text(
                stringResource(Res.string.fit_onboarding_queued),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(14.dp),
            ) {}
        }
    }
}
