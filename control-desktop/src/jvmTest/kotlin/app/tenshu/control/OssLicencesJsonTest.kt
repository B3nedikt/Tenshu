package app.tenshu.control

import app.tenshu.ui.legal.parseOssLicences
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The open-source notices the Control desktop binary ships (`docs/oss-licences.md`), as the root
 * build's AboutLibraries convention generates them into `jvmMain`'s resources.
 *
 * It stands in for all twenty Android/desktop apps: they share the one convention, so a break in
 * the wiring (the JSON missing from the classpath, an empty list, licence texts dropped) shows here.
 */
class OssLicencesJsonTest {

    private val components by lazy {
        val json = javaClass.getResourceAsStream("/aboutlibraries.json")?.bufferedReader()?.use { it.readText() }
            ?: fail("/aboutlibraries.json is not on the classpath: the oss-licences convention did not run")
        parseOssLicences(json)
    }

    @Test
    fun theListIsNotEmptyAndNamesWhatControlLinks() {
        val ids = components.map { it.id }.toSet()
        assertTrue(ids.size > 50, "only ${ids.size} libraries: the dependency walk lost most of the graph")
        listOf(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            "io.ktor:ktor-client-core",
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.compose.material3:material3",
        ).forEach { assertTrue(it in ids, "$it is linked by Control but missing from its notices") }
    }

    @Test
    fun everyLibraryNamesALicence() {
        val unlicensed = components.filter { it.licences.isEmpty() }.map { it.id }
        assertTrue(unlicensed.isEmpty(), "no licence recorded for: $unlicensed")
    }

    /**
     * ⚠⚠ Apache-2.0 §4(a) and MIT require the licence TEXT with the binary, not only its name. The
     * plugin runs offline and takes the texts from `config/oss-licences/licenses/`; a licence that
     * loses its file there would silently ship as a bare name.
     */
    @Test
    fun theLicencesThatRequireTheirTextCarryIt() {
        val used = components.flatMap { it.licences }.distinct()
        listOf("Apache License 2.0", "MIT License").forEach { name ->
            val licence = assertNotNull(used.firstOrNull { it.name == name }, "$name not used?")
            assertTrue(!licence.text.isNullOrBlank(), "$name ships without its text")
        }
        val textless = used.filter { it.text.isNullOrBlank() }.map { it.name }
        assertTrue(textless.isEmpty(), "licences without text in Control desktop: $textless")
    }

    /**
     * ⚠⚠ MIT, BSD and ISC require the ORIGINAL copyright line, and the checked-in SPDX texts are
     * templates. The build gives each such library its own text with the real line (from the jar's
     * LICENSE, else `config/oss-licences/copyrights.json`); a template reaching the binary means a
     * library slipped past that, and its notice would be legally empty.
     */
    @Test
    fun noShippedLicenceTextIsATemplate() {
        val placeholder = Regex("<(year|owner|copyright holders?)>")
        val templated = components.flatMap { c -> c.licences.filter { placeholder.containsMatchIn(it.text.orEmpty()) }.map { "${c.id}: ${it.name}" } }
        assertTrue(templated.isEmpty(), "licence texts still carrying a template placeholder: $templated")

        fun copyrightOf(id: String) = components.first { it.id == id }.licences.mapNotNull { it.text }
            .flatMap { it.lines() }.filter { it.trimStart().startsWith("Copyright") }
        // Read from the jar's own LICENSE.txt …
        assertTrue(copyrightOf("org.slf4j:slf4j-api").any { "QOS.ch" in it }, "slf4j-api lost its copyright line")
        // … and from the hand-kept map, for an artifact that carries none.
        assertTrue(copyrightOf("io.github.alexzhirkevich:qrose").any { "Alexander Zhirkevich" in it }, "qrose lost its copyright line")
    }

    /**
     * ⚠⚠ Apache-2.0 §4(d): a work that ships a NOTICE file must pass it on. PDFBox (and fontbox,
     * pdfbox-io) and commons-logging carry one in `META-INF/`, ONNX Runtime a ThirdPartyNotices file;
     * the build copies each into the library's notices as a "NOTICE" entry.
     */
    @Test
    fun apacheNoticeFilesTravelWithTheirLibraries() {
        fun notice(id: String) = components.firstOrNull { it.id == id }?.licences?.firstOrNull { it.name == "NOTICE" }?.text
        listOf(
            "org.apache.pdfbox:pdfbox" to "Apache PDFBox",
            "org.apache.pdfbox:fontbox" to "Apache FontBox",
            "commons-logging:commons-logging" to "Apache Commons Logging",
            "com.microsoft.onnxruntime:onnxruntime" to "THIRD PARTY SOFTWARE NOTICES",
        ).forEach { (id, expected) ->
            val text = assertNotNull(notice(id), "$id ships without its NOTICE file")
            assertTrue(expected in text, "$id's NOTICE does not look like its own: ${text.take(80)}")
        }
    }
}
