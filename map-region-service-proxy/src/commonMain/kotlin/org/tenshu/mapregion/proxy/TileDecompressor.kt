package org.tenshu.mapregion.proxy

/**
 * Platform-supplied decompression for PMTiles payloads (M9-4,
 * `docs/proposals/timeline-movement-capture.md` §13.3).
 *
 * ⚠ **Why this is a seam and not a function.** PMTiles gzips both its directories and its tile
 * payloads (two separate header fields), and `commonMain` has no gzip: `java.util.zip` is reachable
 * only from JVM/Android source sets, and `:map-region-service-proxy` is commonMain-only with real
 * JS and iOS targets. An `expect/actual` was rejected — JS's `DecompressionStream` is *asynchronous*
 * and iOS would need a zlib cinterop, so three of four actuals would be awkward or absent for a
 * feature only two platforms use today.
 *
 * Instead the platform hands in the one function it needs, exactly as [ProxyReverseGeocoderEgress]
 * hands in network egress. A `null` binding disables the rail op on that platform rather than
 * failing it: `railLinesIn` returns empty, which the app's `RailRefinement` must treat as
 * *unavailable* rather than as "no track here".
 */
fun interface TileDecompressor {

    /**
     * @param compression the PMTiles compression code — 1 = none, 2 = gzip.
     * @return the decompressed bytes, or the input unchanged when [compression] is 1.
     */
    fun decompress(bytes: ByteArray, compression: Int): ByteArray
}
