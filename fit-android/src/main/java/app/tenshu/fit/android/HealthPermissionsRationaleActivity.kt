package app.tenshu.fit.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tenshu.ui.legal.privacyPolicyLabel
import app.tenshu.ui.legal.rememberPrivacyPolicyOpener
import app.tenshu.ui.theme.TenshuTheme

/**
 * Health Connect's permission rationale: what Fit reads and what it does with it, plus the privacy
 * policy. Health Connect opens this from its own screens — the "read our privacy policy" link on
 * the consent sheet (`androidx.health.connect.action.SHOW_PERMISSIONS_RATIONALE`, Android 13 and
 * below) and the app's entry in Settings → Health Connect (`VIEW_PERMISSION_USAGE` +
 * `HEALTH_PERMISSIONS`, via the `ViewPermissionUsageActivity` alias, Android 14+). Both are
 * declared in the manifest; Health Connect refuses to show its consent sheet to an app that
 * declares neither.
 *
 * ⚠ A screen of its own, not `MainActivity`: both used to point at the launcher activity, so the
 * link opened the app — and its launch-time consent prompt — instead of an explanation.
 *
 * ⚠ The copy must stay true to the manifest: Fit declares **read** permissions only (the debug-only
 * WRITE set belongs to the screenshot seed, `src/debug/AndroidManifest.xml`), so the text says Fit
 * never writes. A new READ permission means a new item in `fit_hc_rationale_reads`.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TenshuTheme {
                HealthPermissionsRationale(onClose = ::finish)
            }
        }
    }
}

@Composable
private fun HealthPermissionsRationale(onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.fit_hc_rationale_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Paragraph(stringResource(R.string.fit_hc_rationale_reads))
            Paragraph(stringResource(R.string.fit_hc_rationale_read_only))
            Paragraph(stringResource(R.string.fit_hc_rationale_storage))
            Paragraph(stringResource(R.string.fit_hc_rationale_control))
            TextButton(onClick = rememberPrivacyPolicyOpener()) { Text(privacyPolicyLabel()) }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.fit_hc_rationale_close))
            }
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
