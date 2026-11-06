package app.tenshu.vectortile

/**
 * A tiny MVT **encoder**, test-only — the counterpart the decoder is checked against.
 *
 * Written rather than committing a binary fixture so each case states its intent in code: a test
 * that says `moveTo(10,10); lineTo(5,5)` is readable, while a hex blob is not. Real-archive
 * verification is `:ui-map`'s `MvtRealArchiveTest`; this covers the format's corners.
 */
internal object MvtWriter {

    sealed interface V {
        data class Str(val v: String) : V
        data class Int(val v: Long) : V
        data class SInt(val v: Long) : V
        data class Bool(val v: Boolean) : V
        data class Dbl(val v: Double) : V
    }

    fun tile(build: TileBuilder.() -> Unit): ByteArray =
        TileBuilder().apply(build).bytes()

    class TileBuilder {
        private val out = ArrayList<Byte>()

        fun layer(
            name: String,
            extent: Int = 4096,
            version: Int = 2,
            extraUnknownField: Boolean = false,
            build: LayerBuilder.() -> Unit = {},
        ) {
            val layer = LayerBuilder(name, extent, version, extraUnknownField).apply(build).bytes()
            writeTag(out, 3, MvtDecoder.WIRE_LEN)
            writeVarint(out, layer.size.toLong())
            out.addAll(layer.toList())
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    class LayerBuilder(
        private val name: String,
        private val extent: Int,
        private val version: Int,
        private val extraUnknownField: Boolean,
    ) {
        private val keys = ArrayList<String>()
        private val values = ArrayList<V>()
        private val features = ArrayList<ByteArray>()

        fun feature(type: Int, tags: Map<String, String> = emptyMap(), geom: GeomBuilder.() -> Unit) =
            featureTyped(type, tags.map { it.key to V.Str(it.value) }, geom)

        fun featureTyped(type: Int, typedTags: List<Pair<String, V>>, geom: GeomBuilder.() -> Unit) {
            val f = ArrayList<Byte>()
            writeTag(f, 3, MvtDecoder.WIRE_VARINT); writeVarint(f, type.toLong())

            if (typedTags.isNotEmpty()) {
                val indices = ArrayList<Byte>()
                for ((k, v) in typedTags) {
                    var ki = keys.indexOf(k); if (ki < 0) { keys += k; ki = keys.size - 1 }
                    var vi = values.indexOf(v); if (vi < 0) { values += v; vi = values.size - 1 }
                    writeVarint(indices, ki.toLong())
                    writeVarint(indices, vi.toLong())
                }
                writeTag(f, 2, MvtDecoder.WIRE_LEN)
                writeVarint(f, indices.size.toLong())
                f.addAll(indices)
            }

            val g = GeomBuilder().apply(geom).bytes()
            writeTag(f, 4, MvtDecoder.WIRE_LEN); writeVarint(f, g.size.toLong()); f.addAll(g.toList())
            features += f.toByteArray()
        }

        fun bytes(): ByteArray {
            val out = ArrayList<Byte>()
            writeTag(out, 1, MvtDecoder.WIRE_LEN)
            val n = name.encodeToByteArray()
            writeVarint(out, n.size.toLong()); out.addAll(n.toList())

            if (extraUnknownField) {
                // Field 99, length-delimited — nothing in the spec; the decoder must skip it.
                writeTag(out, 99, MvtDecoder.WIRE_LEN)
                writeVarint(out, 3); out.addAll(listOf<Byte>(1, 2, 3))
            }

            for (k in keys) {
                writeTag(out, 3, MvtDecoder.WIRE_LEN)
                val b = k.encodeToByteArray()
                writeVarint(out, b.size.toLong()); out.addAll(b.toList())
            }
            for (v in values) {
                val vb = encodeValue(v)
                writeTag(out, 4, MvtDecoder.WIRE_LEN)
                writeVarint(out, vb.size.toLong()); out.addAll(vb.toList())
            }
            for (f in features) {
                writeTag(out, 2, MvtDecoder.WIRE_LEN)
                writeVarint(out, f.size.toLong()); out.addAll(f.toList())
            }
            writeTag(out, 5, MvtDecoder.WIRE_VARINT); writeVarint(out, extent.toLong())
            writeTag(out, 15, MvtDecoder.WIRE_VARINT); writeVarint(out, version.toLong())
            return out.toByteArray()
        }

        private fun encodeValue(v: V): ByteArray {
            val o = ArrayList<Byte>()
            when (v) {
                is V.Str -> {
                    writeTag(o, 1, MvtDecoder.WIRE_LEN)
                    val b = v.v.encodeToByteArray(); writeVarint(o, b.size.toLong()); o.addAll(b.toList())
                }
                is V.Int -> { writeTag(o, 4, MvtDecoder.WIRE_VARINT); writeVarint(o, v.v) }
                is V.SInt -> { writeTag(o, 6, MvtDecoder.WIRE_VARINT); writeVarint(o, (v.v shl 1) xor (v.v shr 63)) }
                is V.Bool -> { writeTag(o, 7, MvtDecoder.WIRE_VARINT); writeVarint(o, if (v.v) 1 else 0) }
                is V.Dbl -> {
                    writeTag(o, 3, MvtDecoder.WIRE_I64)
                    val bits = v.v.toRawBits()
                    for (i in 0 until 8) o.add(((bits ushr (8 * i)) and 0xFF).toByte())
                }
            }
            return o.toByteArray()
        }
    }

    class GeomBuilder {
        private val out = ArrayList<Byte>()

        fun moveTo(dx: Int, dy: Int) { command(1, 1); zig(dx); zig(dy) }
        fun lineTo(dx: Int, dy: Int) { command(2, 1); zig(dx); zig(dy) }
        fun closePath() { command(7, 1) }

        private fun command(id: Int, count: Int) = writeVarint(out, ((count shl 3) or id).toLong())
        private fun zig(v: Int) = writeVarint(out, ((v shl 1) xor (v shr 31)).toLong())

        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun writeTag(out: MutableList<Byte>, field: Int, wire: Int) =
        writeVarint(out, ((field shl 3) or wire).toLong())

    private fun writeVarint(out: MutableList<Byte>, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) out += (b or 0x80).toByte() else { out += b.toByte(); break }
        }
    }
}
