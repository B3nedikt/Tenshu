package app.tenshu.reachability

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ⚠⚠ The Android/desktop half of [RealmProbe]'s contract (ADR 0025 §8.6): an engine
 * with no probe reads as writable, so an app whose Android/desktop wiring built one without a probe
 * would silently open its whole realm gate. The parameter is required, so nothing can omit it — this
 * test closes the other hole: an Android/desktop wiring module passing [RealmProbe.None].
 *
 * It scans the wiring sources each first-party Compose app ships on Android and desktop — the
 * `*-shared-ui` DI modules and the `*-android` / `*-desktop` shells — and requires a
 * [RealmProbe.Wired] and no [RealmProbe.None] in them. iOS, the web runtime and tests are exactly
 * where `None` belongs, and are not scanned.
 */
class RealmProbeWiringTest {

    private val apps = listOf("calendar", "contacts", "drive", "fit", "home", "maps", "mia", "photos", "timeline")

    /** The repository root, from this module's directory (Gradle runs tests there). */
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun wiringSources(app: String): List<File> {
        val dirs = listOf(
            File(root, "$app-shared-ui/src/commonMain/kotlin/app/tenshu/$app/di"),
            File(root, "$app-shared-ui/src/androidMain"),
            File(root, "$app-shared-ui/src/desktopMain"),
            File(root, "$app-android/src"),
            File(root, "$app-desktop/src"),
        )
        return dirs.filter { it.exists() }.flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
    }

    @Test
    fun everyAndroidAndDesktopWiringPassesARealProbeAndNeverNone() {
        val problems = mutableListOf<String>()
        for (app in apps) {
            val sources = wiringSources(app)
            if (sources.isEmpty()) { problems += "$app: no wiring sources found (did the layout move?)"; continue }
            val text = sources.associateWith { it.readText() }
            if (text.values.none { "RealmProbe.Wired" in it }) {
                problems += "$app: no RealmProbe.Wired in its Android/desktop wiring"
            }
            text.filterValues { "RealmProbe.None(" in it }.keys.forEach {
                problems += "$app: RealmProbe.None in ${it.relativeTo(root)} — Android/desktop must wire a real probe"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
        assertTrue(true)
    }
    /**
     * ⚠⚠ Both halves, in every app root. The reason must be read with its
     * current value — `collectAsState()` on the `StateFlow`, never `collectAsState(initial = null)`,
     * which renders the interactive shell for the first frame while the verdict is already PENDING.
     * And the checking gate's retry must be wired to the engine (`onRetry`), not left to restart its
     * own timer.
     */
    @Test
    fun everyAppRootReadsTheReasonSynchronouslyAndWiresTheRetry() {
        val problems = mutableListOf<String>()
        for (app in apps) {
            val file = File(root, "$app-shared-ui/src/commonMain/kotlin/app/tenshu/$app/ui/App.kt")
            if (!file.exists()) { problems += "$app: no App.kt (did the layout move?)"; continue }
            val text = file.readText()
            if (Regex("""(?i)reason(\(\))?\s*\.collectAsState\(\s*initial""").containsMatchIn(text)) {
                problems += "$app: the reason is collected with an `initial` — use collectAsState() on the StateFlow"
            }
            if ("AppControlCheckingGate(onRetry = " !in text) {
                problems += "$app: the root does not show AppControlCheckingGate with a wired onRetry"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
