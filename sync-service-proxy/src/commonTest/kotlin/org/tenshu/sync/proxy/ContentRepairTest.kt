package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Content repair's decision (`drive-upload-stop-and-reclamation.md` §4.18): which device may put
 * back content the relay no longer holds.
 *
 * ⚠ The answer is one guard — *this device has the plaintext* — and it carries two properties at
 * once, which is why it gets its own tests rather than living inline in the wiring. It keeps the
 * repair **additive** (a device with nothing to offer uploads nothing) and it keeps the repair from
 * **racing a live upload** (only the uploading device holds bytes, and its journal already owns that
 * retry).
 */
class ContentRepairTest {

    private val context = createTestContext(createCoreTestRegistry())
    private val entity: DataId =
        DataId.StringDataId.create(context = context, type = File.ID, keyValue = "/photo.jpg")

    private class Emits {
        val calls = mutableListOf<Triple<String, String, String>>()
        var answer = true
        suspend fun emit(id: DataId, propertyId: String, path: String): Boolean {
            calls += Triple(id.toString(), propertyId, path)
            return answer
        }
    }

    @Test
    fun withLocalBytes_itReEmitsTheSameProperty() = runTest {
        val emits = Emits()
        val repair = localBytesContentRepair(
            readFilePath = { "/photo.jpg" },
            hasLocalBytes = { true },
            emit = emits::emit,
        )

        assertTrue(repair.repair(entity, REPAIRABLE_PROPERTY_IDS.first(), "content-1"))
        assertEquals(1, emits.calls.size)
        assertEquals(REPAIRABLE_PROPERTY_IDS.first(), emits.calls.single().second)
        assertEquals("/photo.jpg", emits.calls.single().third)
    }

    /**
     * ⚠⚠ The case that makes it safe. A device that never downloaded the file declines — so the
     * repair cannot fire on a peer that has merely *heard about* content, which is the same
     * population that would otherwise race an upload still in flight.
     */
    @Test
    fun withoutLocalBytes_itDeclinesAndUploadsNothing() = runTest {
        val emits = Emits()
        val repair = localBytesContentRepair(
            readFilePath = { "/photo.jpg" },
            hasLocalBytes = { false },
            emit = emits::emit,
        )

        assertFalse(repair.repair(entity, REPAIRABLE_PROPERTY_IDS.first(), "content-1"))
        assertTrue(emits.calls.isEmpty(), "a device with nothing to offer must not emit")
    }

    /**
     * ⚠⚠ **A thumbnail resolve must not re-emit the file.** All five thumbnail classes are
     * `Syncable(OnDemand)`, so one arrives here with its own propertyId and its own derived
     * contentId — while `readFilePath` answers with the FILE's path. Without the whitelist the repair
     * would upload the full-size image under the thumbnail's content id, in a state that is entirely
     * ordinary: the file downloaded, the thumbnail not.
     */
    @Test
    fun aThumbnailProperty_isDeclined_notReEmittedFromTheFilesBytes() = runTest {
        val emits = Emits()
        val repair = localBytesContentRepair(
            readFilePath = { "/photo.jpg" },
            hasLocalBytes = { true },
            emit = emits::emit,
        )

        for (thumbnailProperty in listOf(
            "tenshu/file/file/thumbnail",
            "tenshu/file/file/thumbnailLarge",
            "tenshu/file/file/thumbnailXLarge",
            "tenshu/file/file/thumbnailMotion",
        )) {
            assertFalse(
                repair.repair(entity, thumbnailProperty, "content-thumb"),
                "$thumbnailProperty must not be repaired from the file's bytes",
            )
        }
        assertTrue(emits.calls.isEmpty(), "not one byte may be uploaded for a derived property")
    }

    /** ⭐ The positive control: the property the repair exists for is on the whitelist. */
    @Test
    fun theFilesOwnDataProperty_isRepairable() = runTest {
        val emits = Emits()
        val repair = localBytesContentRepair(
            readFilePath = { "/photo.jpg" },
            hasLocalBytes = { true },
            emit = emits::emit,
        )
        assertTrue(repair.repair(entity, REPAIRABLE_PROPERTY_IDS.first(), "content-1"))
        assertEquals(1, emits.calls.size)
    }

    /** An entity whose path cannot be read is a decline, not a throw — the resolve simply retries. */
    @Test
    fun withNoReadablePath_itDeclines() = runTest {
        val emits = Emits()
        val repair = localBytesContentRepair(
            readFilePath = { null },
            hasLocalBytes = { error("must not be asked without a path") },
            emit = emits::emit,
        )

        assertFalse(repair.repair(entity, REPAIRABLE_PROPERTY_IDS.first(), "content-1"))
        assertTrue(emits.calls.isEmpty())
    }

    /** A blank path is an absent one; it must not reach the byte check as `""`. */
    @Test
    fun withABlankPath_itDeclines() = runTest {
        val emits = Emits()
        val repair = localBytesContentRepair(
            readFilePath = { "   " },
            hasLocalBytes = { error("must not be asked with a blank path") },
            emit = emits::emit,
        )

        assertFalse(repair.repair(entity, REPAIRABLE_PROPERTY_IDS.first(), "content-1"))
    }

    /**
     * ⚠ A refused emit is reported as refused. The caller's only use for the answer is telling
     * "this device tried" from "this device cannot help", and a repair that failed at the relay is
     * the first, not the second.
     */
    @Test
    fun aRefusedEmit_isReportedAsFailure() = runTest {
        val emits = Emits().apply { answer = false }
        val repair = localBytesContentRepair(
            readFilePath = { "/photo.jpg" },
            hasLocalBytes = { true },
            emit = emits::emit,
        )

        assertFalse(repair.repair(entity, REPAIRABLE_PROPERTY_IDS.first(), "content-1"))
        assertEquals(1, emits.calls.size, "it did try")
    }

    /**
     * The backfill's variant: the same two guards, but it ENSURES, with the content id, and never
     * forces. Its answer is whether this device acted, not what the ensure found.
     */
    @Test
    fun theEnsureVariantActsOnlyForARepairablePropertyWithLocalBytes_andPassesTheContentId() = runTest {
        val calls = mutableListOf<String>()
        fun ensureWith(hasBytes: Boolean) = localBytesContentEnsure(
            readFilePath = { "/photo.jpg" },
            hasLocalBytes = { hasBytes },
            ensure = { _, propertyId, path, contentId ->
                calls += "$propertyId|$path|$contentId"
                NamedContentEnsure.ACTED
            },
        )

        assertTrue(ensureWith(hasBytes = true).repair(entity, REPAIRABLE_PROPERTY_IDS.first(), "content-1"))
        assertEquals(listOf("${REPAIRABLE_PROPERTY_IDS.first()}|/photo.jpg|content-1"), calls)

        calls.clear()
        assertFalse(ensureWith(hasBytes = false).repair(entity, REPAIRABLE_PROPERTY_IDS.first(), "content-1"))
        assertFalse(
            ensureWith(hasBytes = true).repair(entity, "tenshu/file/thumbnailLarge", "derived-thumb-id"),
            "a thumbnail's content is not at the file's path",
        )
        assertTrue(calls.isEmpty(), "declining touches nothing")
    }

    /**
     * ⚠⚠ A holder that moved on is "this device did not act", and one whose
     * content cannot be read fails loudly, which keeps the pass open. (That a moved-on holder sends
     * nothing is `EnsureContentOnRelayTest`'s.)
     */
    @Test
    fun theEnsureVariantDeclinesAHolderThatMovedOn_andThrowsWhenItCannotTell() = runTest {
        fun ensureAnswering(answer: NamedContentEnsure) = localBytesContentEnsure(
            readFilePath = { "/photo.jpg" },
            hasLocalBytes = { true },
            ensure = { _, _, _, _ -> answer },
        )
        val property = REPAIRABLE_PROPERTY_IDS.first()

        assertTrue(ensureAnswering(NamedContentEnsure.ACTED).repair(entity, property, "content-1"))
        assertFalse(ensureAnswering(NamedContentEnsure.MOVED_ON).repair(entity, property, "content-1"))
        assertFailsWith<IllegalStateException> {
            ensureAnswering(NamedContentEnsure.UNREADABLE).repair(entity, property, "content-1")
        }
    }
}
