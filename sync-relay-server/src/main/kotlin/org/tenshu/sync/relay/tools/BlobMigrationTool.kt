package org.tenshu.sync.relay.tools

import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.storage.FileBlobStore
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.S3BlobStore
import java.io.File
import kotlin.system.exitProcess

/**
 * Copies the relay's large-blob plane from the block volume into an S3-compatible bucket.
 * Runbook: `docs/how-to/migrate-relay-blobs-to-s3.md`.
 *
 * ```
 * java -cp sync-relay-server-all.jar org.tenshu.sync.relay.tools.BlobMigrationToolKt --preflight
 * java -cp sync-relay-server-all.jar org.tenshu.sync.relay.tools.BlobMigrationToolKt [--no-verify]
 * ```
 *
 * `--preflight` runs [BlobStorePreflight] against the destination and exits — no database needed,
 * nothing copied. **Run it first**, and on the real bucket: it is the only thing that establishes
 * the store is compatible at all, because no test can (see [BlobStorePreflight]).
 *
 * Reads the same environment the relay does — `DATABASE_URL`, `BLOB_DIR`, and the four `S3_*`
 * settings — so the source and destination are by construction the ones the relay will use, rather
 * than a second copy of the wiring that can drift from it.
 *
 * ⚠⚠ **Run it twice**: once against the live relay (the bulk pass — nothing reads the bucket yet,
 * so a partly-filled one is unobservable), then again with the relay **stopped** to catch what was
 * written meanwhile, before flipping the env vars. [BlobMigration] explains why that ordering is
 * not interchangeable with a background copy against an already-switched relay.
 *
 * ⚠ Exits non-zero when anything failed, so a runbook step can be `&&`-chained and a half-copied
 * plane cannot be mistaken for a finished one.
 */
fun main(args: Array<String>) {
    val verify = !args.contains("--no-verify")
    val config = RelayConfig.fromEnv()

    require(config.s3Configured) {
        "Set all of S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY — this tool needs the destination."
    }

    // `--preflight` answers "will this bucket actually serve the content plane?" and needs no
    // database — it is run before a window, often before the relay has ever pointed at the store.
    if (args.contains("--preflight")) {
        preflight(config)
        return
    }

    val databaseUrl = config.databaseUrl ?: fail(
        "DATABASE_URL is unset. There is no index to enumerate — an in-memory relay has no durable blobs to move."
    )

    // The source is the volume the relay is (still) using: BLOB_DIR, else beside the SQLite file.
    // Mirrors Application's derivation deliberately — a different guess here would copy the wrong
    // directory and report a cheerful success over an empty source.
    val blobDir = config.blobDir?.let(::File)
        ?: File(File(databaseUrl.removePrefix("jdbc:sqlite:")).absoluteFile.parentFile, "relay-blobs")
    if (!blobDir.isDirectory) fail("Source blob dir '$blobDir' is not a directory. Check BLOB_DIR / DATABASE_URL.")

    val destination = destinationFor(config)

    println("source      : $blobDir")
    println("destination : ${config.s3Bucket} at ${config.s3Endpoint}")
    println("verify      : $verify")

    RelayDatabase(databaseUrl).use { db ->
        val keys = BlobMigration.enumerateKeys(db).toList()
        println("keys        : ${keys.size}")

        val migration = BlobMigration(FileBlobStore(blobDir), destination, verify = verify)
        var lastPrinted = 0
        val report = migration.run(keys.asSequence()) { progress ->
            val done = progress.copied + progress.skipped
            if (done - lastPrinted >= PROGRESS_EVERY) {
                lastPrinted = done
                println("  ... $done/${keys.size} (copied ${progress.copied}, skipped ${progress.skipped})")
            }
        }

        println()
        println("copied            : ${report.copied}")
        println("skipped (present) : ${report.skipped}")
        println("missing at source : ${report.missingAtSource.size}")
        report.missingAtSource.take(SAMPLE).forEach { println("    $it") }
        println("failed            : ${report.failed.size}")
        report.failed.take(SAMPLE).forEach { (key, message) -> println("    $key: $message") }

        if (!report.ok) {
            println()
            println("FAILED — do not flip the relay's S3_* env vars. Re-run to retry; copying is idempotent.")
            exitProcess(1)
        }
        // ⚠ Deliberately not phrased as "safe to switch": a clean bulk pass against a LIVE relay
        // proves nothing about what has been written since. The delta pass with the relay stopped
        // is what makes the switch safe, and the runbook is what sequences it.
        println()
        println("OK — every enumerated key is present at the destination as of now.")
        if (report.missingAtSource.isNotEmpty()) {
            println("NOTE: ${report.missingAtSource.size} indexed key(s) had no bytes at the source; they are")
            println("      unreadable on the current relay too, so this did not make anything worse.")
        }
    }
}

private fun preflight(config: RelayConfig) {
    println("preflight   : ${config.s3Bucket} at ${config.s3Endpoint}")
    println("payload     : ${BlobStorePreflight.PROBE_SIZE_BYTES} bytes (the real content chunk size)")
    println()

    val report = BlobStorePreflight(destinationFor(config)).run()
    report.checks.forEach { check ->
        println("  ${if (check.passed) "PASS" else "FAIL"}  ${check.name}")
        if (!check.passed) println("        ${check.detail}")
    }

    println()
    if (report.ok) {
        // ⚠ Deliberately not "safe to migrate": this proves the STORE works, not that the copy has
        // run or that the delta pass is done. The runbook sequences the rest.
        println("OK — this store round-trips the content plane's operations. Continue with the runbook.")
    } else {
        println("FAILED — do not migrate to this store. Fix the setting above, or pick another store.")
        exitProcess(1)
    }
}

private const val PROGRESS_EVERY = 250
private const val SAMPLE = 20

/** One construction, so a preflight can never validate a different store than the copy writes to. */
private fun destinationFor(config: RelayConfig) = S3BlobStore(
    client = S3BlobStore.clientFor(
        endpoint = config.s3Endpoint!!,
        region = config.s3Region,
        accessKey = config.s3AccessKey!!,
        secretKey = config.s3SecretKey!!,
    ),
    bucket = config.s3Bucket!!,
    keyPrefix = config.s3KeyPrefix,
)

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}
