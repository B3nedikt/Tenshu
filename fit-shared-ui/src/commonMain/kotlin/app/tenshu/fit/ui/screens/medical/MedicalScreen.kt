package app.tenshu.fit.ui.screens.medical

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.MedicalRecord
import app.tenshu.fit.model.MedicalRecordKind
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_medical_allergies
import app.tenshu.fit.shared.resources.fit_medical_banner
import app.tenshu.fit.shared.resources.fit_medical_conditions
import app.tenshu.fit.shared.resources.fit_medical_immunizations
import app.tenshu.fit.shared.resources.fit_medical_medications
import app.tenshu.fit.shared.resources.fit_medical_title
import app.tenshu.fit.ui.components.FitIcons
import app.tenshu.fit.ui.components.FitTopBar
import app.tenshu.fit.ui.components.groupedRows
import app.tenshu.fit.ui.components.dateMedium
import org.jetbrains.compose.resources.stringResource

/**
 * Medical records (docs/proposals/fit-app.md screen 11) — import-only, read-only
 * (fit-agent.md §0.7): a calm, sectioned reading surface with no edit affordances.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicalScreen(
    viewModel: MedicalViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    Column(Modifier.fillMaxSize()) {
        FitTopBar(title = stringResource(Res.string.fit_medical_title), onBack = onBack)
        // ⚠ Lazy at the RECORD, not the section. There are at most four sections (one per
        // MedicalRecordKind), so one item per section would compose every record of a kind in a
        // single item and buy nothing — the records are what an import makes unbounded. See
        // [groupedRows] for how a card's surface spans separate lazy items.
        //
        // ⚠⚠ No `verticalArrangement`: it would apply between the grouped rows too and prise each
        // card apart. Block-level items carry their own bottom padding instead.
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
        ) {
            item(key = "privacy-banner") {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(
                            FitIcons.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp).padding(top = 1.dp),
                        )
                        Text(
                            text = stringResource(Res.string.fit_medical_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            state.sections.forEach { section ->
                item(key = "header-${section.kind}") { SectionHeading(section) }
                groupedRows(
                    items = section.records,
                    key = { "${section.kind}-${it.title}-${it.effectiveDate}" },
                ) { record -> MedRow(record) }
                item(key = "gap-${section.kind}") { Spacer(Modifier.size(16.dp)) }
            }
        }
    }
}

/** A kind's heading + record count, above its grouped rows. */
@Composable
private fun SectionHeading(section: MedicalSection) {
    Row(
        Modifier.padding(horizontal = 2.dp).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FitIcons.medical(section.kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = sectionLabel(section.kind).uppercase(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "· ${section.records.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun sectionLabel(kind: MedicalRecordKind): String = stringResource(
    when (kind) {
        MedicalRecordKind.Medication -> Res.string.fit_medical_medications
        MedicalRecordKind.Immunization -> Res.string.fit_medical_immunizations
        MedicalRecordKind.Allergy -> Res.string.fit_medical_allergies
        MedicalRecordKind.Condition -> Res.string.fit_medical_conditions
    },
)

@Composable
private fun MedRow(record: MedicalRecord) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = record.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = dateMedium(record.effectiveDate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = record.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp),
        )
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FitIcons.Profile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = record.issuer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
