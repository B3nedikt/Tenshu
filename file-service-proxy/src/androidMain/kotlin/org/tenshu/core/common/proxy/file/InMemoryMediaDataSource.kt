package org.tenshu.core.common.proxy.file

import android.media.MediaDataSource

/**
 * A read-only [MediaDataSource] over an in-memory byte array, so a just-uploaded video can be
 * poster-framed (`AndroidGetPreviewImplementation`) or clipped into its `MOTION` class
 * (`AndroidMotionThumbnailRenderer`) without first spilling it to a temp file. `MediaDataSource`
 * is API 23+; minSdk is 24.
 */
internal class InMemoryMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun getSize(): Long = data.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val available = (data.size - position).toInt()
        val count = minOf(size, available)
        if (count <= 0) return -1
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun close() {}
}
