package org.tenshu.inference.proxy.catalog

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ⚠⚠ The names the client GETs and the names the publisher uploads are one contract written twice —
 * once in Kotlin, once in `scripts/models-publish.sh` — and nothing else connects them. They
 * disagreed (`models.json` against `catalog.json`), which no unit test on either side could see: every
 * client test served the client's own name, and the script has no tests. A publish that succeeds and a
 * device that answers "can't reach the model list" would have been the first symptom.
 *
 * So this reads the script. It asserts the upload targets, not the whole script: a rewritten publisher
 * that still uploads these two keys passes.
 */
class PublishedCatalogPathsTest {

    private val script: String = repoRoot().resolve("scripts/models-publish.sh").readText()

    @Test
    fun thePublisherUploadsTheCatalogWhereTheClientLooks() {
        assertTrue(
            script.contains("\"s3://\$SPACES_BUCKET/${HttpModelCatalogSource.CATALOG_PATH}\""),
            "models-publish.sh does not upload s3://\$SPACES_BUCKET/${HttpModelCatalogSource.CATALOG_PATH}",
        )
    }

    @Test
    fun thePublisherUploadsTheSignatureWhereTheClientLooks() {
        assertTrue(
            script.contains("\"s3://\$SPACES_BUCKET/${HttpModelCatalogSource.SIGNATURE_PATH}\""),
            "models-publish.sh does not upload s3://\$SPACES_BUCKET/${HttpModelCatalogSource.SIGNATURE_PATH}",
        )
    }

    /** The test JVM runs in the module directory; the repository root is the one with the settings file. */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "no settings.gradle.kts above ${System.getProperty("user.dir")}" }
    }
}
