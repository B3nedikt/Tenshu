package app.tenshu.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.tenshu.macos.MacMapSnapshotter
import app.tenshu.ui.theme.spacing

/**
 * Desktop `actual`: the Apple Maps election, or nothing at all where there is no Apple to elect.
 *
 * ⚠⚠ Gated on [MacMapSnapshotter.isAvailable], not on the OS name. On Windows and Linux, and on a
 * Mac whose build never produced the helper, there is no second renderer — so the row does not
 * render. An affordance that cannot do what it says would be fabricated; "the helper is here" is
 * the only honest gate.
 *
 * The copy is the same discipline as iOS's `MapRendererSection` and `place-geocoding.md` D5: say
 * what the other party learns **before** the switch, say it is off by default, and name the true
 * scope — **in this app** (D9), because `Preferences` is per-application exactly as
 * `UserDefaults.standard` is per iOS app.
 */
@Composable
actual fun MapRendererSetting() {
    if (!MacMapSnapshotter.isAvailable()) return

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.s)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Use Apple Maps",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = DesktopMapRendererControl.usesAppleMaps,
                onCheckedChange = { DesktopMapRendererControl.select(it) },
            )
        }
        Text(
            text = "Off by default. Our maps draw from map data stored on your device, so opening " +
                "one sends nothing anywhere. Apple Maps covers everywhere and will look familiar — " +
                "but Apple sees which place, trip or workout you open, and when. This applies in " +
                "this app only, and you can turn it off at any time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
        )
    }
}
