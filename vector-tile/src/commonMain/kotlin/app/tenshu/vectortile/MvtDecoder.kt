package app.tenshu.vectortile

/**
 * A **Mapbox Vector Tile decoder** — the primitive M9 needs to read the rail geometry we already
 * ship (`docs/proposals/timeline-movement-capture.md` §13.2).
 *
 * The gap it closes: `PmtilesDirectory` plans byte ranges and hands the payload to MapLibre
 * untouched, so **nothing in this codebase could read a tile's contents**. Rail lines are in every
 * region archive on disk, and were unreachable to code.
 *
 * ### Scope
 *
 * Takes **already-decompressed** tile bytes. That mirrors the house precedent
 * (`PmtilesDirectory.decodeDirectory(decompressed: ByteArray)`) and sidesteps a real problem:
 * PMTiles stores tiles gzipped (`tile_compression = 2` on our archives) and `commonMain` has no
 * decompressor — `java.util.zip` is reachable only from JVM/Android source sets. Making the caller
 * decompress keeps this module dependency-free and platform-agnostic; a shared gunzip seam is its
 * own decision, not this one's to make silently.
 *
 * No protobuf runtime. MVT is a small fixed schema (spec 2.1) and the repo already hand-rolls
 * varints in two places; a dependency would cost more than the ~80 lines of wire parsing here.
 *
 * ### Robustness
 *
 * Tile bytes are **untrusted input** — they arrive over the network from a CDN. Every decode path is
 * bounds-checked and a malformed tile yields an empty or partial result rather than an exception,
 * matching `GoogleTimelineParser`'s posture for imported files. An unknown field is skipped by wire
 * type, so a future schema revision degrades instead of failing.
 */
object MvtDecoder {

    /**
     * Decode a tile. Returns its layers in encounter order; a malformed tile yields whatever parsed
     * cleanly before the damage (possibly none). Never throws.
     */
    fun decode(bytes: ByteArray): List<MvtLayer> = runCatching {
        val layers = ArrayList<MvtLayer>()
        val r = ProtoReader(bytes, 0, bytes.size)
        while (r.hasMore()) {
            val (field, wire) = r.readTag() ?: break
            if (field == TILE_LAYERS && wire == WIRE_LEN) {
                val slice = r.readLengthDelimited() ?: break
                decodeLayer(bytes, slice.first, slice.second)?.let { layers += it }
            } else if (!r.skip(wire)) {
                break
            }
        }
        layers
    }.getOrDefault(emptyList())

    // ── layer ────────────────────────────────────────────────────────────────

    private fun decodeLayer(bytes: ByteArray, start: Int, end: Int): MvtLayer? {
        var name: String? = null
        var extent = DEFAULT_EXTENT
        var version = 1
        val keys = ArrayList<String>()
        val values = ArrayList<MvtValue>()
        val featureSlices = ArrayList<Pair<Int, Int>>()

        val r = ProtoReader(bytes, start, end)
        while (r.hasMore()) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == LAYER_NAME && wire == WIRE_LEN ->
                    name = r.readLengthDelimited()?.let { bytes.utf8(it.first, it.second) } ?: return null
                field == LAYER_FEATURES && wire == WIRE_LEN ->
                    r.readLengthDelimited()?.let { featureSlices += it } ?: return null
                field == LAYER_KEYS && wire == WIRE_LEN ->
                    keys += (r.readLengthDelimited()?.let { bytes.utf8(it.first, it.second) } ?: return null)
                field == LAYER_VALUES && wire == WIRE_LEN ->
                    values += (r.readLengthDelimited()?.let { decodeValue(bytes, it.first, it.second) } ?: return null)
                field == LAYER_EXTENT && wire == WIRE_VARINT -> extent = (r.readVarint() ?: return null).toInt()
                field == LAYER_VERSION && wire == WIRE_VARINT -> version = (r.readVarint() ?: return null).toInt()
                else -> if (!r.skip(wire)) return null
            }
        }
        if (name == null || extent <= 0) return null

        val features = featureSlices.mapNotNull { (s, e) -> decodeFeature(bytes, s, e, keys, values) }
        return MvtLayer(name = name, version = version, extent = extent, features = features)
    }

    private fun decodeValue(bytes: ByteArray, start: Int, end: Int): MvtValue {
        val r = ProtoReader(bytes, start, end)
        while (r.hasMore()) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == VALUE_STRING && wire == WIRE_LEN ->
                    return r.readLengthDelimited()?.let { MvtValue.Str(bytes.utf8(it.first, it.second)) }
                        ?: MvtValue.Null
                field == VALUE_FLOAT && wire == WIRE_I32 ->
                    return r.readFixed32()?.let { MvtValue.Num(Float.fromBits(it).toDouble()) } ?: MvtValue.Null
                field == VALUE_DOUBLE && wire == WIRE_I64 ->
                    return r.readFixed64()?.let { MvtValue.Num(Double.fromBits(it)) } ?: MvtValue.Null
                field == VALUE_INT && wire == WIRE_VARINT ->
                    return r.readVarint()?.let { MvtValue.Int64(it) } ?: MvtValue.Null
                field == VALUE_UINT && wire == WIRE_VARINT ->
                    return r.readVarint()?.let { MvtValue.Int64(it) } ?: MvtValue.Null
                field == VALUE_SINT && wire == WIRE_VARINT ->
                    return r.readVarint()?.let { MvtValue.Int64(zigZag(it)) } ?: MvtValue.Null
                field == VALUE_BOOL && wire == WIRE_VARINT ->
                    return r.readVarint()?.let { MvtValue.Bool(it != 0L) } ?: MvtValue.Null
                else -> if (!r.skip(wire)) return MvtValue.Null
            }
        }
        return MvtValue.Null
    }

    // ── feature ──────────────────────────────────────────────────────────────

    private fun decodeFeature(
        bytes: ByteArray,
        start: Int,
        end: Int,
        keys: List<String>,
        values: List<MvtValue>,
    ): MvtFeature? {
        var id = 0L
        var geometryType = MvtGeometryType.UNKNOWN
        val tagIndices = ArrayList<Int>()
        var geomSlice: Pair<Int, Int>? = null

        val r = ProtoReader(bytes, start, end)
        while (r.hasMore()) {
            val (field, wire) = r.readTag() ?: break
            when {
                field == FEATURE_ID && wire == WIRE_VARINT -> id = r.readVarint() ?: return null
                field == FEATURE_TYPE && wire == WIRE_VARINT ->
                    geometryType = MvtGeometryType.of((r.readVarint() ?: return null).toInt())
                field == FEATURE_TAGS && wire == WIRE_LEN -> {
                    // Packed: a run of varints, key/value index pairs into the layer's tables.
                    val (s, e) = r.readLengthDelimited() ?: return null
                    val pr = ProtoReader(bytes, s, e)
                    while (pr.hasMore()) tagIndices += (pr.readVarint() ?: return null).toInt()
                }
                field == FEATURE_TAGS && wire == WIRE_VARINT ->
                    tagIndices += (r.readVarint() ?: return null).toInt() // unpacked (spec-legal)
                field == FEATURE_GEOMETRY && wire == WIRE_LEN -> geomSlice = r.readLengthDelimited() ?: return null
                else -> if (!r.skip(wire)) return null
            }
        }

        // Pair up tags, dropping a trailing odd index or an out-of-range reference rather than failing.
        val tags = LinkedHashMap<String, MvtValue>()
        var i = 0
        while (i + 1 < tagIndices.size) {
            val k = tagIndices[i]
            val v = tagIndices[i + 1]
            if (k in keys.indices && v in values.indices) tags[keys[k]] = values[v]
            i += 2
        }

        val geometry = geomSlice?.let { (s, e) -> decodeGeometry(bytes, s, e) } ?: emptyList()
        return MvtFeature(id = id, type = geometryType, tags = tags, geometry = geometry)
    }

    /**
     * Decode the command stream (spec §4.3) into rings of tile-local integer coordinates.
     *
     * Commands are `(id, count)` packed into one varint; `MoveTo` starts a new ring, `LineTo`
     * extends it, `ClosePath` takes no parameters. Parameters are zig-zag deltas from the running
     * cursor, which persists **across** commands — the cursor is not reset by a new `MoveTo`, which
     * is the classic mis-implementation.
     */
    private fun decodeGeometry(bytes: ByteArray, start: Int, end: Int): List<List<MvtPoint>> {
        val rings = ArrayList<List<MvtPoint>>()
        var current = ArrayList<MvtPoint>()
        var cursorX = 0
        var cursorY = 0

        val r = ProtoReader(bytes, start, end)
        while (r.hasMore()) {
            val command = r.readVarint()?.toInt() ?: break
            val id = command and 0x7
            val count = command ushr 3
            when (id) {
                CMD_MOVE_TO -> repeat(count) {
                    if (current.isNotEmpty()) { rings += current; current = ArrayList() }
                    cursorX += zigZag(r.readVarint() ?: return finish(rings, current)).toInt()
                    cursorY += zigZag(r.readVarint() ?: return finish(rings, current)).toInt()
                    current.add(MvtPoint(cursorX, cursorY))
                }
                CMD_LINE_TO -> repeat(count) {
                    cursorX += zigZag(r.readVarint() ?: return finish(rings, current)).toInt()
                    cursorY += zigZag(r.readVarint() ?: return finish(rings, current)).toInt()
                    current.add(MvtPoint(cursorX, cursorY))
                }
                CMD_CLOSE_PATH -> {
                    // Close by repeating the ring's first point, so consumers see an explicit ring.
                    if (current.isNotEmpty()) current.add(current.first())
                }
                else -> return finish(rings, current) // unknown command — stop, keep what we have
            }
        }
        return finish(rings, current)
    }

    private fun finish(rings: MutableList<List<MvtPoint>>, current: List<MvtPoint>): List<List<MvtPoint>> {
        if (current.isNotEmpty()) rings.add(current)
        return rings
    }

    private fun zigZag(v: Long): Long = (v ushr 1) xor -(v and 1)

    private fun ByteArray.utf8(start: Int, end: Int): String =
        decodeToString(start, end, throwOnInvalidSequence = false)

    // ── field numbers (MVT spec 2.1) ─────────────────────────────────────────

    private const val TILE_LAYERS = 3

    private const val LAYER_VERSION = 15
    private const val LAYER_NAME = 1
    private const val LAYER_FEATURES = 2
    private const val LAYER_KEYS = 3
    private const val LAYER_VALUES = 4
    private const val LAYER_EXTENT = 5

    private const val FEATURE_ID = 1
    private const val FEATURE_TAGS = 2
    private const val FEATURE_TYPE = 3
    private const val FEATURE_GEOMETRY = 4

    private const val VALUE_STRING = 1
    private const val VALUE_FLOAT = 2
    private const val VALUE_DOUBLE = 3
    private const val VALUE_INT = 4
    private const val VALUE_UINT = 5
    private const val VALUE_SINT = 6
    private const val VALUE_BOOL = 7

    private const val CMD_MOVE_TO = 1
    private const val CMD_LINE_TO = 2
    private const val CMD_CLOSE_PATH = 7

    internal const val WIRE_VARINT = 0
    internal const val WIRE_I64 = 1
    internal const val WIRE_LEN = 2
    internal const val WIRE_I32 = 5

    /** The spec's default when a layer omits `extent`. Protomaps emits 4096 explicitly. */
    const val DEFAULT_EXTENT = 4096
}
