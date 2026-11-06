package app.tenshu.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * `finish()`, which is what system back does at the root of a task — the system then brings forward
 * whatever was in front before, i.e. the app that opened this one (`control-cross-app-open.md`
 * CO-D11).
 *
 * ⚠ `null` when there is no activity in scope (a `@Preview`, a test harness), so a caller cannot
 * accidentally depend on one being there.
 */
@Composable
actual fun rememberAppExit(): (() -> Unit)? {
    val activity = LocalActivity.current ?: return null
    return remember(activity) { { activity.finish() } }
}
