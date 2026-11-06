package org.tenshu.preference

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString

/**
 * `T <-> Data` round-trip for one preference value type. Implementations map the
 * consumer-facing type onto a concrete Tenshu [Data] (`TBoolean`, `TInteger`,
 * `TString`, `TList<TString>`, a composite, ...) on write and read it back on
 * read.
 *
 * The stored `Setting.value` is a **polymorphic `Data` field** (not opaque
 * bytes), so the value is self-describing on the wire — any consumer (including
 * another app, e.g. Control) can read it through the schema without the owning
 * app's adapter. (Earlier versions stored a `TByteArray` + byte codec to sidestep
 * the DB dbEntryMapper not resolving `typeVar`-typed value fields; that
 * limitation only affected type *variables* — a concrete `Data.Schema` field
 * round-trips fine, as the sync engine's own `DataBlockPayload.value` proves. See
 * `core-preference-service.md`.)
 *
 * [fromData] returns `null` on a type mismatch / malformed value; the surrounding
 * `PreferenceService.read(pref)` then falls back to [Preference.default] — see
 * `core-preference-service.md` §6.6.
 */
interface PreferenceAdapter<T> {
    fun toData(value: T, context: TContext): Data
    fun fromData(data: Data?): T?
}

object BooleanAdapter : PreferenceAdapter<Boolean> {
    override fun toData(value: Boolean, context: TContext): Data = value.toTBoolean(context)
    override fun fromData(data: Data?): Boolean? = (data as? TBoolean)?.get()
}

object LongAdapter : PreferenceAdapter<Long> {
    override fun toData(value: Long, context: TContext): Data = value.toTInteger(context)
    override fun fromData(data: Data?): Long? = (data as? TInteger)?.get()
}

object IntAdapter : PreferenceAdapter<Int> {
    override fun toData(value: Int, context: TContext): Data = value.toTInteger(context)
    override fun fromData(data: Data?): Int? = (data as? TInteger)?.get()?.toInt()
}

object StringAdapter : PreferenceAdapter<String> {
    override fun toData(value: String, context: TContext): Data = value.toTString(context)
    override fun fromData(data: Data?): String? = (data as? TString)?.get()
}

object ByteArrayAdapter : PreferenceAdapter<ByteArray> {
    override fun toData(value: ByteArray, context: TContext): Data = value.toTByteArray(context)
    override fun fromData(data: Data?): ByteArray? = (data as? TByteArray)?.get()?.copyOf()
}

/**
 * List-valued preference, byte-encoded into a `TByteArray` (an inline value),
 * NOT a `TList`. A `genericData` field stores an inline primitive or a composite
 * reference, but not an array — so list values ride as opaque bytes (like
 * [ByteArrayAdapter]). The trade-off: a list value is not self-describing (a
 * cross-app reader needs this adapter), unlike the scalar adapters. Acceptable
 * while no consumer reads a list-valued preference generically.
 *
 * Encoding (length-prefixed UTF-8, big-endian): `[count:int32][len:int32][utf8]...`.
 */
object StringListAdapter : PreferenceAdapter<List<String>> {
    override fun toData(value: List<String>, context: TContext): Data =
        encode(value).toTByteArray(context)

    override fun fromData(data: Data?): List<String>? =
        (data as? TByteArray)?.get()?.let(::decode)

    private fun encode(value: List<String>): ByteArray {
        val encoded = value.map { it.encodeToByteArray() }
        val size = 4 + encoded.sumOf { 4 + it.size }
        val out = ByteArray(size)
        var pos = 0
        writeInt(out, pos, value.size); pos += 4
        for (bytes in encoded) {
            writeInt(out, pos, bytes.size); pos += 4
            bytes.copyInto(out, pos); pos += bytes.size
        }
        return out
    }

    private fun decode(bytes: ByteArray): List<String>? {
        if (bytes.size < 4) return null
        return runCatching {
            var pos = 0
            val count = readInt(bytes, pos); pos += 4
            require(count >= 0) { "negative count" }
            buildList(count) {
                repeat(count) {
                    val len = readInt(bytes, pos); pos += 4
                    require(len >= 0 && pos + len <= bytes.size) { "bad length" }
                    add(bytes.decodeToString(pos, pos + len))
                    pos += len
                }
            }
        }.getOrNull()
    }

    private fun writeInt(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v ushr 24).toByte()
        buf[offset + 1] = (v ushr 16).toByte()
        buf[offset + 2] = (v ushr 8).toByte()
        buf[offset + 3] = v.toByte()
    }

    private fun readInt(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF shl 24) or
            (buf[offset + 1].toInt() and 0xFF shl 16) or
            (buf[offset + 2].toInt() and 0xFF shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
}
