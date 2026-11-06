package org.tenshu.sync.common

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelationSliceSnapshotTest {

    /**
     * The relay's `ContentRouting.REF_PATTERN` (`^blob-[0-9a-f]{64}$`) is enforced on **both**
     * PUT and GET. It lives in `:sync-relay-server` (not a dependency here), so it is mirrored
     * — exactly as `ManifestContentRefTest` does for `manifestContentRef`.
     *
     * ⚠ A ref outside it is 400-rejected by the relay, which breaks cross-device bytes and is
     * caught only by `:system-test-utils`. A slice ref has the same exposure as a manifest ref.
     */
    private val refPattern = Regex("^blob-[0-9a-f]{64}$")

    @Test
    fun ref_satisfiesTheRelayRefPattern() {
        val ref = RelationSliceSnapshot.ref(
            realmId = "realm-1",
            relationId = "tenshu/file/folder/files",
            sliceKey = "folder-entity-id",
        )
        assertTrue(
            refPattern.matches(ref),
            "a slice ref the relay would 400-reject breaks cross-device sync: $ref",
        )
    }

    @Test
    fun ref_isDeterministicAndSeparatesEveryCoordinate() {
        fun ref(
            realmId: String = "realm-1",
            relationId: String = "tenshu/file/folder/files",
            sliceKey: String = "folder-a",
        ) = RelationSliceSnapshot.ref(realmId, relationId, sliceKey)

        assertEquals(ref(), ref(), "derivation must be deterministic")
        assertNotEquals(ref(), ref(realmId = "realm-2"), "realm must separate")
        assertNotEquals(ref(), ref(relationId = "tenshu/timeline/moments"), "relation must separate")
        assertNotEquals(ref(), ref(sliceKey = "folder-b"), "slice must separate")
    }

    @Test
    fun ref_isStableAcrossReseals_soAParentNeverHasToBeRewritten() {
        // ⚠⚠ The address must NOT move with `asOfSeq`. A folder entry points at its child
        // folder's slice, so an address that moved on every re-seal would force each ancestor
        // to be re-sealed to fix the link — a leaf edit rewriting the whole tree — and would
        // strand the old blob as garbage no GC pass collects.
        //
        // ⚠ Opposite call from C7's per-property contentId, and deliberately so: there two
        // DIFFERENT artifacts collided on one address (a bug); here one artifact is REPLACED
        // (the intent). What a reader got is answered by the decoded envelope's asOfSeq.
        val first = RelationSliceSnapshot.ref("realm-1", "tenshu/file/folder/files", "folder-a")
        val afterReseal = RelationSliceSnapshot.ref("realm-1", "tenshu/file/folder/files", "folder-a")

        assertEquals(first, afterReseal, "a re-seal must overwrite in place, not orphan a blob")
    }

    @Test
    fun encode_roundTrips() {
        val snapshot = RelationSliceSnapshot(
            asOfSeq = 791_201,
            relationId = "tenshu/file/folder/files",
            sliceKey = "folder-entity-id",
            entryCount = 41,
            entries = byteArrayOf(1, 2, 3, 4, 5),
        )

        val decoded = RelationSliceSnapshot.decode(RelationSliceSnapshot.encode(snapshot))

        assertEquals(snapshot.asOfSeq, decoded?.asOfSeq)
        assertEquals(snapshot.relationId, decoded?.relationId)
        assertEquals(snapshot.sliceKey, decoded?.sliceKey)
        assertEquals(snapshot.entryCount, decoded?.entryCount)
        assertContentEquals(snapshot.entries, decoded?.entries)
    }

    @Test
    fun encode_roundTripsAnUnslicedRelationAndEmptyEntries() {
        // `sliceKey = ""` is the "relation is not sliced" case (§5.7.3), and an empty slice is
        // a legitimate answer — an empty folder is not a missing folder.
        val snapshot = RelationSliceSnapshot(
            asOfSeq = 0,
            relationId = "tenshu/timeline/moments",
            sliceKey = "",
            entryCount = 0,
            entries = ByteArray(0),
        )

        val decoded = RelationSliceSnapshot.decode(RelationSliceSnapshot.encode(snapshot))

        assertEquals("", decoded?.sliceKey)
        assertEquals(0, decoded?.entryCount)
        assertContentEquals(ByteArray(0), decoded?.entries)
    }

    @Test
    fun decode_returnsNullOnATruncatedBlob() {
        // ⚠ Null, never a throw: a snapshot is an optimisation and every reader already falls
        // back to a relation read. A device that cannot read a slice must be slower, not broken.
        val encoded = RelationSliceSnapshot.encode(
            RelationSliceSnapshot(1, "tenshu/file/folder/files", "f", 1, byteArrayOf(9, 9, 9))
        )
        for (length in 0 until encoded.size) {
            assertNull(
                RelationSliceSnapshot.decode(encoded.copyOfRange(0, length)),
                "a blob truncated to $length bytes must decode to null, not throw",
            )
        }
    }

    @Test
    fun decode_returnsNullForAnEnvelopeFromTheFuture() {
        // ⚠⚠ The reason the envelope starts versioned. A newer writer may add envelope fields;
        // an older reader must decline the slice and fall back, never mis-parse it as v1 —
        // which is precisely the failure positional composites cannot avoid (HR #9) and this
        // artifact escapes by not being one.
        val encoded = RelationSliceSnapshot.encode(
            RelationSliceSnapshot(1, "tenshu/file/folder/files", "f", 0, ByteArray(0))
        ).copyOf()
        encoded[0] = (RelationSliceSnapshot.VERSION + 1).toByte()

        assertNull(RelationSliceSnapshot.decode(encoded))
    }

    @Test
    fun entriesStayOpaqueToTheEnvelope() {
        // C8a: the platform must be able to read the envelope of a service whose projection it
        // cannot parse — that is what lets a device SKIP a slice deliberately rather than
        // mistake it for absent. Arbitrary non-UTF8 bytes must survive untouched.
        val garbage = ByteArray(256) { (it - 128).toByte() }
        val decoded = RelationSliceSnapshot.decode(
            RelationSliceSnapshot.encode(
                RelationSliceSnapshot(7, "tenshu/some/unknown/relation", "k", 3, garbage)
            )
        )

        assertEquals(7, decoded?.asOfSeq, "the envelope is readable without knowing the service")
        assertEquals(3, decoded?.entryCount)
        assertContentEquals(garbage, decoded?.entries, "entries must round-trip byte-for-byte")
    }
}
