package app.tenshu.control.di

import java.util.zip.GZIPInputStream
import org.tenshu.mapregion.proxy.TileDecompressor

/**
 * JVM/Android gzip for PMTiles payloads (M9-4). The whole reason `TileDecompressor` is a seam:
 * `java.util.zip` cannot be reached from the proxy's commonMain, which also targets JS and iOS.
 *
 * Compression code 2 is gzip; 1 (or anything else) is stored uncompressed and passes through.
 */
internal object JvmTileDecompressor : TileDecompressor {
    override fun decompress(bytes: ByteArray, compression: Int): ByteArray =
        if (compression == 2) GZIPInputStream(bytes.inputStream()).use { it.readBytes() } else bytes
}
