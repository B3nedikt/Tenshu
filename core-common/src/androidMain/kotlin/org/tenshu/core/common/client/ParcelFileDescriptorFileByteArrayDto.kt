package org.tenshu.core.common.client

import android.os.ParcelFileDescriptor
import org.tenshu.core.Data
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.util.toBinaryCacheKey
import org.tenshu.files.util.ParcelFileDescriptorCache

class ParcelFileDescriptorFileByteArrayDto(
    var rawValue: Any,
    private val cache: ParcelFileDescriptorCache
) : FileByteArray {

    // Keyed via `toBinaryCacheKey()` — the single source of truth shared with
    // the put side (`BinderTaskTransport.receiveFile`). `String(...)` here
    // would not round-trip the put-side key and silently miss; see
    // `BinaryCacheKey`. A genuine miss is surfaced as `IllegalStateException`
    // and tolerated by the preview/data consumer (`FileMapper.map`).
    private val fileDescriptor by lazy {
        val cachedFd = cache.get(fileId.toBinaryCacheKey())
            ?: error(
                "ParcelFileDescriptor with id ${fileId.toBinaryCacheKey()} not found in cache"
            )

        cachedFd.dup()
    }

    private val bytes by lazy {
        ParcelFileDescriptor.AutoCloseInputStream(fileDescriptor).use { it.readBytes() }
    }

    override val fileId: ByteArray
        get() = rawValue as ByteArray

    override val size: Long
        get() = bytes.size.toLong()

    override fun get(): ByteArray {
        return bytes
    }

    /**
     * Short-circuits the cache-miss path: rather than the default's
     * `runCatching { get() }.getOrNull()` round-trip (which would let the
     * `fileDescriptor` lazy's `error("…")` propagate then catch
     * `IllegalStateException`), check the cache directly and return null
     * on miss. Cheaper, same observable null-on-miss contract.
     *
     * Note: this **does not** re-read the lazy `bytes` if `fileDescriptor`
     * has already initialised — once the bytes are materialised, the
     * underlying handle is no longer consulted. So a hit here means
     * either "bytes already in memory from a prior `get()`" or "handle
     * still in cache, read fresh".
     */
    override fun getOrNull(): ByteArray? {
        return if (cache.get(fileId.toBinaryCacheKey()) != null) {
            bytes
        } else {
            null
        }
    }

    override val value: Data
        get() = TByteArrayDto(get())

    override fun equals(other: Any?): Boolean {
        if (other !is FileByteArray) return false

        return other.fileId.contentEquals(fileId)
    }

    override fun hashCode(): Int {
        return fileId.hashCode()
    }
}
