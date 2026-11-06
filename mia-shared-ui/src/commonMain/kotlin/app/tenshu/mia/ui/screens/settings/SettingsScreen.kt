package app.tenshu.mia.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.mia.ui.screens.settings.GrantsViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.tenshu.mia.MiaSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tenshu.mia.data.AssistantRoleLauncher
import app.tenshu.mia.data.VoiceInput
import app.tenshu.mia.shared.resources.Res
import app.tenshu.mia.shared.resources.mia_settings_assistant_active
import app.tenshu.mia.shared.resources.mia_settings_assistant_body
import app.tenshu.mia.shared.resources.mia_settings_assistant_open
import app.tenshu.mia.shared.resources.mia_settings_assistant_title
import app.tenshu.mia.shared.resources.mia_settings_coming_soon
import app.tenshu.mia.shared.resources.mia_settings_grants_body
import app.tenshu.mia.shared.resources.mia_settings_grants_calendar
import app.tenshu.mia.shared.resources.mia_settings_grants_devices
import app.tenshu.mia.shared.resources.mia_settings_grants_routines
import app.tenshu.mia.shared.resources.mia_settings_grants_files
import app.tenshu.mia.shared.resources.mia_settings_grants_labels
import app.tenshu.mia.shared.resources.mia_settings_grants_fitness
import app.tenshu.mia.shared.resources.mia_settings_grants_places
import app.tenshu.mia.shared.resources.mia_settings_grants_timeline
import app.tenshu.mia.shared.resources.mia_settings_grants_write
import app.tenshu.mia.shared.resources.mia_settings_grants_title
import app.tenshu.mia.shared.resources.mia_settings_grants_visual_search
import app.tenshu.mia.shared.resources.mia_settings_model_body
import app.tenshu.mia.shared.resources.mia_settings_model_title
import app.tenshu.mia.shared.resources.mia_settings_skills_body
import app.tenshu.mia.shared.resources.mia_settings_skills_title
import app.tenshu.mia.shared.resources.mia_settings_voice_body
import app.tenshu.mia.shared.resources.mia_settings_dictation_off
import app.tenshu.mia.shared.resources.mia_settings_dictation_on
import app.tenshu.mia.shared.resources.mia_settings_dictation_title
import app.tenshu.mia.shared.resources.mia_settings_voice_title
import org.jetbrains.compose.resources.StringResource
import app.tenshu.ui.legal.OpenSourceLicencesListItem
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import app.tenshu.ui.legal.privacyPolicyLabel
import app.tenshu.ui.legal.rememberPrivacyPolicyOpener

/**
 * The Settings tab (docs/proposals/home-and-mia-apps.md §4): every entry present, gated
 * until its machinery exists — voice-ID and model settings need the voice pipeline, and
 * Skills is the D3 post-Rei seam. The default-assistant entry is LIVE where a platform
 * binds an [AssistantRoleLauncher] (M4: Android — a stub `VoiceInteractionService`
 * qualifies Mia, the button opens the system picker since `ROLE_ASSISTANT` is not
 * dialog-requestable); elsewhere it stays informational.
 */
@Composable
fun SettingsScreen(
    /** Opens the open-source notices (`docs/oss-licences.md`). */
    onLicences: () -> Unit = {},
) {
    // Optional platform binding: present on Android, absent on desktop (and iOS renders
    // its own native Settings) — resolved leniently rather than crashing the screen.
    val koin = getKoin()
    val assistantLauncher = remember(koin) { koin.getOrNull<AssistantRoleLauncher>() }
    // AV-1 (IN-D11): a bound VoiceInput means this device recognises speech ON-DEVICE. Absence is
    // the "not available here" signal, and the entry below is where it says so rather than leaving
    // the user to wonder where the mic went.
    val voiceAvailable = remember(koin) { koin.getOrNull<VoiceInput>() != null }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GrantsEntry()
        AssistantEntry(assistantLauncher)
        SettingsEntry(
            Res.string.mia_settings_dictation_title,
            if (voiceAvailable) Res.string.mia_settings_dictation_on
            else Res.string.mia_settings_dictation_off,
        )
        SettingsEntry(Res.string.mia_settings_voice_title, Res.string.mia_settings_voice_body)
        SettingsEntry(Res.string.mia_settings_model_title, Res.string.mia_settings_model_body)
        SettingsEntry(Res.string.mia_settings_skills_title, Res.string.mia_settings_skills_body)
        // The legal entries, last: the in-app privacy policy (App Store 5.1.1(i), Play User Data
        // policy), then the open-source notices this binary ships (docs/oss-licences.md).
        PrivacyPolicyEntry()
        OpenSourceLicencesListItem(onClick = onLicences)
    }
}

/** Opens the privacy policy in the browser; the glyph says the tap leaves the app. */
@Composable
private fun PrivacyPolicyEntry() {
    val open = rememberPrivacyPolicyOpener()
    Card(onClick = open, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                privacyPolicyLabel(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * MQ-D6's switches. ⚠ One line per **service**, because that is the only granularity the
 * platform has — there is no per-file or per-row capability, and a UI hinting at one would
 * promise what nothing can enforce.
 */
@Composable
private fun GrantsEntry() {
    val viewModel: GrantsViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(Res.string.mia_settings_grants_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(Res.string.mia_settings_grants_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            MiaSource.entries.forEach { source ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(grantLabel(source)),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = source in state.granted,
                        enabled = state.editable,
                        onCheckedChange = { viewModel.setGranted(source, it) },
                    )
                }
                // ⚠⚠ The write tier, and only where something can actually write. A switch
                // nothing consults would imply a capability the system does not have.
                if (source.writable) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(Res.string.mia_settings_grants_write),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = source in state.writable,
                            // ⚠ Write without read is unsatisfiable, so the line goes dead
                            // when the source itself is off rather than offering a grant that
                            // could never be used.
                            enabled = state.editable && source in state.granted,
                            onCheckedChange = { viewModel.setWritable(source, it) },
                        )
                    }
                }
            }
        }
    }
}

private fun grantLabel(source: MiaSource): StringResource = when (source) {
    MiaSource.FILES -> Res.string.mia_settings_grants_files
    MiaSource.TIMELINE -> Res.string.mia_settings_grants_timeline
    MiaSource.FITNESS -> Res.string.mia_settings_grants_fitness
    MiaSource.PLACES -> Res.string.mia_settings_grants_places
    MiaSource.DEVICES -> Res.string.mia_settings_grants_devices
    // ⚠ Its own row, not folded into DEVICES: seeing a lamp and running a routine that changes
    // half the house are different permissions, and the second is the one to be able to turn off.
    MiaSource.ROUTINES -> Res.string.mia_settings_grants_routines
    MiaSource.CALENDAR -> Res.string.mia_settings_grants_calendar
    // ⚠ The exhaustive `when` is doing its job here: adding a source without deciding what to call
    // it in the UI is not possible, which is why this is not an `else`.
    MiaSource.LABELS -> Res.string.mia_settings_grants_labels
    // ⚠ Distinct from LABELS in the list, because they are distinct permissions: one reads tags a
    // model already wrote, the other runs a model against your question over the whole library.
    MiaSource.VISUAL_SEARCH -> Res.string.mia_settings_grants_visual_search
}

@Composable
private fun AssistantEntry(launcher: AssistantRoleLauncher?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Text(
                    stringResource(Res.string.mia_settings_assistant_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (launcher != null && launcher.isRoleHeld()) {
                    SuggestionChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(Res.string.mia_settings_assistant_active)) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(Res.string.mia_settings_assistant_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (launcher != null) {
                TextButton(onClick = launcher::openAssistantSettings) {
                    Text(stringResource(Res.string.mia_settings_assistant_open))
                }
            }
        }
    }
}

@Composable
private fun SettingsEntry(title: StringResource, body: StringResource) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                SuggestionChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(Res.string.mia_settings_coming_soon)) },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
