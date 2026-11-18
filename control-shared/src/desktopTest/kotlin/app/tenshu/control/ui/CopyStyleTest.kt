package app.tenshu.control.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * House style for Control's user-facing copy: no dashes as punctuation. An em dash (or the spaced
 * en dash German typography uses for the same job) reads as machine-written, so prose says it with
 * a full stop, a comma, a colon or parentheses instead. A string that is ONLY dashes and spaces is
 * a placeholder glyph (an empty value, the SAS before it arrives) and is allowed. Comments are not
 * copy and are not checked. The apex holds its locales to the same rule in
 * `web/apex/src/copyStyle.test.ts`; the iOS twin of these strings lives in `control-ios/Sources`.
 */
class CopyStyleTest {

    // Gradle runs desktopTest with the module directory as the working directory.
    private val resources = File("src/commonMain/composeResources")

    @Test
    fun noComposeStringUsesADashAsPunctuation() {
        val files = resources.listFiles { f -> f.isDirectory && f.name.startsWith("values") }
            ?.map { File(it, "strings.xml") }?.filter { it.isFile }.orEmpty()
        assertTrue(files.size >= 2, "Found only $files under ${resources.absolutePath}; update this test if they moved")

        val entry = Regex("""<(string|item)\b([^>]*)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
        val offenders = files.flatMap { file ->
            val xml = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            entry.findAll(xml).map { it.groupValues[2].trim() to it.groupValues[3] }
                .filter { (_, text) -> "—" in text || " – " in text }
                .filterNot { (_, text) -> text.all { it.isWhitespace() || it == '—' || it == '–' } }
                .map { (attrs, text) -> "${file.parentFile.name} $attrs: $text" }
                .toList()
        }
        assertEquals(emptyList(), offenders, "Rewrite these without a dash as punctuation")
    }
}
