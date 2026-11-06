package org.tenshu.inference.proxy.coreml

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.proxy.IosFileDatabase
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random

/**
 * The tiny ML Programs `scripts/inference/export-coreml-towers.py --fixture` writes, and a scratch
 * cache to compile them into.
 *
 * ⚠ A Kotlin/Native test binary carries no resources. Gradle passes the fixture directory in
 * `TENSHU_COREML_FIXTURES` (`inference-service-proxy/build.gradle.kts`); without it every fixture
 * case skips with a printed reason rather than failing.
 */
@OptIn(ExperimentalForeignApi::class)
internal class CoreMlFixtures {

    val root: String = "${NSTemporaryDirectory()}coreml-test-${Random.nextLong()}"
    val files = IosFileDatabase(root = root)

    fun cache(compiler: CoreMlCompiler = SystemCoreMlCompiler): CoreMlModelCache =
        CoreMlModelCache(files, root, compiler, osBuild = { "test-build" })

    /** Copies the fixture archive into the scratch store and returns its path there, or `null`. */
    fun archive(name: String): String? {
        val directory: String? = NSProcessInfo.processInfo.environment["TENSHU_COREML_FIXTURES"] as? String
        if (directory == null) {
            println("SKIPPED: TENSHU_COREML_FIXTURES is not set, so $name cannot be read by path")
            return null
        }
        val source = "$directory/$name.mlpackage.tar"
        if (!NSFileManager.defaultManager.fileExistsAtPath(source)) {
            println("SKIPPED: no fixture at $source")
            return null
        }
        val target = "archives/$name.tar"
        NSFileManager.defaultManager.createDirectoryAtPath("$root/archives", true, null, null)
        NSFileManager.defaultManager.copyItemAtPath(source, "$root/$target", null)
        return target
    }

    /** Compiles the fixture through the real prepare step and returns the `.mlmodelc` path. */
    fun compiled(name: String, spec: CoreMlTowerSpec, digest: String = digestFor(name)): String? {
        val archive: String = archive(name) ?: return null
        return runBlocking { cache().prepare(spec, archive, digest) }
    }

    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    companion object {
        /** A stand-in sha256, distinct per fixture name: the cache takes the digest as a name. */
        fun digestFor(name: String): String =
            name.encodeToByteArray().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                .padEnd(64, '0').take(64)
    }
}
