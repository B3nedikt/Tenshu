package app.tenshu.ui.resources

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every Compose Multiplatform `strings.xml` in the tree is authored against what CMP's resource
 * pipeline actually does with it — not against what Android's `aapt` would do.
 *
 * ⚠⚠ **The two rules, from the CMP 1.12.0 source, not from the docs.** They are narrower than
 * Android's and the difference reaches the screen:
 *
 * 1. **Escapes.** The build-time converter (`PrepareComposeResources.handleSpecialCharacters`)
 *    decodes exactly `\n`, `\t`, `\uXXXX` and `\\`. Everything else — `\'`, `\"`, `\@`, `\?` —
 *    survives as a literal backslash in the rendered text. This shipped: Drive's
 *    "File already exists" dialog rendered `A file named \"report.pdf\" already exists.`
 * 2. **Format specifiers.** The runtime formatter (`StringResourcesUtils.replaceWithArgs`)
 *    substitutes only `%N$s` and `%N$d`. A bare `%s` / `%d`, or any other conversion
 *    (`%1$.1f`, `%,d`, `%1$S`), is left untouched and the user sees the specifier — the
 *    sync banner rendered a literal `%d` on device (2026-08-02), and the move picker's title
 *    read `Move "%s"`.
 *
 * Both classes are invisible to compilation and to every existing test, because the accessor
 * generator does not care what the text says and no screen test pins that copy. So this is a
 * JVM reader of the XML, the same shape as [app.tenshu.ui.error.ErrorCopyParityTest], and for the
 * same reason: there is no compile-time seam.
 *
 * ⚠ Android `res/values/strings.xml` files are deliberately **not** scanned: those go through
 * `aapt` + `String.format`, where `\'` is *required* and `%d` is fine.
 *
 * ⚠ Reading sources by relative path assumes a Gradle `Test` task runs with the module directory
 * as its working directory. The scan asserts it found catalogues rather than passing on an empty
 * set. ⚠ The catalogues are declared as inputs of `desktopTest` in this module's build script —
 * without that, editing another module's `strings.xml` left this task UP-TO-DATE and a stale
 * green stood until `--rerun` (2026-09-12). `scripts/run-gate.sh` clears results regardless.
 */
class StringResourceFormatTest {

    private val repoRoot = File("..").canonicalFile

    private fun composeCatalogues(): List<File> = repoRoot.walkTopDown()
        .onEnter { it.name !in setOf("build", ".git", "node_modules", ".gradle") }
        .filter { it.isFile && it.name == "strings.xml" && it.path.contains("/composeResources/") }
        .toList()

    /** `<string name="k">text</string>` and every `<item>` of a `<plurals>` / `<string-array>`. */
    private fun values(xml: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).forEach { out += it.groupValues[1] to it.groupValues[2] }
        Regex("""<(plurals|string-array)\s+name="([^"]+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).forEach { block ->
                val name = block.groupValues[2]
                Regex("""<item[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(block.groupValues[3])
                    .forEach { out += name to it.groupValues[1] }
            }
        return out
    }

    @Test
    fun theScanFindsTheCatalogues() {
        val found = composeCatalogues()
        assertTrue(found.size >= 5, "Found only ${found.size} composeResources strings.xml — the scan itself broke")
    }

    /** Rule 1: nothing CMP will not decode. */
    @Test
    fun noEscapeCmpLeavesLiteral() {
        val offenders = composeCatalogues().flatMap { file ->
            values(file.readText()).filter { (_, text) -> UNDECODED_ESCAPE.containsMatchIn(text) }
                .map { (name, text) -> "${file.relativeTo(repoRoot)}: $name: ${text.trim()}" }
        }
        assertTrue(
            offenders.isEmpty(),
            "CMP decodes only \\n, \\t, \\uXXXX and \\\\ — anything else renders with a literal " +
                "backslash. Write a bare ' or a typographic “…” instead:\n" + offenders.joinToString("\n"),
        )
    }

    /** Rule 2: only `%N$s` / `%N$d`, and positions contiguous from 1. */
    @Test
    fun everySpecifierIsOneCmpSubstitutes() {
        val offenders = composeCatalogues().flatMap { file ->
            values(file.readText()).mapNotNull { (name, text) ->
                val bad = CONVERSION.findAll(text).map { it.value }.filterNot { CMP_SUBSTITUTES.matches(it) }.toList()
                val positions = POSITION.findAll(text).map { it.groupValues[1].toInt() }.toSortedSet()
                val gap = positions.isNotEmpty() && positions != (1..positions.last()).toSet()
                when {
                    bad.isNotEmpty() -> "${file.relativeTo(repoRoot)}: $name: $bad in ${text.trim()}"
                    gap -> "${file.relativeTo(repoRoot)}: $name: positions $positions are not 1..n in ${text.trim()}"
                    else -> null
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "CMP's formatter substitutes only %N\$s and %N\$d; anything else is shown to the user " +
                "verbatim (a bare % with no conversion letter is fine):\n" + offenders.joinToString("\n"),
        )
    }

    private companion object {
        /** A backslash not starting `\n`, `\t`, `\uXXXX` or `\\`. */
        val UNDECODED_ESCAPE = Regex("""\\(?!n|t|u[0-9a-fA-F]{4}|\\)""")

        /**
         * A `%` that reads as a conversion: optional `N$`, flags, width, precision, then a letter.
         * A bare `%` followed by a space, punctuation or end of text (`80%`, `%1$d%`) is not one.
         */
        val CONVERSION = Regex("""%(?:\d+\$)?[-+#0,(]*\d*(?:\.\d+)?[a-zA-Z]""")
        val CMP_SUBSTITUTES = Regex("""%\d+\$[ds]""")
        val POSITION = Regex("""%(\d+)\$""")
    }
}
