package org.tenshu.tiles

import java.io.File
import java.security.MessageDigest

/**
 * Streaming SHA-256 over a bundle file.
 *
 * The catalog hashes every published bundle, and a bundle is not a small thing: D2 sizes a large
 * country at "a few GB", and the `global` region *is* the planet (~15–25 GB zoom-capped, ~100 GB at
 * full zoom). `core-common`'s `sha256(ByteArray)` — the primitive the **client** runs over a
 * downloaded bundle — cannot be pointed at those from the build side: it needs the whole file in one
 * array, and a JVM array is `Int`-indexed, so a bundle past 2 GiB fails outright before memory
 * pressure even enters into it. Reading in fixed chunks is the only shape that works at planet scale.
 *
 * It is the *same digest*: SHA-256 is defined over a stream of 64-byte blocks, so a chunked feed and a
 * one-shot buffer produce identical output — [FileDigestTest] pins that against the `core-common`
 * primitive rather than asserting it here. So this does **not** fork the hash contract: the client
 * still verifies with `core-common`, and its answer still matches the catalog.
 *
 * Deliberately local to `:tiles-signer`. Whole-file hashing is a build-tool concern; `core-common`
 * gets no new API and the client's verification stack is untouched (D3 — one signature stack).
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

/** 1 MiB — large enough that a 100 GB planet is ~100k reads, small enough to stay off the large-object heap. */
private const val DEFAULT_DIGEST_BUFFER = 1 shl 20
