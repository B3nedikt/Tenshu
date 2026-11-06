package org.tenshu.core.common.client

import android.annotation.SuppressLint
import android.os.SharedMemory
import org.tenshu.core.Data
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.util.toBinaryCacheKey
import org.tenshu.files.util.SharedMemoryCache

@SuppressLint("NewApi")
class SharedMemoryByteArrayDto(
    var rawValue: Any,
    private val cache: SharedMemoryCache
) : InMemoryByteArray {

    override val uniqueId: ByteArray
        get() = rawValue as ByteArray

    // Keyed via `toBinaryCacheKey()` — the single source of truth shared with
    // the put side (`BinderTaskTransport.receiveSharedMemory`). `String(...)`
    // here would not round-trip the put-side key and silently miss; see
    // `BinaryCacheKey`. A genuine miss (handle evicted between emissions) is
    // surfaced as `IllegalStateException` and tolerated by the preview/data
    // consumer (`FileMapper.map`) so it degrades to "no preview" rather than
    // crashing the `getFiles` Stream.
    val sharedMemory by lazy {
        cache.get(uniqueId.toBinaryCacheKey())
            ?: error(
                "SharedMemory with id ${uniqueId.toBinaryCacheKey()} not found in cache"
            )
    }

    private val bytes by lazy {
        val buffer = sharedMemory.mapReadOnly()
        val bytes = ByteArray(sharedMemory.size)
        buffer.get(bytes)
        SharedMemory.unmap(buffer)
        bytes
    }

    override val size: Long
        get() = sharedMemory.size.toLong()

    override fun get(): ByteArray = bytes

    override val value: Data
        get() = TByteArrayDto(get())

    override fun equals(other: Any?): Boolean {
        if (other !is InMemoryByteArray) return false

        return other.uniqueId.contentEquals(uniqueId)
    }

    override fun hashCode(): Int {
        return uniqueId.hashCode()
    }
}
