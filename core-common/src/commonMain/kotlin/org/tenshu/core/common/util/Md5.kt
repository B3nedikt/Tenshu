package org.tenshu.core.common.util

@Suppress("LocalVariableName")
fun md5Hash(input: ByteArray): ByteArray {
    val s = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
    )

    val K = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
        0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
        0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
        0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt()
    )

    var a0 = 0x67452301
    var b0 = 0xefcdab89.toInt()
    var c0 = 0x98badcfe.toInt()
    var d0 = 0x10325476

    val padded = pad(input)

    for (i in 0 until padded.size step 64) {
        val chunk = padded.sliceArray(i until i + 64)
        val M = IntArray(16) { j ->
            (chunk[j * 4].toInt() and 0xFF) or
                    ((chunk[j * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((chunk[j * 4 + 2].toInt() and 0xFF) shl 16) or
                    ((chunk[j * 4 + 3].toInt() and 0xFF) shl 24)
        }

        var A = a0
        var B = b0
        var C = c0
        var D = d0

        for (j in 0 until 64) {
            val F: Int
            val g: Int
            when (j) {
                in 0..15 -> {
                    F = (B and C) or (B.inv() and D)
                    g = j
                }

                in 16..31 -> {
                    F = (D and B) or (D.inv() and C)
                    g = (5 * j + 1) % 16
                }

                in 32..47 -> {
                    F = B xor C xor D
                    g = (3 * j + 5) % 16
                }

                else -> {
                    F = C xor (B or D.inv())
                    g = (7 * j) % 16
                }
            }

            val temp = D
            D = C
            C = B
            B += (A + F + K[j] + M[g]).rotateLeft(s[j])
            A = temp
        }

        a0 += A
        b0 += B
        c0 += C
        d0 += D
    }

    return toLE(a0) + toLE(b0) + toLE(c0) + toLE(d0)
}

private fun pad(input: ByteArray): ByteArray {
    val bitLen = input.size * 8L
    val padding = mutableListOf<Byte>()

    // Append 0x80 (10000000 in binary)
    padding.add(0x80.toByte())

    // Calculate padding length to reach 56 mod 64
    val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
    repeat(padLen) { padding.add(0x00) }

    // Append original length in bits as 64-bit little-endian
    for (i in 0..7) {
        padding.add(((bitLen shr (8 * i)) and 0xFF).toByte())
    }

    return input + padding.toByteArray()
}

private fun toLE(value: Int): ByteArray {
    return byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )
}









