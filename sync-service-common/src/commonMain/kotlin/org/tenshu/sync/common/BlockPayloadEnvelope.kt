package org.tenshu.sync.common

import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TContext

/**
 * Block version stamp (`core-schema-evolution.md` §5, upgraded to the full
 * vector per §4.1.2 at E1): every block payload's *plaintext* begins with
 *
 * ```
 * [varint coreVersion][varint stampLen][stampLen bytes][payload composite bytes]
 * ```
 *
 * - `coreVersion` — the producing `TENSHU_STORAGE_FORMAT_VERSION`, as a bare
 *   unsigned-LEB128 varint. Kept *outside* the composite stamp on purpose: it
 *   is readable with zero schema machinery, so even a peer whose composite
 *   parser can't handle these bytes can still name the version that produced
 *   them (the freeze-robust floor of the design).
 * - `stampBytes` — the emitter's full [org.tenshu.core.common.schema.VersionVector],
 *   serialized under its **frozen-forever** schema (§6.2). Payloads embed
 *   foreign-service types (`genericData` precedent), so decode needs a coherent
 *   multi-service snapshot — one version can't describe a block (§4.1.2).
 *   `stampLen == 0` means "core-only stamp" (test convenience; production emit
 *   always writes the vector).
 *
 * The stamp lives **inside the ciphertext** — the relay has no business reading
 * it (House Rule #1). Since the §7.5 relay-gate split (E2e) the relay has no
 * payload version at all: its 426 gate keys on `RELAY_PROTOCOL_VERSION`, its own
 * wire contract, and the plaintext `formatVersion` on `/sync/realm` is a
 * transition-window leftover the relay ignores (§7.8). This stamp is now the
 * **only** carrier of "which schema produced these bytes", which is what makes
 * upcast-on-read (§8.2) possible.
 *
 * Why: the relay backlog mixes versions across months of immutable history, and
 * without a per-block stamp *nothing can tell which schema produced a given
 * block's bytes* — positional composites garble rather than error, so every
 * future migration strategy (E2 upcast-on-read) is dead on arrival. Local
 * stores don't need per-row stamps (wholesale-versioned via `PRAGMA
 * user_version`); the block is the unit that must self-describe. Per-block, not
 * per-composite: a block is produced atomically under one schema version.
 *
 * Sealed at every `SyncObserver` emit site; opened in the one shared decode
 * funnel (`SyncProcessor.decryptAndParseCipher`), which routes the stamp through the E2c
 * `PayloadUpcaster` wherever migration chains are registered. Skew fencing lives in
 * `SchemaPullFence`.
 */
object BlockPayloadEnvelope {

    /** A successfully opened envelope. */
    class Opened(
        /** The producing `TENSHU_STORAGE_FORMAT_VERSION`. */
        val formatVersion: Int,
        /**
         * The emitter's serialized frozen `VersionVector`, or empty for a
         * core-only stamp. Parse with the ordinary `BinaryParser` when needed.
         */
        val stampBytes: ByteArray,
        /** The payload composite bytes after the stamp. */
        val payloadBytes: ByteArray,
    )

    /**
     * Prefix [payloadBytes] with [formatVersion] and the serialized
     * `VersionVector` [stampBytes] (empty = core-only stamp).
     */
    fun seal(
        payloadBytes: ByteArray,
        stampBytes: ByteArray = EMPTY,
        formatVersion: Int = TENSHU_STORAGE_FORMAT_VERSION,
    ): ByteArray {
        require(formatVersion >= 0) { "formatVersion must be non-negative: $formatVersion" }
        val header = ByteArray(2 * MAX_VARINT_BYTES + stampBytes.size)
        var length = writeVarint(header, 0, formatVersion)
        length = writeVarint(header, length, stampBytes.size)
        stampBytes.copyInto(header, length)
        length += stampBytes.size
        return header.copyOf(length) + payloadBytes
    }

    /**
     * Read the stamp off [bytes], or `null` if the prefix is malformed (empty
     * input, truncated/overlong varint, or a stamp length running past the
     * end). A `null` means the bytes were not produced by a stamping emitter;
     * callers drop the payload exactly as they would on a parse failure.
     */
    fun open(bytes: ByteArray): Opened? {
        val (formatVersion, afterVersion) = readVarint(bytes, 0) ?: return null
        val (stampLen, afterLen) = readVarint(bytes, afterVersion) ?: return null
        if (stampLen < 0 || afterLen + stampLen > bytes.size) return null
        return Opened(
            formatVersion = formatVersion,
            stampBytes = bytes.copyOfRange(afterLen, afterLen + stampLen),
            payloadBytes = bytes.copyOfRange(afterLen + stampLen, bytes.size),
        )
    }

    /** Writes [value] as unsigned LEB128 at [offset]; returns the new offset. */
    private fun writeVarint(target: ByteArray, offset: Int, value: Int): Int {
        var remaining = value
        var index = offset
        while (true) {
            val sevenBits = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining == 0) {
                target[index++] = sevenBits.toByte()
                return index
            }
            target[index++] = (sevenBits or 0x80).toByte()
        }
    }

    /** Reads an unsigned LEB128 at [offset]; `null` on truncation/overflow. */
    private fun readVarint(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        var value = 0
        var shift = 0
        var index = offset
        while (true) {
            if (index >= bytes.size || index - offset >= MAX_VARINT_BYTES) return null
            val byte = bytes[index].toInt()
            value = value or ((byte and 0x7F) shl shift)
            index++
            if (byte and 0x80 == 0) break
            shift += 7
        }
        if (value < 0) return null // 5-byte varints can overflow into the sign bit
        return value to index
    }

    /**
     * This runtime's stamp bytes — the serialized frozen `VersionVector`
     * assembled by walking the registered module graph (§4.1.1/§4.1.2).
     *
     * Extracted so the one *production* emitter ([seal] call sites in
     * `SyncObserver`) and any hand-built block share one definition. ⚠ Hand-built
     * blocks **must** pass a real stamp: from E2c on, a core-only (empty) stamp is
     * dropped BY DECISION whenever any migration chain is registered — its
     * producing per-service versions cannot be proven — and a chain is registered
     * in production since E2d-1 (`SyncService` v1→v2).
     */
    fun stampOf(schemas: SchemaProvider, context: TContext): ByteArray =
        BinarySerializer().serialize(schemas.currentVersionVector(context))

    /** An Int fits in at most 5 LEB128 bytes. */
    private const val MAX_VARINT_BYTES = 5

    private val EMPTY = ByteArray(0)
}
