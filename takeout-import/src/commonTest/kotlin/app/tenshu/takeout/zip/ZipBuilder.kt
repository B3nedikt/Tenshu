package app.tenshu.takeout.zip

/**
 * A minimal ZIP writer for the tests — **stored entries only**, so `commonTest` needs no deflate
 * (the real thing is proven against `java.util.zip`'s own output in `jvmTest`).
 *
 * Built by hand rather than from a fixture file so every byte the reader walks is visible here:
 * the sentinels, the zeroed local sizes and the zip64 records are each a deliberate line below,
 * and each is a case the reader has to survive.
 */
class ZipBuilder {

    private data class Item(val name: String, val bytes: ByteArray, val zeroLocalSizes: Boolean)

    private val items = mutableListOf<Item>()

    fun add(name: String, content: String, zeroLocalSizes: Boolean = false) = apply {
        items += Item(name, content.encodeToByteArray(), zeroLocalSizes)
    }

    fun addDirectory(name: String) = apply { items += Item(name, ByteArray(0), false) }

    /**
     * @param zip64 write the zip64 records and leave `0xFFFF…` sentinels in the classic ones — what
     *   a real writer does past 4 GB or 65 535 entries.
     */
    fun build(zip64: Boolean = false): ByteArray {
        val out = Buffer()
        val offsets = mutableListOf<Int>()

        items.forEach { item ->
            offsets += out.size
            out.u32(0x04034b50)
            out.u16(20); out.u16(0); out.u16(0)     // version, flags, method = stored
            out.u16(0); out.u16(0); out.u32(0)      // time, date, crc
            if (item.zeroLocalSizes) {
                // ⚠⚠ A streaming writer defers the sizes to a data descriptor and writes zeros
                // here. A reader that trusts local headers reads nothing from such an archive.
                out.u32(0); out.u32(0)
            } else {
                out.u32(item.bytes.size); out.u32(item.bytes.size)
            }
            out.u16(item.name.length); out.u16(0)
            out.bytes(item.name.encodeToByteArray())
            out.bytes(item.bytes)
        }

        val directoryAt = out.size
        items.forEachIndexed { index, item ->
            out.u32(0x02014b50)
            out.u16(20); out.u16(20); out.u16(0); out.u16(0)
            out.u16(0); out.u16(0); out.u32(0)
            out.u32(item.bytes.size); out.u32(item.bytes.size)
            out.u16(item.name.length); out.u16(0); out.u16(0)
            out.u16(0); out.u16(0); out.u32(0)
            out.u32(offsets[index])
            out.bytes(item.name.encodeToByteArray())
        }
        val directorySize = out.size - directoryAt

        if (zip64) {
            val zip64At = out.size
            out.u32(0x06064b50)
            out.u64(44)                              // size of this record, minus 12
            out.u16(45); out.u16(45); out.u32(0); out.u32(0)
            out.u64(items.size.toLong()); out.u64(items.size.toLong())
            out.u64(directorySize.toLong()); out.u64(directoryAt.toLong())
            out.u32(0x07064b50)                      // the locator
            out.u32(0); out.u64(zip64At.toLong()); out.u32(1)
        }

        out.u32(0x06054b50)
        out.u16(0); out.u16(0)
        if (zip64) {
            out.u16(0xFFFF); out.u16(0xFFFF)
            out.u32(0xFFFFFFFF); out.u32(0xFFFFFFFF)
        } else {
            out.u16(items.size); out.u16(items.size)
            out.u32(directorySize); out.u32(directoryAt)
        }
        out.u16(0)
        return out.toByteArray()
    }

    private class Buffer {
        private val bytes = mutableListOf<Byte>()
        val size: Int get() = bytes.size
        fun bytes(value: ByteArray) { value.forEach { bytes += it } }
        fun u16(value: Int) { repeat(2) { bytes += ((value shr (8 * it)) and 0xFF).toByte() } }
        fun u32(value: Int) = u32(value.toLong())
        fun u32(value: Long) { repeat(4) { bytes += ((value shr (8 * it)) and 0xFF).toByte() } }
        fun u64(value: Long) { repeat(8) { bytes += ((value shr (8 * it)) and 0xFF).toByte() } }
        fun toByteArray() = bytes.toByteArray()
    }
}

/** Stored entries need no inflate; a test that reaches this has built the wrong fixture. */
val neverInflate: Inflate = { _, _ -> error("a stored entry must never be inflated") }
