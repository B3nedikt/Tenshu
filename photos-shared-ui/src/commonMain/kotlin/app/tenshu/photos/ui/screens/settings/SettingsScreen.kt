package app.tenshu.photos.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.ingest.IngestSourceInfo
import app.tenshu.ui.legal.AppPrivacyPolicyListItem
import app.tenshu.ui.legal.OpenSourceLicencesListItem
import org.tenshu.core.ingest.IngestSource

/**
 * Settings (`photos-app.md` S7): what this device backs up, and a switch per source.
 *
 * ⚠⚠ **The copy is doing real work here.** The switch is Control's central *kill switch*, not the
 * master toggle: turning a source off genuinely stops it, but turning it on cannot force backups
 * to run while the app that owns the drain has its own backup switch off (the registry read is
 * fail-open). A screen that implied symmetry would be advertising an "on" that sometimes does
 * nothing — so it says where backup is configured instead.
 *
 * ⚠ Photos does not own the drain yet (PH-4b), so this screen manages sources and cannot create
 * one. The empty state says that plainly rather than offering a picker that would write a row
 * nothing reads.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onToggle: (sourceId: String, enabled: Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpenTrash: () -> Unit = {},
    onSkipExistingContent: (Boolean) -> Unit = {},
    onBucketSelected: (String, Boolean) -> Unit = { _, _ -> },
    /** *"Play videos in the grid"* (`photos-video-support.md` D2). Defaulted so previews stay inert. */
    onPlayVideosInGrid: (Boolean) -> Unit = {},
    /** Opens the open-source notices (`docs/oss-licences.md`). */
    onOpenLicences: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // ⚠ The legal entries — the privacy policy, then the open-source notices
    // (`docs/oss-licences.md`) — sit OUTSIDE the `when`, pinned under whatever state is showing:
    // App Store 5.1.1(i) / Play's User Data policy want the policy reachable, and a loading screen
    // or an unreachable Control is exactly when a link inside one state branch would vanish.
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            SettingsStateContent(
                state, onToggle, onRetry, onOpenTrash, onSkipExistingContent, onBucketSelected,
                onPlayVideosInGrid,
            )
        }
        HorizontalDivider()
        AppPrivacyPolicyListItem()
        OpenSourceLicencesListItem(onClick = onOpenLicences)
    }
}

@Composable
private fun SettingsStateContent(
    state: SettingsUiState,
    onToggle: (sourceId: String, enabled: Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpenTrash: () -> Unit,
    onSkipExistingContent: (Boolean) -> Unit,
    onBucketSelected: (String, Boolean) -> Unit,
    onPlayVideosInGrid: (Boolean) -> Unit,
) {
    when {
        state.loading -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }

        state.readFailed -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Can't reach Tenshu Control.\nYour backup settings are safe — Control just isn't running.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Try again") }
        }

        // ⚠ Even with no sources the Trash row must be reachable: a user whose backup is set up
        // on another device still has photos in this realm, and may still have deleted one.
        state.nothingBackedUp -> Column(Modifier.fillMaxSize()) {
            ListItem(
                headlineContent = { Text("Trash") },
                supportingContent = { Text("Deleted photos wait here until you empty it") },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenTrash),
            )
            HorizontalDivider()
            Text(
                text = "Nothing is being backed up from this device yet.\nSet up backup in Tenshu Drive — the photos it saves appear here.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
        }

        else -> LazyColumn(Modifier.fillMaxSize()) {
            item(key = "header") {
                Column(Modifier.padding(16.dp)) {
                    Text("Backed up from this device", style = MaterialTheme.typography.titleMedium)
                    Text(
                        // ⚠ The one sentence that keeps the switch honest.
                        text = "Turning a source off stops it backing up. Backup itself is set up in Tenshu Drive.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                HorizontalDivider()
            }

            // PH-4b: adding a source, not only managing one. ⚠ The whole section is absent when
            // this device cannot enumerate its own folders — desktop, where the drain lives in
            // Control. An empty list would say "you have no folders", which is a different and
            // false claim.
            if (state.canAddSources) {
                item(key = "add-header") {
                    Column(Modifier.padding(16.dp)) {
                        Text("Back up a folder", style = MaterialTheme.typography.titleMedium)
                        Text(
                            // ⚠ Says where the work happens. Ticking a folder here writes the same
                            // device setting Drive's backup reads — it is one engine, not two.
                            text = "Photos and Drive share one backup. A folder you turn on here " +
                                "is backed up by whichever app is running.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    HorizontalDivider()
                }
                items(state.addableBuckets, key = { "bucket-${it.id}" }) { bucket ->
                    ListItem(
                        headlineContent = { Text(bucket.displayName) },
                        supportingContent = { Text("${bucket.itemCount} items") },
                        trailingContent = {
                            Switch(
                                checked = bucket.selected,
                                onCheckedChange = { onBucketSelected(bucket.id, it) },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "add-divider") { HorizontalDivider() }
            }

            item(key = "skip-existing") {
                ListItem(
                    headlineContent = { Text("Skip photos already in Tenshu") },
                    supportingContent = {
                        // ⚠ Says what it compares. "Already in Tenshu" could mean the file name,
                        // the date, or the phone's own memory of what it uploaded — and only one
                        // of those is what this does, across every device in the realm.
                        Text("Don't upload a photo whose exact copy is already saved, even from another device")
                    },
                    trailingContent = {
                        Switch(
                            checked = state.skipExistingContent,
                            onCheckedChange = onSkipExistingContent,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
            }

            item(key = "play-videos") {
                ListItem(
                    headlineContent = { Text("Play videos in the grid") },
                    supportingContent = {
                        // ⚠ Says the two things a user would otherwise wonder: that it is silent,
                        // and that it is one at a time — not a wall of motion.
                        Text("Videos play silently in the feed, one at a time")
                    },
                    trailingContent = {
                        Switch(
                            checked = state.playVideosInGrid,
                            onCheckedChange = onPlayVideosInGrid,
                            modifier = Modifier.testTag(SETTINGS_PLAY_VIDEOS_TAG),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
            }

            item(key = "trash") {
                ListItem(
                    headlineContent = { Text("Trash") },
                    supportingContent = { Text("Deleted photos wait here until you empty it") },
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenTrash),
                )
                HorizontalDivider()
            }

            items(state.sources, key = { it.sourceId }) { source ->
                ListItem(
                    headlineContent = { Text(source.displayName) },
                    supportingContent = { Text(statusLine(source)) },
                    trailingContent = {
                        Switch(
                            checked = source.enabled,
                            onCheckedChange = { onToggle(source.sourceId, it) },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * What a source is doing, in the user's terms.
 *
 * ⚠ An error is shown as an error rather than folded into "paused": a source that is failing and a
 * source the user paused look the same in a list and mean opposite things.
 */
private fun statusLine(source: IngestSourceInfo): String = when {
    !source.enabled -> "Off"
    source.lastError.isNotBlank() -> "Problem: ${source.lastError}"
    source.status == IngestSource.STATUS_RUNNING && source.pendingCount > 0 ->
        "Backing up — ${source.pendingCount} left"
    source.status == IngestSource.STATUS_RUNNING -> "Backing up"
    source.status == IngestSource.STATUS_UP_TO_DATE -> "Up to date"
    source.status == IngestSource.STATUS_OUT_OF_SPACE -> "Out of space"
    source.status == IngestSource.STATUS_PAUSED_POLICY -> "Waiting for Wi-Fi or charging"
    source.pendingCount > 0 -> "${source.pendingCount} waiting"
    else -> "Idle"
}

/** Test handle for the grid-motion switch (`photos-video-support.md` D2). */
const val SETTINGS_PLAY_VIDEOS_TAG = "settingsPlayVideosInGrid"
