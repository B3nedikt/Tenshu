package app.tenshu.ui.legal

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * `res/raw/aboutlibraries.json`, which the AboutLibraries plugin generates into every Android app
 * per variant (root `build.gradle.kts`). Looked up by name because `:ui-core` is a library and the
 * resource belongs to whichever app links it; ⚠ `res/raw/oss_licences_keep.xml` keeps it through
 * the release build's resource shrinker, which cannot see a by-name lookup.
 */
@Composable
internal actual fun bundledLicencesReader(): () -> String? {
    val context = LocalContext.current.applicationContext
    return remember(context) { { readRawLicences(context) } }
}

@SuppressLint("DiscouragedApi")
private fun readRawLicences(context: Context): String? {
    val id = context.resources.getIdentifier(BUNDLED_LICENCES_NAME, "raw", context.packageName)
    if (id == 0) return null
    return context.resources.openRawResource(id).bufferedReader().use { it.readText() }
}
