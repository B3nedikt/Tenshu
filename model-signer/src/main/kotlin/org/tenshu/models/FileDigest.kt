package org.tenshu.models

import java.io.File
import java.security.MessageDigest

/**
 * Streaming SHA-256 over one model artifact.
 *
 * ⚠ `core-common`'s `sha256(ByteArray)` — the primitive the **client** runs over a downloaded
 * artifact — cannot be pointed at these from the build side: it needs the whole file in one array,
 * and a checkpoint tower is ~380 MB. Reading in fixed chunks is the shape that works.
 *
 * It is the *same digest*: SHA-256 is defined over a stream of 64-byte blocks, so a chunked feed
 * and a one-shot buffer produce identical output — [FileDigestTest] pins that against the
 * `core-common` primitive rather than asserting it here. So this does **not** fork the hash
 * contract; the client still verifies with `core-common` and its answer still matches the catalog.
 *
 * ⚠ Local to this module rather than shared with `:tiles-signer`'s identical helper, which is
 * `internal` to it. Whole-file hashing is a build-tool concern and `core-common` gains no API — the
 * thing that must not fork is the **signature** stack, and that is shared (see the build file).
 */
internal fun sha256File(file: File, bufferSize: Int = DEFAULT_DIGEST_BUFFER): ByteArray {
    require(bufferSize > 0) { "bufferSize must be positive, was $bufferSize" }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(bufferSize)
    file.inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** 1 MiB — a 380 MB tower is ~380 reads, and it stays off the large-object heap. */
private const val DEFAULT_DIGEST_BUFFER = 1 shl 20
