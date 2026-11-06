package org.tenshu.models

import java.io.File
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * The model-catalog build tool (IN-2 / IN-D10). Two commands, mirroring `:tiles-signer`:
 *
 * ```
 * ./gradlew :model-signer:run --args="generate-key --out /secure/model-keys/model-signing.key"
 * ./gradlew :model-signer:run --args="build-and-sign \
 *     --manifest deploy/models/models.json \
 *     --artifacts deploy/models/build/artifacts \
 *     --out deploy/models/build/catalog.json \
 *     --key /secure/model-keys/model-signing.key"
 * ```
 *
 * ⚠⚠ **`generate-key` writes a secret.** It refuses to overwrite, and prints the PUBLIC key so it
 * can be pinned in `MODEL_CATALOG_PUBLIC_KEY_HEX`. Losing the private half means every installed
 * device stops trusting new catalogs until an app release ships a new pin — which is the whole
 * reason IN-D10 pins the key rather than each hash: rotation is possible, not free.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        // ⚠⚠ The commands THROW; only this entry point turns a failure into an exit code. An
        // `exitProcess` inside the logic kills any test JVM that drives it, which is exactly how
        // the overwrite-refusal path came to be untested.
        "generate-key" -> runCommand { generateKey(args.drop(1).toArgMap()) }
        "build-and-sign" -> runCommand { buildAndSign(args.drop(1).toArgMap()) }
        else -> {
            System.err.println(
                """
                usage:
                  generate-key    --out <keyfile>
                  build-and-sign  --manifest <models.json> --artifacts <dir> --out <catalog.json> --key <keyfile>
                                  [--build-date <YYYY-MM-DD>]
                """.trimIndent()
            )
            exitProcess(2)
        }
    }
}

// `internal` so `GenerateKeyTest` can drive it directly. `main` is the only other caller.
internal fun generateKey(args: Map<String, String>) {
    val out = File(args.require("--out"))
    // ⚠⚠ Never clobber a signing key. An accidental second `generate-key` over a live key would
    // orphan every device that has the old public half pinned, and the old seed is unrecoverable.
    if (out.exists()) {
        error("${out.path} already exists — refusing to overwrite a signing key.")
    }
    // ⚠⚠ Create the directory OWNER-ONLY, and fail loudly if it cannot be created. The previous
    // shape was `out.parentFile?.mkdirs()` with the result dropped, so an uncreatable path surfaced
    // as a `FileNotFoundException` from the WRITE — naming the key file, and saying nothing about
    // the directory that was the actual problem. On modern macOS `/` is a read-only filesystem, so
    // the runbook's own `/secure/model-keys` example could not be created by anyone, root included.
    val parent = out.absoluteFile.parentFile
    if (parent != null && !parent.isDirectory) {
        if (!parent.mkdirs()) {
            error(
                "could not create ${parent.path} — the signing key was NOT written. A top-level " +
                    "path like /secure cannot be created on macOS ('/' is read-only). Choose " +
                    "somewhere writable, e.g. --out \$HOME/.tenshu/model-keys/model-signing.key"
            )
        }
        // ⚠ 0700 on the directory, explicitly. A key directory that inherits the shell's umask is
        // group- and world-readable on a default macOS setup, which is not a property to leave to
        // whatever the operator's environment happens to be.
        parent.setReadable(false, false); parent.setReadable(true, true)
        parent.setWritable(false, false); parent.setWritable(true, true)
        parent.setExecutable(false, false); parent.setExecutable(true, true)
    }
    val keypair = ModelSigner().generateKeypair()
    out.writeText(keypair.privateKeySeed.toHex())
    // Owner-only. Best effort: on a filesystem without POSIX permissions this is a no-op, which is
    // why the how-to also says to keep the key off shared machines.
    runCatching { out.setReadable(false, false); out.setReadable(true, true); out.setWritable(false, false) }
    println("Wrote private seed to ${out.path} (owner-read only)")
    println()
    println("Pin this in inference-service-proxy MODEL_CATALOG_PUBLIC_KEY_HEX:")
    println("  ${keypair.publicKey.toHex()}")
}

private fun buildAndSign(args: Map<String, String>) {
    val manifestFile = File(args.require("--manifest"))
    val artifactRoot = File(args.require("--artifacts"))
    val out = File(args.require("--out"))
    val keyFile = File(args.require("--key"))
    val buildDate = args["--build-date"] ?: LocalDate.now().toString()

    require(manifestFile.isFile) { "manifest not found: ${manifestFile.absolutePath}" }
    require(artifactRoot.isDirectory) { "artifact root not a directory: ${artifactRoot.absolutePath}" }
    require(keyFile.isFile) { "signing key not found: ${keyFile.absolutePath}" }

    val manifest = ModelCatalogBuilder.parseManifest(manifestFile.readText())
    val catalog = ModelCatalogBuilder(artifactRoot).build(manifest, buildDate)

    val seed = keyFile.readText().trim().hexToBytes()
    val signer = ModelSigner()
    val signature = signer.sign(catalog.toByteArray(), seed)

    out.parentFile?.mkdirs()
    out.writeText(catalog)
    File(out.parentFile, out.name + ".sig").writeBytes(signature)

    println("Wrote ${out.path} + ${out.name}.sig  (buildDate=$buildDate)")
    manifest.models.forEach { m ->
        println("  ${m.id} v${m.version} — ${m.artifacts.size} artifact(s), runtime='${m.runtime}'")
    }
    println()
    // ⚠ Printed on every sign, not just on generate-key: a catalog signed with the WRONG key is
    // silently unverifiable at the client, and comparing this line against the pinned constant is
    // the only local check that catches it.
    println("Signed with public key: ${signer.publicKeyFor(seed).toHex()}")
    println("  — this MUST equal MODEL_CATALOG_PUBLIC_KEY_HEX, or every device refuses the catalog.")
}

private fun List<String>.toArgMap(): Map<String, String> =
    chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }

private fun Map<String, String>.require(flag: String): String =
    this[flag] ?: error("missing $flag")

/**
 * Runs one command, turning any failure into a printed error and a non-zero exit.
 *
 * ⚠ The only `exitProcess` for a command failure lives here. The commands throw, so a test can
 * drive them and observe the refusal instead of having its JVM killed mid-assertion.
 */
private fun runCommand(command: () -> Unit) {
    runCatching { command() }.onFailure { failure ->
        System.err.println("ERROR: ${failure.message ?: failure.toString()}")
        exitProcess(1)
    }
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "signing key is not valid hex (odd length)" }
    return ByteArray(length / 2) { i ->
        ((digitToInt(this[i * 2]) shl 4) or digitToInt(this[i * 2 + 1])).toByte()
    }
}

private fun digitToInt(c: Char): Int =
    c.digitToIntOrNull(16) ?: throw IllegalArgumentException("signing key is not valid hex")
