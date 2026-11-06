package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.value

interface TByteArray : TValue {

    override val schema: ValueSchema
        get() = Schema

    val size: Long

    /**
     * Read the byte payload from this value's backing store. Producer-side
     * `TByteArrayDto` impls with inline bytes return them directly; the
     * cross-process consumer-side impl
     * (`ParcelFileDescriptorFileByteArrayDto` on Android) reads through a
     * cached `ParcelFileDescriptor` handle and **throws
     * `IllegalStateException` if the handle was evicted from the cache**
     * since the value crossed IPC (see that impl's KDoc).
     *
     * Use [get] on the producer side where the value was constructed
     * locally with inline bytes — fail-fast is the right contract.
     * Consumer sites reading bytes that crossed IPC should prefer
     * [getOrNull] instead, which returns `null` on cache miss without the
     * throw-then-catch round-trip.
     */
    fun get(): ByteArray

    /**
     * Null-on-miss companion to [get]: returns the byte payload if it can
     * be produced, `null` if the backing store can't satisfy the read
     * (cross-process SharedMemory / `ParcelFileDescriptor` handle evicted,
     * etc.). Genuine errors at other layers (IO, corruption) still
     * propagate as before — only the documented cache-miss
     * `IllegalStateException` collapses to `null`.
     *
     * Default implementation routes through [get] with a scoped catch on
     * `IllegalStateException`, matching the precedent in
     * `FileMapper.readOrNullOnCacheMiss` (which becomes a one-line
     * `.getOrNull()` after this lands). Backing-store impls that can
     * detect the miss without throwing (e.g.
     * `ParcelFileDescriptorFileByteArrayDto`) should override to
     * short-circuit the throw-then-catch round-trip.
     */
    fun getOrNull(): ByteArray? = try {
        get()
    } catch (_: IllegalStateException) {
        null
    }

    companion object {
        const val ID = "tenshu/core/byteArray"

        val Schema: ValueSchema = value(
            id = ID,
            parent = TValue.Schema,
        )
    }
}

fun ByteArray.toTByteArray(context: TContext? = null): TByteArray {
    return if (context != null) {
        context.dataFactory()
            .createValue(TByteArray.ID, this) as TByteArray
    } else {
        SimpleTByteArray(this)
    }
}

internal class SimpleTByteArray(private val bytes: ByteArray) : TByteArray {
    override val size: Long get() = bytes.size.toLong()
    override fun get(): ByteArray = bytes
    override val value: Data get() = this
    override val schema: ValueSchema get() = TByteArray.Schema

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TByteArray) return false
        return bytes.contentEquals(other.get())
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}
