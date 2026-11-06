package org.tenshu.core.common.util

/**
 * Simplified Bech32 implementation for pairing codes.
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun encode(hrp: String, data: ByteArray): String {
        val converted = convertBits(data, 8, 5, true)
        val combined = converted + checksum(hrp, converted)
        val builder = StringBuilder(hrp.lowercase() + "1")
        for (b in combined) {
            builder.append(CHARSET[b.toInt()])
        }
        return builder.toString()
    }

    fun decode(bech: String): Pair<String, ByteArray> {
        val pos = bech.lastIndexOf('1')
        // Increase length limit from 90 to 500 to support longer pairing data (keys + UUIDs)
        if (pos < 1 || pos + 7 > bech.length || bech.length > 500) error("Invalid bech32")
        val hrp = bech.substring(0, pos).lowercase()
        val data = ByteArray(bech.length - pos - 1)
        for (i in 0 until data.size) {
            val d = CHARSET.indexOf(bech[pos + 1 + i].lowercaseChar())
            if (d == -1) error("Invalid character")
            data[i] = d.toByte()
        }
        if (!verifyChecksum(hrp, data)) error("Invalid checksum")
        val converted = convertBits(data.copyOfRange(0, data.size - 6), 5, 8, false)
        return hrp to converted
    }

    private fun verifyChecksum(hrp: String, data: ByteArray): Boolean {
        return polymod(expandHrp(hrp) + data) == 1
    }

    private fun checksum(hrp: String, data: ByteArray): ByteArray {
        val values = expandHrp(hrp) + data
        val poly = polymod(values + byteArrayOf(0, 0, 0, 0, 0, 0)) xor 1
        val result = ByteArray(6)
        for (i in 0 until 6) {
            result[i] = ((poly shr (5 * (5 - i))) and 31).toByte()
        }
        return result
    }

    private fun expandHrp(hrp: String): ByteArray {
        val result = ByteArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            result[i] = (hrp[i].code shr 5).toByte()
            result[i + hrp.length + 1] = (hrp[i].code and 31).toByte()
        }
        result[hrp.length] = 0
        return result
    }

    private fun polymod(values: ByteArray): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk shr 25
            chk = (chk and 0x1ffffff shl 5) xor v.toInt()
            for (i in 0 until 5) {
                if ((top shr i) and 1 != 0) {
                    chk = chk xor generators[i]
                }
            }
        }
        return chk
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Byte>()
        val maxV = (1 shl to) - 1
        for (v in data) {
            acc = (acc shl from) or (v.toInt() and 0xff)
            bits += from
            while (bits >= to) {
                bits -= to
                result.add(((acc shr bits) and maxV).toByte())
            }
        }
        if (pad) {
            if (bits > 0) {
                result.add(((acc shl (to - bits)) and maxV).toByte())
            }
        } else if (bits >= from || ((acc shl (to - bits)) and maxV) != 0) {
            error("Invalid padding")
        }
        return result.toByteArray()
    }
}
