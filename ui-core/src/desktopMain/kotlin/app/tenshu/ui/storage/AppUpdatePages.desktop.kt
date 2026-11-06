package app.tenshu.ui.storage

import androidx.compose.runtime.Composable

/** No update route on this platform yet (`core-schema-skew-surface.md` §8.5): the words carry the fix. */
@Composable
actual fun rememberAppUpdatePages(): AppUpdatePages = AppUpdatePages.NONE
