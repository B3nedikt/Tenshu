package org.tenshu.inference.proxy.catalog

/** One entry of a [testTar] archive. [type] is the ustar type flag: `0` a file, `5` a directory. */
internal class TarTestEntry(
    val name: String,
    val content: ByteArray = ByteArray(0),
    val type: Char = '0',
    val prefix: String = "",
)

/** A ustar archive of [entries], closed by the two zero blocks a real one ends with. */
internal fun testTar(vararg entries: TarTestEntry): ByteArray {
    var out = ByteArray(0)
    entries.forEach { entry ->
        val header = ByteArray(TarReader.BLOCK)
        put(header, 0, entry.name)
        put(header, 100, "0000644")
        put(header, 108, "0000000")
        put(header, 116, "0000000")
        put(header, 124, entry.content.size.toString(8).padStart(11, '0'))
        put(header, 136, "00000000000")
        header[156] = entry.type.code.toByte()
        put(header, 257, "ustar")
        put(header, 263, "00")
        put(header, 345, entry.prefix)
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        val sum: Int = header.sumOf { it.toInt() and 0xFF }
        put(header, 148, sum.toString(8).padStart(6, '0'))
        header[154] = 0
        val padded: Int = (entry.content.size + TarReader.BLOCK - 1) / TarReader.BLOCK * TarReader.BLOCK
        out = out + header + entry.content.copyOf(padded)
    }
    return out + ByteArray(TarReader.BLOCK * 2)
}

private fun put(header: ByteArray, offset: Int, text: String) {
    text.encodeToByteArray().copyInto(header, offset)
}
