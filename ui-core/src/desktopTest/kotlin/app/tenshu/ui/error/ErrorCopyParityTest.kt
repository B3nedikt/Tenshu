package app.tenshu.ui.error

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Three-way parity between the error copy catalogue's renderers, and completeness against the
 * whole `TError` taxonomy — `core-error-copy-catalogue.md` §5.5.
 *
 * ⭐⭐ **Why this reads source files instead of linking modules.** The three renderers deliberately
 * cannot see each other: `:ui-core` is Compose, `tenshu-sdk-ios-core` is Kotlin/Native, and neither may
 * depend on the other (that separation is the whole point of §5.1). So there is no compile-time
 * seam where a drifted catalogue could be caught, and "kept in step by hand" was the honest
 * description until this test existed.
 *
 * ⚠⚠ It matters more than it looks. Kotlin/Native does not cross-compile Apple targets off macOS
 * and the repo's iOS CI lane is currently blocked, so on most machines **nothing else in the build
 * looks at `ErrorCopySurface.kt` at all**. This test is a JVM reader of that file's text: it cannot
 * prove the file compiles, but it does prove its catalogue matches the one users see on Android and
 * desktop — which is the likelier mistake when adding copy.
 *
 * ⚠ Reading sources by relative path is fragile in the usual way: it assumes a Gradle `Test` task
 * runs with the module directory as its working directory, and it hard-codes two paths. Both are
 * asserted rather than assumed — a moved file fails loudly here instead of silently passing.
 */
class ErrorCopyParityTest {

    private val repoRoot = File("..").canonicalFile
    private val stringsXml = File("src/commonMain/composeResources/values/strings.xml").canonicalFile
    private val iosSurface = File(
        repoRoot,
        "tenshu-sdk-ios-core/src/iosMain/kotlin/org/tenshu/sdk/ios/ErrorCopySurface.kt",
    )

    /** `<string name="error_foo">` -> `error_foo`. */
    private fun xmlKeys(): Set<String> =
        Regex("""<string\s+name="(error_[a-z0-9_]+)"""")
            .findAll(stringsXml.readText())
            .map { it.groupValues[1] }
            .toSet()

    /** `"error_foo" -> "..."` in the iOS `when`. */
    private fun iosKeys(): Set<String> =
        Regex(""""(error_[a-z0-9_]+)"\s*->""")
            .findAll(iosSurface.readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun bothCatalogueSourcesExistWhereExpected() {
        assertTrue(stringsXml.isFile, "Not found: $stringsXml — update this test if it moved")
        assertTrue(iosSurface.isFile, "Not found: $iosSurface — update this test if it moved")
    }

    /**
     * The Compose module's own two halves: the `strings.xml` entries and the `CATALOGUE` map that
     * reaches them. Compose Resources offers no lookup by name, so these are a two-place edit
     * (`ErrorCopy.kt` KDoc) and this is the half of the guard that is checkable in one module.
     */
    @Test
    fun composeCatalogueMapMatchesItsStringsXml() {
        val xml = xmlKeys() - FALLBACK
        val map = CATALOGUE.keys
        assertTrue(
            xml == map,
            "strings.xml and CATALOGUE disagree. Only in XML: ${xml - map}; only in map: ${map - xml}",
        )
    }

    /**
     * ⚠⚠ The cross-platform half: iOS renders the same keys or its users get the fallback for copy
     * that exists everywhere else. A key here and not there is not a compile error on any machine.
     */
    @Test
    fun iosCatalogueRendersTheSameKeysAsCompose() {
        val compose = xmlKeys() - FALLBACK
        val ios = iosKeys()
        assertTrue(
            compose == ios,
            "Compose and iOS catalogues disagree. Missing on iOS: ${compose - ios}; " +
                "extra on iOS: ${ios - compose}",
        )
    }

    /**
     * Completeness, automatically: every concrete `TError` in the tree has copy.
     *
     * ⭐ This is what `ErrorCopyCatalogueTest`'s hand-maintained list could not do — a `TError`
     * added tomorrow in any module now fails here until somebody writes its sentence, rather than
     * silently rendering "Something went wrong". §5.0's rule only holds if adding an error makes
     * you write copy in the catalogue; this is what makes that true.
     *
     * ⚠ Scans sources rather than reflecting over classes, because no single module can see every
     * service (the same reason the parity halves above read files). Test sources and `build/` are
     * excluded; the `tenshu/core/error` base id is not a concrete error and is skipped.
     */
    @Test
    fun everyErrorTypeInTheTreeHasCopy() {
        val idPattern = Regex("""const val ID = "([^"]*\berror\b[^"]*)"""")
        val ids = repoRoot.walkTopDown()
            .onEnter { it.name !in setOf("build", ".git", "node_modules", ".gradle") }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains("/src/commonTest/") || it.path.contains("Test.kt") }
            .flatMap { f -> idPattern.findAll(f.readText()).map { it.groupValues[1] } }
            .filterNot { it == TERROR_BASE_ID }
            // `ErrorEvent`'s ids are log composites, not `TError`s — they are never rendered.
            .filterNot { it.startsWith("tenshu/logging/") }
            // Ids built by interpolation (`"${'$'}{Service.ID}/error/..."`) cannot be read
            // statically; they are covered by the hand-listed taxonomy in ErrorCopyCatalogueTest.
            .filterNot { it.contains("$") }
            .toSet()

        assertTrue(ids.size > 10, "Scan found only ${ids.size} error ids — the scan itself broke")

        val uncovered = ids.filter { app.tenshu.error.errorCopyKeyForId(it).value !in CATALOGUE }
        assertTrue(
            uncovered.isEmpty(),
            "These error types have no catalogue entry — add one to strings.xml, CATALOGUE and " +
                "ErrorCopySurface: " +
                uncovered.joinToString { "$it -> ${app.tenshu.error.errorCopyKeyForId(it).value}" },
        )
    }

    private companion object {
        const val TERROR_BASE_ID = "tenshu/core/error"
        val FALLBACK = setOf("error_unexpected")
    }
}
