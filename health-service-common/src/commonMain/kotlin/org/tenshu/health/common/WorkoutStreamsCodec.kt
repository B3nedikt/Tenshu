package org.tenshu.health.common

/**
 * The versioned, dependency-free binary codec for a [WorkoutStreams] blob — the byte shape
 * stored in `Workout.Streams` and decoded by the Fit projector on a workout-detail read.
 *
 * The layout is deliberately trivial (fixed-width big-endian `Int` fields, no varints) so it is
 * unambiguously portable across JVM / Android / iOS / JS with zero risk of a platform-specific
 * (de)serialisation bug, and is proven by an encode→decode round-trip test. A workout at 1 Hz
 * for an hour is ~3600 route fixes (16 B each) + ~720 HR samples (8 B each) ≈ 63 KB, well within
 * a stored property.
 *
 * ```
 * [ version:Int ]
 * [ routeCount:Int ] routeCount × [ tSec:Int, latE7:Int, lngE7:Int, altCm:Int ]
 * [ hrCount:Int    ] hrCount    × [ tSec:Int, bpm:Int ]
 * ```
 *
 * [decode] is total and forgiving — a `null`, truncated, or forward-version blob yields
 * [WorkoutStreams.EMPTY] rather than throwing (mirroring the importers' "malformed → empty,
 * never crash" discipline). A workout with no streams stores no blob at all (the property is
 * simply unset), so `decode(null)` is the common "summary-only workout" path.
 */
object WorkoutStreamsCodec {

    /** Current wire version. Bump + branch in [decode] when the layout changes. */
    const val VERSION: Int = 1

    private const val HEADER_INTS = 2       // version + routeCount
    private const val ROUTE_INTS = 4        // tSec, latE7, lngE7, altCm
    private const val HR_INTS = 2           // tSec, bpm
    private const val INT_BYTES = 4

    fun encode(streams: WorkoutStreams): ByteArray {
        val size = INT_BYTES * (
            HEADER_INTS +
                streams.route.size * ROUTE_INTS +
                1 +                          // hrCount
                streams.heartRate.size * HR_INTS
            )
        val buf = ByteArray(size)
        var i = 0
        fun put(v: Int) {
            buf[i++] = (v ushr 24).toByte()
            buf[i++] = (v ushr 16).toByte()
            buf[i++] = (v ushr 8).toByte()
            buf[i++] = v.toByte()
        }
        put(VERSION)
        put(streams.route.size)
        for (p in streams.route) { put(p.tSec); put(p.latE7); put(p.lngE7); put(p.altCm) }
        put(streams.heartRate.size)
        for (h in streams.heartRate) { put(h.tSec); put(h.bpm) }
        return buf
    }

    fun decode(bytes: ByteArray?): WorkoutStreams {
        if (bytes == null || bytes.size < INT_BYTES * (HEADER_INTS + 1)) return WorkoutStreams.EMPTY
        return try {
            var i = 0
            fun read(): Int {
                val v = (bytes[i].toInt() and 0xFF shl 24) or
                    (bytes[i + 1].toInt() and 0xFF shl 16) or
                    (bytes[i + 2].toInt() and 0xFF shl 8) or
                    (bytes[i + 3].toInt() and 0xFF)
                i += 4
                return v
            }
            if (read() != VERSION) return WorkoutStreams.EMPTY
            val routeN = read()
            // Guard against a corrupt count claiming more bytes than exist.
            if (routeN < 0 || i + routeN * ROUTE_INTS * INT_BYTES > bytes.size) return WorkoutStreams.EMPTY
            val route = ArrayList<RouteSample>(routeN)
            repeat(routeN) { route += RouteSample(read(), read(), read(), read()) }
            val hrN = read()
            if (hrN < 0 || i + hrN * HR_INTS * INT_BYTES > bytes.size) return WorkoutStreams.EMPTY
            val hr = ArrayList<HrSample>(hrN)
            repeat(hrN) { hr += HrSample(read(), read()) }
            WorkoutStreams(route, hr)
        } catch (_: Exception) {
            WorkoutStreams.EMPTY
        }
    }
}
