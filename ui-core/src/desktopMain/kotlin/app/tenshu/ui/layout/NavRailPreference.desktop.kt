package app.tenshu.ui.layout

import java.io.File
import java.util.Properties

/**
 * A [NavRailPreference] backed by `<storageDir>/<key>.navrail.properties`.
 *
 * Deliberately mirrors `rememberPersistedWindowState`: same directory, same `Properties` format,
 * same swallow-everything error policy. A corrupted or unreadable file simply reads as "no stored
 * choice", so the size-class default applies and the next toggle rewrites it.
 *
 * ⚠ Pass the app's real data directory (`DesktopAppPaths.root()`), the same value already handed
 * to `rememberPersistedWindowState`. The `~/.tenshu` fallback is **not reachable from a sandboxed
 * signed build** — relying on it there degrades silently to session-only persistence, which is
 * exactly the kind of quiet no-op that is hard to notice.
 *
 * @param key        unique identifier for the window (e.g. `"drive"`, `"control"`), so two apps
 *                   do not share one another's rail state.
 * @param storageDir directory the properties file lives in.
 */
fun filePersistedNavRailPreference(
    key: String,
    storageDir: File = File(System.getProperty("user.home") ?: ".", ".tenshu"),
): NavRailPreference = FileNavRailPreference(key, storageDir)

private class FileNavRailPreference(
    key: String,
    storageDir: File,
) : NavRailPreference {

    private val file: File by lazy {
        runCatching { storageDir.mkdirs() }
        File(storageDir, "$key.navrail.properties")
    }

    override fun read(): Boolean? {
        if (!file.exists()) return null
        return runCatching {
            val props = Properties().apply { file.inputStream().use { load(it) } }
            props.getProperty(EXPANDED)?.toBooleanStrictOrNull()
        }.getOrNull()
    }

    override fun write(expanded: Boolean) {
        runCatching {
            val props = Properties().apply { setProperty(EXPANDED, expanded.toString()) }
            file.outputStream().use { props.store(it, "Tenshu navigation rail state") }
        }
    }

    private companion object {
        const val EXPANDED = "expanded"
    }
}
