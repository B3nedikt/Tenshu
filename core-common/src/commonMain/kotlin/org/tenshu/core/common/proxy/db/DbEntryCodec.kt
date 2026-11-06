package org.tenshu.core.common.proxy.db

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue

// Deterministic binary codec for the `data` BLOB column of SqliteObjectStore.
//
// There is no project serializer for DbEntry: `BinarySerializer` operates over
// the `Data`/schema type system, not the `DbValue` hierarchy. The JS store's
// `encodeEntry`/`decodeEntry` is a JS-object map (structured-clone), not bytes.
// So this is the JVM-side equivalent: a tiny length-prefixed format. Schema
// evolution of DbEntry/DbValue is the binary protocol's job upstream — this
// blob is opaque persistence, and the SQLite table is forever (hash,type,data).
//
// Format (all integers big-endian, kotlinx.io default):
//   entry := str(type) i64(dataIdHash)
//            i32(fieldCount)  value*fieldCount
//            i32(propCount)  (str(key) value)*propCount
//   str    := i32(byteLen) utf8Bytes
//   value  := i8(tag) payload
//     tag 1 String  -> str
//     tag 2 Long    -> i64
//     tag 3 Bytes   -> i32(len) bytes
//     tag 4 Boolean -> i8 (0/1)
//     tag 5 Null    -> (no payload)
//     tag 6 Generic -> str(typeId) value(payload)   // self-describing slot
internal object DbEntryCodec {

    private const val TAG_STRING: Byte = 1
    private const val TAG_LONG: Byte = 2
    private const val TAG_BYTES: Byte = 3
    private const val TAG_BOOL: Byte = 4
    private const val TAG_NULL: Byte = 5
    private const val TAG_GENERIC: Byte = 6

    fun encode(entry: DbEntry): ByteArray {
        val buffer = Buffer()
        buffer.writeStr(entry.type)
        buffer.writeLong(entry.dataIdHash)
        buffer.writeInt(entry.fields.size)
        entry.fields.forEach { buffer.writeValue(it) }
        buffer.writeInt(entry.properties.size)
        entry.properties.forEach { (key, value) ->
            buffer.writeStr(key)
            buffer.writeValue(value)
        }
        return buffer.readByteArray()
    }

    fun decode(bytes: ByteArray): DbEntry {
        val buffer = Buffer().also { it.write(bytes) }
        val type = buffer.readStr()
        val dataIdHash = buffer.readLong()
        val fieldCount = buffer.readInt()
        val fields = ArrayList<DbValue>(fieldCount)
        repeat(fieldCount) { fields.add(buffer.readValue()) }
        val propCount = buffer.readInt()
        // LinkedHashMap: preserve write order so the round-trip is byte-stable.
        val properties = LinkedHashMap<String, DbValue>(propCount)
        repeat(propCount) {
            val key = buffer.readStr()
            properties[key] = buffer.readValue()
        }
        return DbEntry(type, dataIdHash, fields, properties)
    }

    private fun Buffer.writeStr(value: String) {
        val encoded = value.encodeToByteArray()
        writeInt(encoded.size)
        write(encoded)
    }

    private fun Buffer.readStr(): String {
        val length = readInt()
        return readByteArray(length).decodeToString()
    }

    private fun Buffer.writeValue(value: DbValue) {
        when (value) {
            is DbValue.StringValue -> {
                writeByte(TAG_STRING)
                writeStr(value.value)
            }

            is DbValue.LongValue -> {
                writeByte(TAG_LONG)
                writeLong(value.value)
            }

            is DbValue.ByteArrayValue -> {
                writeByte(TAG_BYTES)
                writeInt(value.value.size)
                write(value.value)
            }

            is DbValue.BooleanValue -> {
                writeByte(TAG_BOOL)
                writeByte(if (value.value) 1 else 0)
            }

            is DbValue.GenericValue -> {
                writeByte(TAG_GENERIC)
                writeStr(value.typeId)
                writeValue(value.payload)
            }

            DbValue.NullValue -> writeByte(TAG_NULL)
        }
    }

    private fun Buffer.readValue(): DbValue =
        when (val tag = readByte()) {
            TAG_STRING -> DbValue.StringValue(readStr())
            TAG_LONG -> DbValue.LongValue(readLong())
            TAG_BYTES -> DbValue.ByteArrayValue(readByteArray(readInt()))
            TAG_BOOL -> DbValue.BooleanValue(readByte().toInt() != 0)
            TAG_NULL -> DbValue.NullValue
            TAG_GENERIC -> DbValue.GenericValue(readStr(), readValue())
            else -> error("DbEntryCodec: unknown DbValue tag $tag")
        }
}
