package org.tenshu.tiles

import app.tenshu.vectortile.TransitLineIndex
import java.io.File

/**
 * Phase-1 manifest signer CLI (`maps-region-delivery.md` §9). Two commands:
 *
 *   keygen --out <dir>
 *       Generate an Ed25519 signing keypair. Writes `<dir>/tiles-signing.key` (the 32-byte seed,
 *       hex — keep secret) + `<dir>/tiles-signing.pub` (the public key, hex — pin in the client).
 *
 *   build-and-sign --regions <regions.json> --extracts <dir> --out <catalog.json> --key <key-hex-file>
 *                  [--hashes <hashes.json>]
 *       Assemble catalog.json from the region definitions + the produced `<id>.pmtiles` extracts,
 *       write it, and write a detached signature `<out>.sig`. Prints the public key.
 *
 *       `--hashes` supplies `{byteSize, sha256}` for bundles that are not on this machine — the
 *       planet-sized `global` region is built on a rented box that must never see the signing key
 *       (D11). `tiles-build.sh` writes `build/hashes.json` there; copy that file (a few hundred
 *       public bytes) to the machine holding the key and sign from it. Local extracts still win.
 *
 * The signature is over the exact bytes written to catalog.json, with the same Ed25519 the client
 * verifies with — one signature stack (D3).
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "keygen" -> keygen(parseFlags(args))
        "build-and-sign" -> buildAndSign(parseFlags(args))
        else -> {
            System.err.println(
                """
                tiles-signer — Phase 1 manifest signer (maps-region-delivery.md §9)

                  keygen --out <dir>
                  build-and-sign --regions <regions.json> --extracts <dir> --out <catalog.json> --key <key-hex-file>
                                 [--hashes <hashes.json>]   # digests for bundles not on this machine (D11)
                                 [--overlays <dir>]         # transit-line overlays (<id>.tlne), §33 L3
                """.trimIndent(),
            )
            kotlin.system.exitProcess(if (args.isEmpty()) 0 else 2)
        }
    }
}

private fun keygen(flags: Map<String, String>) {
    val outDir = File(flags["out"] ?: ".").apply { mkdirs() }
    val kp = ManifestSigner().generateKeypair()
    File(outDir, "tiles-signing.key").writeText(kp.privateKeySeed.toHex())
    File(outDir, "tiles-signing.pub").writeText(kp.publicKey.toHex())
    println("Wrote signing keypair to ${outDir.path}")
    println("Public key (pin in the client): ${kp.publicKey.toHex()}")
}

private fun buildAndSign(flags: Map<String, String>) {
    val defsFile = File(flags.require("regions"))
    val extractsDir = File(flags.require("extracts"))
    val out = File(flags.require("out"))
    val seed = File(flags.require("key")).readText().trim().hexToBytes()

    val defs = CatalogBuilder.parseDefinitions(defsFile)
    val digestFile = flags["hashes"]?.let { path ->
        val digests = CatalogBuilder.parseDigests(File(path))
        check(digests.buildDate == defs.buildDate) {
            "hashes.json is for build ${digests.buildDate} but regions.json says ${defs.buildDate} " +
                "— signing these together would publish a catalog pointing at the wrong dated build."
        }
        digests
    }

    val catalog = CatalogBuilder.buildCatalog(
        defs = defs,
        extractsDir = extractsDir,
        precomputed = digestFile?.bundles.orEmpty(),
        // §33 L3. ⚠ Optional: a build with no overlays produces exactly the catalogue it does today.
        overlaysDir = flags["overlays"]?.let(::File),
        precomputedOverlays = digestFile?.overlays.orEmpty(),
        overlayFormatVersion = flags["overlay-format-version"]?.toIntOrNull() ?: TransitLineIndex.FORMAT_VERSION,
    )
    val bytes = CatalogBuilder.encodeCatalogBytes(catalog)

    val signer = ManifestSigner()
    val signature = signer.sign(bytes, seed)

    out.parentFile?.mkdirs()
    out.writeBytes(bytes)
    File(out.parentFile, "${out.name}.sig").writeBytes(signature)

    val withOverlay = catalog.regions.count { it.overlayPath.isNotEmpty() }
    println("Wrote ${out.path} (${bytes.size} bytes) + ${out.name}.sig over ${catalog.regions.size} region(s)")
    // ⚠ Printed even when zero: a build that silently stopped carrying overlays is the failure this
    // number exists to make visible.
    println("  transit-line overlays: $withOverlay of ${catalog.regions.size} region(s)")
    println("Public key (pin in the client): ${signer.publicKeyFor(seed).toHex()}")
}

private fun parseFlags(args: Array<String>): Map<String, String> {
    val flags = mutableMapOf<String, String>()
    var i = 1
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--") && i + 1 < args.size) {
            flags[a.removePrefix("--")] = args[i + 1]
            i += 2
        } else {
            i += 1
        }
    }
    return flags
}

private fun Map<String, String>.require(flag: String): String =
    this[flag] ?: error("Missing required --$flag")

internal fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
