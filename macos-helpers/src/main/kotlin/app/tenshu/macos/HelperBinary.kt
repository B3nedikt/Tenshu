package app.tenshu.macos

import java.io.File

/**
 * Locates one of the bundled macOS helper executables, preferring the **signed
 * copy embedded in the `.app` bundle** (`Contents/Helpers/<name>`, beside the
 * tray agent) so it's covered by the app's signature/notarization. Falls back to
 * extracting the binary that rides in an owning module's jar as a classpath
 * resource to a temp file (unsigned dev runs — an extracted unsigned binary may
 * be killed under hardened runtime, hence the bundle copy is the production
 * path). See `docs/proposals/desktop-qr-scanning.md` §7–§8.
 *
 * Resolution is by [name] only, so this class doesn't care which module *built*
 * the binary: `image-decode-helper` is compiled here, `qr-scan-helper` in
 * `:ui-core` (it belongs with the camera code), and both are found the same way.
 * Helpers are swiftc-built on a Mac host and simply absent elsewhere — [resolve]
 * returning `null` is the ordinary "not on macOS / not built" answer, never an
 * error.
 */
class HelperBinary(private val name: String) {

    @Volatile
    private var cached: File? = null

    fun resolve(): File? {
        cached?.let { if (it.canExecute()) return it }
        return (bundledHelper() ?: extractedHelper())?.also { cached = it }
    }

    /**
     * The signed helper embedded in the running jpackage bundle. The native
     * launcher runs from `<app>/Contents/MacOS/<launcher>`, so the helper sits at
     * `<app>/Contents/Helpers/<name>`. Null for dev/unpackaged launches.
     */
    private fun bundledHelper(): File? {
        val exec = ProcessHandle.current().info().command().orElse(null) ?: return null
        val contents = File(exec).parentFile?.parentFile ?: return null
        return File(contents, "Helpers/$name").takeIf { it.isFile && it.canExecute() }
    }

    private fun extractedHelper(): File? {
        val stream = HelperBinary::class.java.getResourceAsStream("/$name") ?: return null
        return stream.use { input ->
            val tmp = File.createTempFile(name, "").apply { deleteOnExit() }
            tmp.outputStream().use { input.copyTo(it) }
            tmp.setExecutable(true)
            tmp
        }
    }
}
