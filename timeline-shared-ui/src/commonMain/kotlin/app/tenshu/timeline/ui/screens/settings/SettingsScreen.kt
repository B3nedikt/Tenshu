package app.tenshu.timeline.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tenshu.timeline.capture.TimelineCaptureControl
import app.tenshu.timeline.data.TimelineImportControl
import app.tenshu.timeline.data.TimelineImportStatus
import app.tenshu.timeline.data.summary
import app.tenshu.timeline.data.geocode.PlaceGeocodingControl
import app.tenshu.timeline.data.capture.RawFixJournal
import app.tenshu.ui.map.MapRendererSetting
import app.tenshu.timeline.data.capture.RawFixJournalControl
import app.tenshu.timeline.data.capture.SegmentationReplaySweep
import androidx.compose.runtime.LaunchedEffect
import app.tenshu.timeline.data.wifi.WifiFingerprintControl
import app.tenshu.timeline.data.wifi.WifiPlaceFingerprint
import app.tenshu.timeline.data.wifi.WifiRecognitionState
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_settings
import app.tenshu.timeline.shared.resources.timeline_settings_capture
import app.tenshu.timeline.shared.resources.timeline_settings_capture_desc
import app.tenshu.timeline.shared.resources.timeline_settings_capture_section
import app.tenshu.timeline.shared.resources.timeline_settings_geocoding
import app.tenshu.timeline.shared.resources.timeline_settings_geocoding_desc
import app.tenshu.timeline.shared.resources.timeline_settings_geocoding_section
import app.tenshu.timeline.shared.resources.timeline_settings_import
import app.tenshu.timeline.shared.resources.timeline_settings_import_action
import app.tenshu.timeline.shared.resources.timeline_settings_import_desc
import app.tenshu.timeline.shared.resources.timeline_settings_import_dismiss
import app.tenshu.timeline.shared.resources.timeline_settings_import_photos
import app.tenshu.timeline.shared.resources.timeline_settings_import_photos_desc
import app.tenshu.timeline.shared.resources.timeline_settings_import_running
import app.tenshu.timeline.shared.resources.timeline_settings_import_section
import app.tenshu.timeline.shared.resources.timeline_settings_wifi
import app.tenshu.timeline.shared.resources.timeline_settings_wifi_desc
import app.tenshu.timeline.shared.resources.timeline_settings_wifi_status_no_network
import app.tenshu.timeline.shared.resources.timeline_settings_wifi_status_unknown
import app.tenshu.timeline.shared.resources.timeline_settings_wifi_status_withheld
import app.tenshu.timeline.shared.resources.timeline_settings_wifi_status_working
import app.tenshu.timeline.shared.resources.timeline_settings_replay_section
import app.tenshu.timeline.shared.resources.timeline_settings_wifi_section
import app.tenshu.timeline.shared.resources.timeline_settings_about_section
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import app.tenshu.ui.legal.privacyPolicyLabel
import app.tenshu.ui.legal.rememberPrivacyPolicyOpener
import app.tenshu.timeline.ui.components.TimelineDetailScaffold
import androidx.compose.runtime.collectAsState
import app.tenshu.ui.legal.openSourceLicencesLabel
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin

/**
 * Settings — the destination behind the Home top-bar gear (docs/proposals/timeline-app.md §7).
 * Only real, actionable controls live here: the three capture opt-ins, each rendered only where
 * a platform binds its control. The display-only Appearance note ("follows your system"), the
 * About card and its privacy tagline were removed — they stated the platform default and the
 * product-wide E2E promise, neither of which is a setting.
 *
 * [DiagnosticsSection] used to be unrendered on the grounds that it is a developer surface rather
 * than a user-facing one. It now renders — but **only inside the debug-gated capture-diagnostics
 * block** (§18.9), which resolves that objection rather than overriding it: the section appears
 * exactly where a developer needs it (beside the replay that writes into it) and nowhere else,
 * because Android binds `RawFixJournalControl` only in debug builds.
 */
@Composable
fun SettingsScreen(
    // Null on the shells where Settings is a nav-chrome destination (Compose): there is nothing
    // below it on the back stack, so a chevron would be a control that does nothing.
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Opens the open-source notices (`docs/oss-licences.md`). */
    onLicences: () -> Unit = {},
) {
    TimelineDetailScaffold(
        title = stringResource(Res.string.timeline_settings),
        onBack = onBack,
        modifier = modifier,
    ) {
        // Capture opt-in — rendered only where a platform binds a control (Android); off by
        // default (threat-model A35). Injected here so the shared navigation stays platform-blind.
        val koin = getKoin()
        val captureControl = remember(koin) { koin.getOrNull<TimelineCaptureControl>() }
        // Place-name lookup opt-in (place-geocoding.md D5) — same pattern: rendered only where a
        // platform binds a control (none yet in G1, so this appears nowhere until G2 binds one).
        val geocodingControl = remember(koin) { koin.getOrNull<PlaceGeocodingControl>() }
        // Wi-Fi place recognition opt-in (§4.5) — same bound-only-shows pattern.
        val wifiControl = remember(koin) { koin.getOrNull<WifiFingerprintControl>() }
        // B2: bound where a platform can pick a file (Android, desktop) — absent everywhere else,
        // so the row is missing rather than present and inert.
        val importControl = remember(koin) { koin.getOrNull<TimelineImportControl>() }
        // §18.9 — bound only in debug builds, so this section simply is not there in release.
        val rawFixControl = remember(koin) { koin.getOrNull<RawFixJournalControl>() }
        val rawFixJournal = remember(koin) { koin.getOrNull<RawFixJournal>() }
        val replaySweep = remember(koin) { koin.getOrNull<SegmentationReplaySweep>() }

        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            if (captureControl != null) {
                SectionHeader(stringResource(Res.string.timeline_settings_capture_section))
                CaptureToggleRow(captureControl)
                Spacer(Modifier.height(28.dp))
            }

            if (importControl != null) {
                SectionHeader(stringResource(Res.string.timeline_settings_import_section))
                ImportRow(importControl)
                Spacer(Modifier.height(28.dp))
            }

            if (geocodingControl != null) {
                SectionHeader(stringResource(Res.string.timeline_settings_geocoding_section))
                GeocodingToggleRow(geocodingControl)
                Spacer(Modifier.height(28.dp))
            }

            // The map-renderer election, beside the geocoding toggle because §4 of
            // `ios-apple-maps-option.md` argues an egress choice belongs in Settings rather than in
            // an onboarding fork. ⚠ No `if` here on purpose: the composable is an expect/actual that
            // renders NOTHING where there is no second renderer to elect — Android by decision
            // (§9 Q4), desktop off macOS, and macOS without the snapshot helper built. Gating it
            // here as well would put the same rule in two places and let them disagree.
            MapRendererSetting()
            Spacer(Modifier.height(28.dp))

            if (wifiControl != null) {
                SectionHeader(stringResource(Res.string.timeline_settings_wifi_section))
                WifiToggleRow(wifiControl, remember(koin) { koin.getOrNull<WifiPlaceFingerprint>() })
                Spacer(Modifier.height(28.dp))
            }

            // The legal entries: the in-app privacy policy (App Store 5.1.1(i), Play User Data
            // policy), then the open-source notices this binary ships (docs/oss-licences.md).
            SectionHeader(stringResource(Res.string.timeline_settings_about_section))
            PrivacyPolicyRow()
            LicencesRow(onLicences)
            Spacer(Modifier.height(28.dp))

            // §18.9 capture diagnostics + the log the replay writes into. ⚠ Gated on the raw-fix
            // control being *bound*, which Android does only in debug builds — so this whole block,
            // including the otherwise-developer-only DiagnosticsSection, is absent from release.
            if (rawFixControl != null) {
                SectionHeader(stringResource(Res.string.timeline_settings_replay_section))
                CaptureDiagnosticsSection(
                    control = rawFixControl,
                    journal = rawFixJournal ?: RawFixJournal.None,
                    sweep = replaySweep,
                )
                DiagnosticsSection()
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun CaptureToggleRow(control: TimelineCaptureControl) {
    val enabled by control.enabled.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(Res.string.timeline_settings_capture),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.timeline_settings_capture_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = control::setEnabled)
    }
}

/**
 * The Takeout import row and whatever the last attempt said (B2).
 *
 * ⚠⚠ The result line reports what was **not** understood as well as what was. An export that
 * silently imports nothing while saying "done" is the failure TB-D2 exists to prevent, and a user
 * can only tell us about it if the number is on screen.
 */
@Composable
private fun ImportRow(control: TimelineImportControl) {
    val status by control.status.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ⚠⚠ The button goes BELOW the text, not beside it. Beside a `weight(1f)` description it
        // gets only the leftover width, and on a phone that squeezed it to one word per line —
        // reported from a real device, and invisible to every test that only asked "is it there".
        // ⚠ Its label is short and deliberately NOT the row title repeated: the first version used
        // the same string for both, which was both redundant and the reason it could not fit.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(Res.string.timeline_settings_import),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.timeline_settings_import_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ⚠ Disabled while a walk is running: a second pick would race the first over the same
        // store, and the import is idempotent but the progress line is not.
        Button(
            onClick = control::pick,
            enabled = status !is TimelineImportStatus.Running,
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(Res.string.timeline_settings_import_action)) }

        // B5 — off by default, and stated plainly: importing a photo library is a much larger
        // decision than importing a location history, and it spends the user's storage quota.
        val includePhotos by control.includePhotos.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(Res.string.timeline_settings_import_photos),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.timeline_settings_import_photos_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = includePhotos,
                onCheckedChange = control::setIncludePhotos,
                enabled = status !is TimelineImportStatus.Running,
            )
        }

        when (val current = status) {
            is TimelineImportStatus.Running -> Text(
                text = stringResource(Res.string.timeline_settings_import_running, current.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is TimelineImportStatus.Done -> ImportOutcomeLine(
                text = current.result.summary(current.name),
                onDismiss = control::dismiss,
            )

            is TimelineImportStatus.Failed -> ImportOutcomeLine(
                text = current.message,
                onDismiss = control::dismiss,
            )

            TimelineImportStatus.Idle -> Unit
        }
    }
}

@Composable
private fun ImportOutcomeLine(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(Res.string.timeline_settings_import_dismiss))
        }
    }
}

@Composable
private fun GeocodingToggleRow(control: PlaceGeocodingControl) {
    val enabled by control.enabled.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(Res.string.timeline_settings_geocoding),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.timeline_settings_geocoding_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = control::setEnabled)
    }
}

@Composable
private fun WifiToggleRow(control: WifiFingerprintControl, fingerprint: WifiPlaceFingerprint?) {
    val enabled by control.enabled.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(Res.string.timeline_settings_wifi),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.timeline_settings_wifi_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = control::setEnabled)
    }
    if (fingerprint != null) WifiStatusLine(fingerprint, enabled)
}

/**
 * **S7 (§23): what recognition last did** — the half of §8.2's "is this even working?" that F2's
 * breadcrumbs left unanswered, moved off `adb` and onto the screen that owns the toggle.
 *
 * ⚠⚠ Only [WifiRecognitionState.IdentityWithheld] is styled as a problem. It is the one state the
 * user can act on (location services off), and it was previously indistinguishable from "not on
 * Wi-Fi". Everything else — including `Unknown` — is neutral: a row that cried failure before
 * capture had ever run would teach the user to ignore it.
 *
 * ⚠ Probes on open **and only while consent is on** — `probe()` checks consent before touching the
 * radio, and deliberately performs no learning, so opening Settings can never mint a binding.
 */
@Composable
private fun WifiStatusLine(fingerprint: WifiPlaceFingerprint, enabled: Boolean) {
    val state by fingerprint.state.collectAsState()
    LaunchedEffect(enabled) { if (enabled) fingerprint.probe() }
    if (!enabled) return

    val text = when (state) {
        WifiRecognitionState.IdentityWithheld -> stringResource(Res.string.timeline_settings_wifi_status_withheld)
        WifiRecognitionState.NoNetwork -> stringResource(Res.string.timeline_settings_wifi_status_no_network)
        WifiRecognitionState.Unknown, WifiRecognitionState.ConsentOff ->
            stringResource(Res.string.timeline_settings_wifi_status_unknown)
        else -> stringResource(Res.string.timeline_settings_wifi_status_working)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (state == WifiRecognitionState.IdentityWithheld) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Opens the open-source licences screen, in the same row style as [PrivacyPolicyRow]. */
@Composable
private fun LicencesRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = openSourceLicencesLabel(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Opens the privacy policy in the browser; the glyph says the tap leaves the app. */
@Composable
private fun PrivacyPolicyRow() {
    val open = rememberPrivacyPolicyOpener()
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = privacyPolicyLabel(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}
