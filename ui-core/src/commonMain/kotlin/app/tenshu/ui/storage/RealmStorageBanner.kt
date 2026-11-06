package app.tenshu.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.tenshu.storage.common.RealmStorageState

/**
 * The shared "your realm cannot sync" strip, for any app — the surface half of
 * [RealmStorageState].
 *
 * Renders **nothing** for [RealmStorageState.Ok], so a caller can place it unconditionally above
 * its nav host the way Fit / Timeline / Maps already place [app.tenshu.ui.feedback.ErrorBanner].
 *
 * ⚠ **The caller supplies the localised text**, matching `ErrorBanner`'s contract — `ui-core` ships
 * no strings. It is also the only way the [RealmStorageState.Full] copy can interpolate the figures
 * in the app's own number format.
 *
 * ⚠⚠ **[onManageStorage] is a lambda, not a `StorageSetupLauncher`.** Drive already owns an
 * interface by that name in `drive-shared-logic` with Android and desktop implementations; giving
 * `ui-core` a second interface of the same name is precisely the trap that left master
 * un-buildable for iOS twice this month (two `FakeFilesRepository` classes, one updated). The app
 * passes whatever it already has — Drive passes `launcher::openStorageSetup`, an iOS app passes
 * [openControlServices].
 *
 * ⚠ Pass `onManageStorage = null` where no route to Control exists (Control not installed; desktop
 * has no launch path in v1). The banner then states the situation without offering a button that
 * does nothing, which is worse than no button.
 */
@Composable
fun RealmStorageBanner(
    state: RealmStorageState,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onManageStorage: (() -> Unit)? = null,
) {
    if (state is RealmStorageState.Ok) return

    // `errorContainer` for [Full] and `secondaryContainer` for [NoPlan], on the distinction
    // ErrorBanner's KDoc already draws: error tone means "nothing can load or save". NoPlan is the
    // quieter one on purpose — the app is fully usable, the data is safe, it simply is not leaving
    // the device yet. Shouting about it on every screen of an unfunded realm would be the loudest
    // possible way to say "you have not paid".
    val isError = state is RealmStorageState.Full
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.Warning else Icons.Default.CloudOff,
                contentDescription = null,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onManageStorage != null) {
                TextButton(onClick = onManageStorage) { Text(actionLabel) }
            }
        }
    }
}
