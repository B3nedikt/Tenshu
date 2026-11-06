package app.tenshu.fit.ui.screens.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.FitProfile
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.DataSourceState
import app.tenshu.fit.model.ImportRunStatus
import app.tenshu.fit.shared.resources.fit_batch_import_hint
import app.tenshu.fit.shared.resources.fit_batch_import_last
import app.tenshu.fit.shared.resources.fit_batch_import_never
import app.tenshu.fit.shared.resources.fit_batch_import_section
import app.tenshu.fit.shared.resources.fit_batch_import_title
import app.tenshu.fit.shared.resources.fit_source_on_device
import app.tenshu.fit.ui.components.dataSourceHint
import app.tenshu.fit.ui.components.dataSourceName
import app.tenshu.fit.ui.components.dataSourceStatus
import app.tenshu.fit.ui.components.formatRunDate
import app.tenshu.fit.model.ImportNotice
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Units
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.shared.resources.fit_import_file_hint
import app.tenshu.fit.shared.resources.fit_import_file_title
import app.tenshu.fit.shared.resources.fit_import_full_history_granted
import app.tenshu.fit.shared.resources.fit_import_full_history_hint
import app.tenshu.fit.shared.resources.fit_import_full_history_title
import app.tenshu.fit.shared.resources.fit_import_running
import app.tenshu.fit.shared.resources.fit_import_running_count
import app.tenshu.fit.shared.resources.fit_profile_add_source
import app.tenshu.fit.shared.resources.fit_profile_add_source_hint
import app.tenshu.fit.shared.resources.fit_profile_birth_date
import app.tenshu.fit.shared.resources.fit_profile_goal_active_minutes
import app.tenshu.fit.shared.resources.fit_profile_goal_steps
import app.tenshu.fit.shared.resources.fit_profile_automatic
import app.tenshu.fit.shared.resources.fit_profile_auto_detect
import app.tenshu.fit.shared.resources.fit_profile_auto_detect_hint
import app.tenshu.fit.shared.resources.fit_profile_goals
import app.tenshu.fit.shared.resources.fit_profile_height
import app.tenshu.fit.shared.resources.fit_profile_measurement
import app.tenshu.fit.shared.resources.fit_profile_name
import app.tenshu.fit.shared.resources.fit_profile_not_set
import app.tenshu.fit.shared.resources.fit_profile_per_day
import app.tenshu.fit.shared.resources.fit_profile_personal
import app.tenshu.fit.shared.resources.fit_profile_sex
import app.tenshu.fit.shared.resources.fit_profile_sources
import app.tenshu.fit.shared.resources.fit_profile_title
import app.tenshu.fit.shared.resources.fit_profile_about
import app.tenshu.ui.legal.privacyPolicyLabel
import app.tenshu.ui.legal.rememberPrivacyPolicyOpener
import app.tenshu.fit.shared.resources.fit_profile_units
import app.tenshu.fit.shared.resources.fit_profile_weight
import app.tenshu.fit.shared.resources.fit_source_file_imports
import app.tenshu.fit.shared.resources.fit_source_health_connect
import app.tenshu.fit.shared.resources.fit_source_health_connect_hint
import app.tenshu.fit.shared.resources.fit_source_notice_google_fit_action
import app.tenshu.fit.shared.resources.fit_source_notice_google_fit_blocked_action
import app.tenshu.fit.shared.resources.fit_source_notice_google_fit_blocked_body
import app.tenshu.fit.shared.resources.fit_source_notice_google_fit_blocked_title
import app.tenshu.fit.shared.resources.fit_source_notice_google_fit_body
import app.tenshu.fit.shared.resources.fit_source_notice_google_fit_title
import app.tenshu.fit.shared.resources.fit_sharing_notice_action
import app.tenshu.fit.shared.resources.fit_sharing_row_hint
import app.tenshu.fit.shared.resources.fit_sharing_row_title
import app.tenshu.fit.shared.resources.fit_source_status_failed
import app.tenshu.fit.shared.resources.fit_source_status_waiting
import app.tenshu.fit.shared.resources.fit_source_status_imported
import app.tenshu.fit.shared.resources.fit_source_status_no_data
import app.tenshu.fit.shared.resources.fit_source_status_permission
import app.tenshu.fit.shared.resources.fit_source_status_sample
import app.tenshu.fit.shared.resources.fit_source_status_unavailable
import app.tenshu.fit.shared.resources.fit_units_imperial
import app.tenshu.fit.shared.resources.fit_units_metric
import app.tenshu.fit.shared.resources.fit_value_cm
import app.tenshu.fit.shared.resources.fit_value_ft_in
import app.tenshu.fit.shared.resources.fit_value_kg
import app.tenshu.fit.shared.resources.fit_value_lb
import app.tenshu.fit.ui.components.FitCard
import app.tenshu.fit.ui.components.FitIcons
import app.tenshu.fit.ui.components.FitTopBar
import app.tenshu.fit.ui.components.SectionHeader
import app.tenshu.fit.ui.components.TrustCue
import app.tenshu.fit.ui.components.dateWithYear
import app.tenshu.fit.ui.components.groupThousands
import app.tenshu.fit.ui.components.oneDecimal
import app.tenshu.fit.ui.components.sourceLabel
import app.tenshu.fit.ui.components.updatedText
import org.jetbrains.compose.resources.stringResource
import org.tenshu.preference.BiologicalSex
import kotlin.math.roundToInt
import app.tenshu.ui.legal.openSourceLicencesLabel

/**
 * Profile (docs/proposals/fit-app.md screen 11) — goals, personal data, units, and the
 * connected data-source list. The Units row is the one live mutation in v1 (toggles the
 * whole app's metric/imperial presentation via [ProfileViewModel.setUnits]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    /**
     * Opens Browse → Body. Weight is a *measurement* (fit-app.md §18.2), so its row leads to the
     * reading history rather than to an editor — the first time that chevron has told the truth.
     */
    onOpenBodyData: () -> Unit = {},
    /** Opens the sharing wizard (fit-app.md §15.6) — now only from the Google Fit notice. */
    onOpenSharingWizard: () -> Unit = {},
    /** Opens one source's detail (`fit-data-sources-restructure.md` §4). */
    onOpenDataSource: (DataSourceKind, String?) -> Unit = { _, _ -> },
    /** Opens the batch-import history. */
    onOpenBatchImport: () -> Unit = {},
    /** Opens the open-source notices (`docs/oss-licences.md`). */
    onOpenLicences: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    // ⚠ Before the profile loads, the screen is still Fit's settings: show the legal entries alone
    // rather than nothing (App Store 5.1.1(i) wants the policy reachable in every state).
    val profile = state.profile ?: return ProfileAboutSection(onOpenLicences, Modifier.fillMaxWidth().padding(16.dp))
    // Exactly one editor at a time — a dialog per row, opened by that row's chevron (§18.6).
    var editing by remember { mutableStateOf<ProfileEditor?>(null) }

    // ⚠⚠ The M3 **small** top app bar, not the large one this screen shipped with first
    // (fit-app.md §18.15). The large bar is a `TwoRowsTopAppBar`: its top row is pinned at 64.dp
    // — that row *is* the collapsed bar, and it is where a nav icon and actions would live — and
    // its title is bottom-aligned in the second row. Profile has neither a nav icon nor actions,
    // so those 64.dp drew nothing at all, which is what read as "huge padding". No parameter
    // centres a two-row bar's title; the component that does is this one.
    //
    // ⚠ It was also off-spec as a large bar: `expandedHeight = 112.dp` is the **medium** bar's
    // container token, so it rendered `headlineMedium` (the large bar's type) in a container
    // sized for `headlineSmall`. M3 pairs the height with the type scale and has no such variant.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scrollState = rememberScrollState()

    // ⚠⚠ A `Scaffold`, not a `Column`. Content must scroll *under* an overlaying bar, where the
    // scrollable extent never moves; as siblings in a Column the bar owns layout height and the
    // extent shifts mid-gesture, feeding straight back into the nested-scroll connection. Drive's
    // screens already host bars this way (`SyncPreferencesScreen`).
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FitTopBar(
                title = stringResource(Res.string.fit_profile_title),
                scrollBehavior = scrollBehavior,
            )
        },
        // ⚠ Zero, and it stays zero after the §18.15 inset flip: the **bar** owns the status-bar
        // inset now (`TopAppBarDefaults.windowInsets`), and the shell still owns the bottom one.
        // A screen-level inset here would double-pad against both.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(scrollState)
                .padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProfileSection(stringResource(Res.string.fit_profile_goals)) {
                // Both goals open the same dialog — they are one decision, and iOS has always
                // shown them as one pair of steppers.
                ProfileRow(
                    label = stringResource(Res.string.fit_profile_goal_active_minutes),
                    value = stringResource(Res.string.fit_profile_per_day, state.goals.activeMinutes.toString()),
                    valueColor = MaterialTheme.colorScheme.primary,
                    onClick = { editing = ProfileEditor.Goals },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProfileRow(
                    label = stringResource(Res.string.fit_profile_goal_steps),
                    value = stringResource(Res.string.fit_profile_per_day, groupThousands(state.goals.steps)),
                    last = true,
                    onClick = { editing = ProfileEditor.Goals },
                )
            }

            ProfileSection(stringResource(Res.string.fit_profile_personal)) {
                // ⚠ The name moved here when the identity header was removed. It is NOT a
                // duplicate any more — with the header gone this is its only editor, and Home
                // greets the user by it, so deleting the header without this would have quietly
                // removed the ability to set your own name.
                ProfileRow(
                    label = stringResource(Res.string.fit_profile_name),
                    value = profile.name?.takeIf { it.isNotBlank() } ?: notSet(),
                    onClick = { editing = ProfileEditor.Name },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // Height and weight open an *entry* dialog that records a new reading, not an
                // editor that overwrites one — they are measurements (§18.2). Weight additionally
                // has a history to show; Height does not (Body charts weight only), which is why
                // only weight offers the Browse → Body trailing action.
                ProfileRow(
                    label = stringResource(Res.string.fit_profile_height),
                    value = heightText(profile),
                    onClick = { editing = ProfileEditor.Height },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProfileRow(
                    label = stringResource(Res.string.fit_profile_weight),
                    value = weightText(profile),
                    onClick = { editing = ProfileEditor.Weight },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProfileRow(
                    label = stringResource(Res.string.fit_profile_birth_date),
                    value = profile.birthDate?.let { dateWithYear(it) } ?: notSet(),
                    onClick = { editing = ProfileEditor.BirthDate },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProfileRow(
                    label = stringResource(Res.string.fit_profile_sex),
                    value = sexText(profile.sex),
                    last = true,
                    onClick = { editing = ProfileEditor.Sex },
                )
            }

            ProfileSection(stringResource(Res.string.fit_profile_units)) {
                ProfileRow(
                    label = stringResource(Res.string.fit_profile_measurement),
                    value = stringResource(if (profile.units == Units.Imperial) Res.string.fit_units_imperial else Res.string.fit_units_metric),
                    last = true,
                    onClick = {
                        viewModel.setUnits(if (profile.units == Units.Metric) Units.Imperial else Units.Metric)
                    },
                )
            }

            ProfileSection(stringResource(Res.string.fit_profile_sources)) {
                // ⚠⚠ Every row here IS a source, and its state comes from `DataSourceProjection`
                // (D10) — the screen renders, it never re-derives which state wins. This section
                // used to mix a source, two actions and a diagnostic as identical rows; the
                // actions now live inside the source they belong to.
                //
                // ⚠ An empty list means "nothing to show yet", NOT "you have no sources", so it
                // renders nothing rather than an empty state that claims the user's realm is bare.
                state.dataSources.forEachIndexed { index, row ->
                    SourceRow(
                        icon = FitIcons.dataSource(row.kind),
                        name = dataSourceName(row.kind),
                        // ⚠ Only the device label. The kind hint used to live here and it is
                        // what wrapped to four lines beside a long status — it says the same
                        // thing on every device, every time, and the detail screen still
                        // carries it.
                        detail = row.deviceLabel
                            ?.let { stringResource(Res.string.fit_source_on_device, it) },
                        status = dataSourceStatus(row.state),
                        highlight = row.state is DataSourceState.Imported ||
                            row.state is DataSourceState.Recording,
                        onClick = { onOpenDataSource(row.kind, row.deviceId) },
                    )
                    if (index != state.dataSources.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                // §4's one deliberate exception to moving the notice into the detail: it is the
                // only element that actively prompts, and burying every prompt one tap down
                // trades clutter for silence. The row's highlighted status is the passive half.
                when (state.importStatus.notice) {
                    ImportNotice.GoogleFitNotPermitted -> SourceNotice(
                        title = stringResource(Res.string.fit_source_notice_google_fit_blocked_title),
                        body = stringResource(Res.string.fit_source_notice_google_fit_blocked_body),
                        action = stringResource(Res.string.fit_sharing_notice_action),
                        onClick = onOpenSharingWizard,
                    )
                    // Permitted and still silent: permission is not the obstacle, so only an
                    // export can help. Named only where a picker exists — otherwise it is a
                    // second dead end.
                    ImportNotice.GoogleFitNotSharing -> SourceNotice(
                        title = stringResource(Res.string.fit_source_notice_google_fit_title),
                        body = stringResource(Res.string.fit_source_notice_google_fit_body),
                        action = if (state.canImportFiles) stringResource(Res.string.fit_source_notice_google_fit_action) else null,
                        onClick = if (state.canImportFiles) onOpenBatchImport else null,
                    )
                    null -> Unit
                }
            }

            // A separate section, because a file import is an ACT rather than a standing
            // connection (D11) — no source row, no auto-import switch, nothing to disconnect.
            // Absent entirely where the shell can pick no file, rather than shown empty.
            state.batchImport?.let { batch ->
                ProfileSection(stringResource(Res.string.fit_batch_import_section)) {
                    SourceRow(
                        icon = FitIcons.Archive,
                        name = stringResource(Res.string.fit_batch_import_title),
                        // Likewise: the explanation is on the screen this row opens.
                        detail = null,
                        status = batch.lastRunAt
                            ?.let { stringResource(Res.string.fit_batch_import_last, formatRunDate(it)) }
                            ?: stringResource(Res.string.fit_batch_import_never),
                        highlight = batch.lastStatus == ImportRunStatus.Succeeded,
                        onClick = onOpenBatchImport,
                    )
                }
            }

            // The legal entries: the in-app privacy policy (App Store 5.1.1(i), Play User Data
            // policy), then the open-source notices. Profile is Fit's settings screen, so they live
            // here, last before the trust cue.
            ProfileAboutSection(onOpenLicences)

            TrustCue(Modifier.fillMaxWidth().padding(top = 2.dp))
        }
    }

    val dismiss = { editing = null }
    when (editing) {
        ProfileEditor.Name -> NameDialog(profile.name, dismiss, viewModel::saveName)
        ProfileEditor.Goals -> GoalsDialog(state.goals.activeMinutes, state.goals.steps, dismiss, viewModel::saveGoals)
        ProfileEditor.BirthDate -> BirthDateDialog(profile.birthDate, dismiss) { viewModel.saveBirthDate(it); dismiss() }
        ProfileEditor.Sex -> SexDialog(profile.sex, dismiss, viewModel::saveSex)
        ProfileEditor.Height -> HeightDialog(profile.heightCm, profile.units, dismiss, viewModel::saveHeight)
        // Weight is the one row with a history worth opening, so its dialog offers it — as a
        // visible action, not a hidden long-press. Height has no such screen (Body charts weight
        // only), so it simply does not offer one.
        ProfileEditor.Weight -> WeightDialog(
            currentKg = profile.weightKg,
            units = profile.units,
            onDismiss = dismiss,
            onSave = viewModel::saveWeight,
            onViewHistory = { dismiss(); onOpenBodyData() },
        )
        null -> Unit
    }
}

/**
 * The legal entries — privacy policy, then open-source licences (`docs/oss-licences.md`) — in
 * Profile's section style. Shown before the profile loads too.
 */
@Composable
private fun ProfileAboutSection(onOpenLicences: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        ProfileSection(stringResource(Res.string.fit_profile_about)) {
            ProfileRow(
                label = privacyPolicyLabel(),
                value = "",
                onClick = rememberPrivacyPolicyOpener(),
            )
            ProfileRow(
                label = openSourceLicencesLabel(),
                value = "",
                last = true,
                onClick = onOpenLicences,
            )
        }
    }
}

@Composable
private fun ProfileSection(label: String, content: @Composable () -> Unit) {
    Column {
        SectionHeader(label, Modifier.padding(start = 2.dp, bottom = 8.dp))
        FitCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
            Column { content() }
        }
    }
}

/**
 * A row whose value *is* the control — Fit's first switch (FA-2 D1).
 *
 * ⚠ Deliberately not a [ProfileRow] with a `Switch` in the value slot: that row's chevron rule is
 * "clickable ⇒ somewhere to go", and a toggle goes nowhere. Making the whole row clickable to
 * flip it keeps the tap target honest while the chevron stays absent.
 */
@Composable
private fun ProfileToggleRow(
    label: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ProfileRow(
    label: String,
    value: String,
    last: Boolean = false,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()
    Row(
        rowModifier.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
        // ⚠ The chevron is conditional on there being somewhere to go. It used to render
        // unconditionally while only the Units row was clickable, so six of seven rows advertised
        // a destination that did not exist. FP-3 gives every row an editor and they will all earn
        // one again — the condition stays so the next non-interactive row cannot silently
        // re-open the same bug (fit-app.md §18.1).
        if (onClick != null) {
            Icon(FitIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SourceRow(
    icon: ImageVector,
    name: String,
    /**
     * Second line, or null for a single-line row.
     *
     * ⚠ Nullable since DS-4's statuses got long. A row carrying both a generic hint *and*
     * "163 items · 14.08.2026" has two texts competing for one width, and the status wins
     * because it is measured first — on a real phone that squeezed "Workouts you record, enter
     * or that are detected" into **four** lines. The hint is orientation you need once and it
     * lives on the detail screen; the status is what the row is for.
     */
    detail: String?,
    status: String?,
    highlight: Boolean,
    last: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                // ⚠ Never wraps. A status that broke across two lines would re-introduce the
                // squeeze from the other side, and "163 items ·" / "14.08.2026" reads worse than
                // either half alone.
                maxLines = 1,
            )
        }
    }
}

/**
 * An explanation, not a state: something true about the source above that its one-line status
 * cannot carry — see [ImportNotice].
 *
 * Deliberately *not* a [SourceRow]. A notice is prose, it has no right-hand status, and it must not
 * read as one more connected source; the tonal container marks it as commentary on the list rather
 * than a member of it. [action] and [onClick] are optional together, because the remedy a notice
 * names is not offered on every platform — a tappable line that leads nowhere is worse than a
 * plain one.
 */
@Composable
private fun SourceNotice(title: String, body: String, action: String?, onClick: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (action != null) {
                Text(
                    action,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The shared "we have never been told" value — see `fit_profile_not_set`. */
@Composable
private fun notSet(): String = stringResource(Res.string.fit_profile_not_set)

@Composable
private fun heightText(profile: FitProfile): String {
    val heightCm = profile.heightCm ?: return notSet()
    return if (profile.units == Units.Imperial) {
        val totalInches = (heightCm / 2.54).roundToInt()
        stringResource(Res.string.fit_value_ft_in, totalInches / 12, totalInches % 12)
    } else {
        stringResource(Res.string.fit_value_cm, heightCm)
    }
}

@Composable
private fun weightText(profile: FitProfile): String {
    // Unlike the rest of Personal data this is a *measurement*: it comes from the latest
    // `HealthSample`, so "Not set" here means the store holds no weight reading at all, not that
    // the user skipped a form (fit-app.md §18.2).
    val weightKg = profile.weightKg ?: return notSet()
    return if (profile.units == Units.Imperial) {
        stringResource(Res.string.fit_value_lb, oneDecimal(weightKg * 2.2046))
    } else {
        stringResource(Res.string.fit_value_kg, oneDecimal(weightKg))
    }
}

/** The nullable presentation of [sexLabel] — `null` is "not set", a real answer (§18.4). */
@Composable
private fun sexText(sex: BiologicalSex?): String = sex?.let { sexLabel(it) } ?: notSet()

/**
 * The live source's one-line state, or null only for [ImportStatus.Idle] — no import has run, so
 * there is nothing to say about one.
 *
 * Every other state gets a row, and that is load-bearing: the ViewModel drops the
 * provenance-derived Health Connect entry as soon as the import reports on the source itself, so a
 * `null` here would delete the row rather than describe it — and the app would claim Health Connect
 * was never connected in exactly the states (failed, sample, unavailable) where the user most needs
 * to know what happened to it.
 */
@Composable
private fun importStatusDetail(status: ImportStatus): String? = when (status) {
    is ImportStatus.Imported -> stringResource(Res.string.fit_source_status_imported, status.itemCount)
    is ImportStatus.NoData -> stringResource(Res.string.fit_source_status_no_data)
    is ImportStatus.PermissionsMissing -> stringResource(Res.string.fit_source_status_permission)
    is ImportStatus.Running ->
        if (status.imported > 0) stringResource(Res.string.fit_import_running_count, status.imported)
        else stringResource(Res.string.fit_import_running)
    is ImportStatus.Failed -> stringResource(Res.string.fit_source_status_failed)
    is ImportStatus.WaitingForControl -> stringResource(Res.string.fit_source_status_waiting)
    is ImportStatus.Sample -> stringResource(Res.string.fit_source_status_sample)
    ImportStatus.SourceUnavailable -> stringResource(Res.string.fit_source_status_unavailable)
    ImportStatus.Idle -> null
}
