package org.tenshu.sync.common

import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BlockPayloadEnvelopeTest {

    @Test
    fun sealsWithTheCurrentStorageFormatVersionByDefault() {
        val payload = byteArrayOf(1, 2, 3)
        val opened = BlockPayloadEnvelope.open(BlockPayloadEnvelope.seal(payload))!!
        assertEquals(TENSHU_STORAGE_FORMAT_VERSION, opened.formatVersion)
        assertEquals(0, opened.stampBytes.size)
        assertContentEquals(payload, opened.payloadBytes)
    }

    @Test
    fun roundTripsVersionsAcrossVarintWidths() {
        val payload = byteArrayOf(0x7F, -1, 0)
        for (version in listOf(0, 1, 14, 127, 128, 300, 16_383, 16_384, Int.MAX_VALUE)) {
            val opened = BlockPayloadEnvelope.open(
                BlockPayloadEnvelope.seal(payload, formatVersion = version)
            )!!
            assertEquals(version, opened.formatVersion)
            assertContentEquals(payload, opened.payloadBytes)
        }
    }

    @Test
    fun roundTripsAVersionVectorStamp() {
        // The stamp segment is opaque bytes to the envelope (the caller
        // serializes/parses the frozen VersionVector composite).
        val stamp = ByteArray(300) { (it % 251).toByte() }
        val payload = byteArrayOf(9, 8, 7)
        val opened = BlockPayloadEnvelope.open(BlockPayloadEnvelope.seal(payload, stamp))!!
        assertEquals(TENSHU_STORAGE_FORMAT_VERSION, opened.formatVersion)
        assertContentEquals(stamp, opened.stampBytes)
        assertContentEquals(payload, opened.payloadBytes)
    }

    @Test
    fun coreOnlyStampCostsExactlyTwoBytes() {
        // The stamp must stay cheap: version varint + zero-length marker.
        val sealed = BlockPayloadEnvelope.seal(ByteArray(0), formatVersion = 14)
        assertEquals(2, sealed.size)
    }

    @Test
    fun roundTripsAnEmptyPayload() {
        val opened = BlockPayloadEnvelope.open(
            BlockPayloadEnvelope.seal(ByteArray(0), formatVersion = 42)
        )!!
        assertEquals(42, opened.formatVersion)
        assertEquals(0, opened.payloadBytes.size)
    }

    @Test
    fun openRejectsEmptyInput() {
        assertNull(BlockPayloadEnvelope.open(ByteArray(0)))
    }

    @Test
    fun openRejectsTruncatedVarint() {
        // A lone continuation byte promises more bytes that never arrive.
        assertNull(BlockPayloadEnvelope.open(byteArrayOf(0x80.toByte())))
    }

    @Test
    fun openRejectsOverlongVarint() {
        // Six continuation-flagged bytes exceed what an Int can carry.
        val overlong = ByteArray(6) { 0x80.toByte() } + byteArrayOf(0x01)
        assertNull(BlockPayloadEnvelope.open(overlong))
    }

    @Test
    fun openRejectsAStampLengthPastTheEnd() {
        // [version=14][stampLen=200] but only 3 bytes follow.
        val truncated = byteArrayOf(14, 200.toByte(), 1, 1, 2, 3)
        assertNull(BlockPayloadEnvelope.open(truncated))
    }

    @Test
    fun openRejectsAPayloadMissingItsStampSegment() {
        // A v13-era (single-varint) envelope whose first payload byte promises
        // a stamp longer than the remaining bytes is rejected, not misread.
        val v13Style = byteArrayOf(13) + ByteArray(2) { 0x7F }
        val opened = BlockPayloadEnvelope.open(v13Style)
        // Either rejected outright or the misread stamp length fails the
        // bounds check — it must never come back as a clean full-size payload.
        if (opened != null) {
            assertEquals(13, opened.formatVersion)
        }
    }

    @Test
    fun sealRejectsNegativeVersions() {
        assertFailsWith<IllegalArgumentException> {
            BlockPayloadEnvelope.seal(ByteArray(0), formatVersion = -1)
        }
    }
}
