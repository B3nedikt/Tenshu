@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.common.proxy.db.sqlite.ReplicaPhase.CHECKPOINT
import org.tenshu.core.common.proxy.db.sqlite.ReplicaPhase.COMMIT
import org.tenshu.core.common.proxy.db.sqlite.ReplicaPhase.OPEN_CLASSIFY
import org.tenshu.core.common.proxy.db.sqlite.ReplicaPhase.OPEN_READ_WRITE
import org.tenshu.core.common.proxy.db.sqlite.ReplicaPhase.STATEMENT
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_BUSY
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CANTOPEN
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CONSTRAINT
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CORRUPT
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_FULL
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_INTERRUPT
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_IOERR
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_LOCKED
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_MISMATCH
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_NOMEM
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_NOTADB
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_PROTOCOL
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_RANGE
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_READONLY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * §5.2.2's table, row by row and column by column, and the two pure pieces beside it: the budget's
 * arithmetic and the judgement of a store's versions.
 */
class ReplicaVerdictsTest {

    private fun row(code: Int, classify: ReplicaVerdict, readWrite: ReplicaVerdict, statement: ReplicaVerdict, commit: ReplicaVerdict, checkpoint: ReplicaVerdict) {
        assertEquals(classify, ReplicaVerdicts.of(code, OPEN_CLASSIFY, protectedDataAvailable = true), "classify rc=$code")
        assertEquals(readWrite, ReplicaVerdicts.of(code, OPEN_READ_WRITE, protectedDataAvailable = true), "read/write rc=$code")
        assertEquals(statement, ReplicaVerdicts.of(code, STATEMENT, protectedDataAvailable = true), "statement rc=$code")
        assertEquals(commit, ReplicaVerdicts.of(code, COMMIT, protectedDataAvailable = true), "commit rc=$code")
        assertEquals(checkpoint, ReplicaVerdicts.of(code, CHECKPOINT, protectedDataAvailable = true), "checkpoint rc=$code")
    }

    @Test
    fun corruptAndNotADatabaseMarkEverywhere() {
        for (code in listOf(SQLITE_CORRUPT, SQLITE_NOTADB)) {
            row(code, ReplicaVerdict.CORRUPT, ReplicaVerdict.CORRUPT, ReplicaVerdict.CORRUPT, ReplicaVerdict.CORRUPT, ReplicaVerdict.CORRUPT)
        }
    }

    @Test
    fun transientFailuresRetryAtOpenAndFenceInAnAccess() {
        for (code in listOf(SQLITE_IOERR, SQLITE_FULL, SQLITE_NOMEM, SQLITE_CANTOPEN, SQLITE_INTERRUPT, SQLITE_PROTOCOL)) {
            row(
                code,
                ReplicaVerdict.RETRY_UNAVAILABLE,
                ReplicaVerdict.RETRY_UNAVAILABLE,
                ReplicaVerdict.COMMIT_FAILED,
                ReplicaVerdict.COMMIT_FAILED,
                ReplicaVerdict.SKIPPED,
            )
        }
    }

    @Test
    fun busyIsASliceAtBeginAndARetryElsewhere() {
        for (code in listOf(SQLITE_BUSY, SQLITE_LOCKED)) {
            row(
                code,
                ReplicaVerdict.RETRY_UNAVAILABLE,
                ReplicaVerdict.RETRY_UNAVAILABLE,
                ReplicaVerdict.COMMIT_FAILED,
                ReplicaVerdict.COMMIT_FAILED,
                ReplicaVerdict.SKIPPED,
            )
            assertEquals(ReplicaVerdict.RETRY_SLICE, ReplicaVerdicts.of(code, OPEN_CLASSIFY, atBegin = true, protectedDataAvailable = true))
            assertEquals(ReplicaVerdict.RETRY_SLICE, ReplicaVerdicts.of(code, OPEN_READ_WRITE, atBegin = true, protectedDataAvailable = true))
        }
    }

    @Test
    fun aReadOnlyResultOnTheClassifyConnectionIsUnclassifiableAndNeverCorrupt() {
        assertEquals(ReplicaVerdict.RETRY_UNCLASSIFIABLE, ReplicaVerdicts.of(SQLITE_READONLY, OPEN_CLASSIFY, protectedDataAvailable = true))
        // Its extended forms map through the primary code.
        val cantInit = SQLITE_READONLY or (5 shl 8)
        assertEquals(ReplicaVerdict.RETRY_UNCLASSIFIABLE, ReplicaVerdicts.of(cantInit and 0xff, OPEN_CLASSIFY, protectedDataAvailable = true))
        assertEquals(ReplicaVerdict.COMMIT_FAILED, ReplicaVerdicts.of(SQLITE_READONLY, STATEMENT, protectedDataAvailable = true))
    }

    @Test
    fun anyOtherCodeIsAProgrammingErrorNeverCorruption() {
        for (code in listOf(SQLITE_CONSTRAINT, SQLITE_MISMATCH, SQLITE_RANGE, 1 /* SQLITE_ERROR */)) {
            row(
                code,
                ReplicaVerdict.RETRY_UNAVAILABLE,
                ReplicaVerdict.RETRY_UNAVAILABLE,
                ReplicaVerdict.COMMIT_FAILED,
                ReplicaVerdict.COMMIT_FAILED,
                ReplicaVerdict.SKIPPED,
            )
        }
    }

    @Test
    fun withProtectedDataUnavailableNoCodeIsCorruption() {
        for (code in listOf(SQLITE_CORRUPT, SQLITE_NOTADB, SQLITE_IOERR, SQLITE_CANTOPEN, SQLITE_BUSY)) {
            fun of(phase: ReplicaPhase) = ReplicaVerdicts.of(code, phase, protectedDataAvailable = false)
            assertEquals(ReplicaVerdict.RETRY_PROTECTED_DATA, of(OPEN_CLASSIFY))
            assertEquals(ReplicaVerdict.RETRY_PROTECTED_DATA, of(OPEN_READ_WRITE))
            assertEquals(ReplicaVerdict.COMMIT_FAILED, of(STATEMENT))
            assertEquals(ReplicaVerdict.COMMIT_FAILED, of(COMMIT))
            assertEquals(ReplicaVerdict.SKIPPED, of(CHECKPOINT))
        }
    }

    // ── the budget's arithmetic (§4.2.1) ──

    @Test
    fun thePlaceholdersAreTheDesigns() {
        val limits = ReplicaLimits()
        assertEquals(0, limits.overflowPages(4061))
        assertEquals(1, limits.overflowPages(4062))
        assertEquals(128, limits.overflowPages(512 * 1024))
        assertEquals(256, limits.headroomPages(100, 0))
        assertEquals(768, limits.largestStatementPages, "H = G + 4 * ov(ROW_MAX)")
        assertEquals(1030, limits.deltaPages, "δ = C + H frames, about 1,030 pages")
        assertEquals(3_000L * 4096, limits.wmaxBytes)
        assertEquals(limits.wmaxBytes / 4, limits.journalSizeLimitBytes)
    }

    @Test
    fun everyRowOperationFitsATransactionThatWasAllowedToBegin() {
        val limits = ReplicaLimits()
        // At the highest size a transaction may begin at, the largest statement still fits...
        assertTrue(limits.fits(limits.beginCeilingBytes, limits.largestStatementPages))
        // ...and one frame further it does not.
        assertFalse(limits.fits(limits.beginCeilingBytes + ReplicaLimits.WAL_FRAME_BYTES, limits.largestStatementPages))
    }

    @Test
    fun wmaxBelowTwoDeltaIsRefused() {
        assertFailsWith<IllegalArgumentException> { ReplicaLimits(wmaxPages = 2_000) }
        // A reset log must leave room for the largest object a migration chunk admits whole
        // (B.3 (35)): Wmax/4 + C + U <= Wmax. With C = G = 8 and one overflow page at ROW_MAX, a
        // rewritten row is H + 3G = 36 pages and a DROPPED one h(0, ROW_MAX) + 4G = 42: the
        // dropped row is the larger, and Wmax = 60 pages, which is 2δ and has room for the
        // rewrite, has none for it.
        val small = ReplicaLimits(wmaxPages = 68, cachePages = 8, statementPages = 8, rowMaxBytes = 8_000)
        assertEquals(36, small.largestStatementPages + 3 * small.statementPages)
        assertEquals(42, small.largestMigrationUnitPages)
        for (pages in listOf(50, 60, 67)) {
            val refused = assertFailsWith<IllegalArgumentException>("Wmax = $pages") { small.copy(wmaxPages = pages) }
            assertTrue("Wmax/4 + C + U" in refused.message.orEmpty(), refused.message)
        }
        // At the placeholders the two are the same 1,536 pages (G = 2 · ov(ROW_MAX)).
        assertEquals(1_536, ReplicaLimits().largestMigrationUnitPages)
    }

    // ── the judgement of versions (§5.2 step 2, §7) ──

    private val build = testBuild()

    private fun reading(
        applicationId: Int = ReplicaSchema.APPLICATION_ID,
        userVersion: Int = 22,
        schemaObjects: Int = 9,
        hasMeta: Boolean = true,
        layout: Int? = 1,
        services: Map<String, Int>? = mapOf("tenshu/file" to 3, "tenshu/sync" to 9),
        wipe: ByteArray? = null,
        migration: ByteArray? = null,
        metaDamage: String? = null,
    ) = ReplicaReading(applicationId, userVersion, schemaObjects, hasMeta, layout, services, wipe, migration, metaDamage)

    @Test
    fun onlyAnEmptyUnstampedFileIsFresh() {
        assertEquals(ReplicaClassification.Fresh, classify(reading(0, 0, 0, false, null, null), build))
        // No schema but a stamp: not fresh, and not ours.
        assertIs<ReplicaClassification.Damaged>(classify(reading(0, 22, 0, false, null, null), build))
        assertIs<ReplicaClassification.Damaged>(classify(reading(applicationId = 0x12345678), build))
        assertIs<ReplicaClassification.Damaged>(classify(reading(layout = null), build))
        assertIs<ReplicaClassification.Damaged>(classify(reading(hasMeta = false), build))
    }

    @Test
    fun allEqualIsCompatible() {
        assertEquals(ReplicaClassification.Compatible, classify(reading(), build))
    }

    @Test
    fun anythingAboveThisBuildIsSkew() {
        assertIs<ReplicaClassification.Skew>(classify(reading(layout = 2), build))
        assertIs<ReplicaClassification.Skew>(classify(reading(userVersion = 23), build))
        assertIs<ReplicaClassification.Skew>(classify(reading(services = mapOf("tenshu/file" to 4, "tenshu/sync" to 9)), build))
        // A service this build does not declare counts as above.
        assertIs<ReplicaClassification.Skew>(
            classify(reading(services = mapOf("tenshu/file" to 3, "tenshu/sync" to 9, "tenshu/new" to 1)), build)
        )
    }

    @Test
    fun aMarkerNeverWidensTheFullCheck() {
        val marker = WipeMarker("forget", "test", 0L, emptyMap()).encode()
        val migrating = MigrationMarker("test", 0L, mapOf("tenshu/file" to 3), "tenshu/file", 2, MigrationPhase.ROWS, null).encode()
        // ⚠ The marker's own half of the resumer rule is part of the judgement, so it is answered
        // on the read-only connection: a frame of a format this build does not know, a wipe that
        // will stamp past this build, a migration whose targets are past it. Each is SKEW with
        // nothing opened read/write, in the foreground and in the background alike.
        assertIs<ReplicaClassification.Skew>(classify(reading(wipe = byteArrayOf(2, 9, 9)), build))
        assertIs<ReplicaClassification.Skew>(classify(reading(migration = byteArrayOf(7)), build))
        assertIs<ReplicaClassification.Skew>(
            classify(reading(wipe = WipeMarker("below-floor", "test", 0L, mapOf("tenshu/core" to 23)).encode()), build),
        )
        assertIs<ReplicaClassification.Skew>(
            classify(reading(migration = MigrationMarker("test", 0L, mapOf("tenshu/file" to 4), "tenshu/file", 3, MigrationPhase.ROWS, null).encode()), build),
        )
        assertIs<ReplicaClassification.Skew>(
            classify(reading(wipe = WipeMarker("x", "test", 0L, mapOf("tenshu/unknown" to 1)).encode()), build),
        )
        // ⚠ A newer build's marker is SKEW whatever ELSE in `meta` is damaged: "above" comes
        // first, and damage in another row must not stop the marker from being read.
        assertIs<ReplicaClassification.Skew>(
            classify(reading(wipe = byteArrayOf(2, 9, 9), metaDamage = "meta.seq is text, not an integer"), build),
        )
        assertIs<ReplicaClassification.Skew>(
            classify(reading(migration = byteArrayOf(7), metaDamage = "meta.replaced_by is integer, not text"), build),
        )
        // A marker row that is itself not a blob reaches here with no bytes, and is damage.
        assertIs<ReplicaClassification.Damaged>(
            classify(reading(wipe = byteArrayOf(), metaDamage = "meta.wipe is text, not a frame"), build),
        )
        // This build's format, and not a frame of it: damage.
        assertIs<ReplicaClassification.Damaged>(classify(reading(wipe = byteArrayOf(1)), build))
        assertIs<ReplicaClassification.Damaged>(classify(reading(migration = byteArrayOf(1, 0)), build))
        // Newer on a service: SKEW whatever marker is present.
        val newer = mapOf("tenshu/file" to 4, "tenshu/sync" to 9)
        assertIs<ReplicaClassification.Skew>(classify(reading(services = newer, wipe = marker), build))
        assertIs<ReplicaClassification.Skew>(classify(reading(services = newer, migration = migrating), build))
        assertIs<ReplicaClassification.Skew>(classify(reading(userVersion = 23, wipe = marker), build))
        // Passing the full check, the marker decides.
        assertEquals(ReplicaClassification.Updating(ReplicaJob.WIPE), classify(reading(wipe = marker), build))
        assertEquals(ReplicaClassification.Updating(ReplicaJob.MIGRATION), classify(reading(migration = migrating), build))
        // The two never coexist (§5.1): both at once is damage, with no order to prefer one in.
        assertIs<ReplicaClassification.Damaged>(classify(reading(wipe = marker, migration = migrating), build))
        // And still SKEW first on a newer store: nothing is judged damaged, or written, there.
        assertIs<ReplicaClassification.Skew>(classify(reading(userVersion = 23, wipe = marker, migration = migrating), build))
        // Behind and marked: still the marker.
        assertEquals(ReplicaClassification.Updating(ReplicaJob.MIGRATION), classify(reading(userVersion = 20, migration = migrating), build))
    }

    @Test
    fun everyCombinationOfLayoutCoreServiceAndMarkersGetsTheVerdictTheFactsGive() {
        // Each axis on its own, then every combination. The expectation below is written from
        // the facts, in the order the design gives them, not from the function under test.
        data class Stamp(val name: String, val version: Int?, val above: Boolean, val behind: Boolean, val belowFloor: Boolean)
        val cores = listOf(
            Stamp("core equal", 22, above = false, behind = false, belowFloor = false),
            Stamp("core behind", 20, above = false, behind = true, belowFloor = false),
            Stamp("core below its floor", 18, above = false, behind = true, belowFloor = true),
            Stamp("core ahead", 23, above = true, behind = false, belowFloor = false),
        )
        val files = listOf(
            Stamp("service equal", 3, above = false, behind = false, belowFloor = false),
            Stamp("service behind", 2, above = false, behind = true, belowFloor = false),
            Stamp("service below its floor", 1, above = false, behind = true, belowFloor = true),
            Stamp("service ahead", 4, above = true, behind = false, belowFloor = false),
            Stamp("service unstamped", null, above = false, behind = true, belowFloor = false),
        )
        val frame = WipeMarker("forget", "test", 0L, emptyMap()).encode()
        val migrating = MigrationMarker("test", 0L, mapOf("tenshu/sync" to 9), "tenshu/sync", 8, MigrationPhase.ROWS, null).encode()
        val markers = listOf("no marker" to (null to null), "wipe" to (frame to null), "migration" to (null to migrating), "both markers" to (frame to migrating))

        val wrong = ArrayList<String>()
        var cases = 0
        for (layoutBehind in listOf(false, true)) for (core in cores) for (file in files) for ((markerName, marker) in markers) {
            cases++
            val (wipe, migration) = marker
            val expected = when {
                core.above || file.above -> "Skew"
                // An older layout is refused before anything of it is judged by this layout's rules.
                layoutBehind -> "OlderLayout"
                wipe != null && migration != null -> "Damaged"
                wipe != null -> "Updating(WIPE)"
                migration != null -> "Updating(MIGRATION)"
                core.belowFloor || file.belowFloor -> "Behind(belowFloor=true)"
                core.behind || file.behind -> "Behind(belowFloor=false)"
                else -> "Compatible"
            }
            val services = buildMap {
                file.version?.let { put("tenshu/file", it) }
                put("tenshu/sync", 9)
            }
            val actual = when (
                val verdict = classify(
                    reading(userVersion = core.version!!, layout = 1, services = services, wipe = wipe, migration = migration),
                    testBuild(layout = if (layoutBehind) 2 else 1),
                )
            ) {
                is ReplicaClassification.Skew -> "Skew"
                is ReplicaClassification.OlderLayout -> "OlderLayout"
                is ReplicaClassification.Damaged -> "Damaged"
                is ReplicaClassification.Updating -> "Updating(${verdict.job})"
                is ReplicaClassification.Behind -> "Behind(belowFloor=${verdict.belowFloor})"
                ReplicaClassification.Compatible -> "Compatible"
                ReplicaClassification.Fresh -> "Fresh"
            }
            if (actual != expected) {
                wrong += "${if (layoutBehind) "layout behind" else "layout equal"}, ${core.name}, ${file.name}, $markerName: expected $expected, got $actual"
            }
        }
        assertEquals(160, cases)
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun behindIsAMigrationAboveTheFloorAndAWipeBelowIt() {
        fun behind(reading: ReplicaReading) = assertIs<ReplicaClassification.Behind>(classify(reading, build))
        assertFalse(behind(reading(userVersion = 20)).belowFloor)
        assertTrue(behind(reading(userVersion = 18)).belowFloor)
        assertFalse(behind(reading(services = mapOf("tenshu/file" to 2, "tenshu/sync" to 9))).belowFloor)
        assertTrue(behind(reading(services = mapOf("tenshu/file" to 1, "tenshu/sync" to 9))).belowFloor)
        // A declared service with no stamp yet is behind, never below a floor.
        assertFalse(behind(reading(services = mapOf("tenshu/sync" to 9))).belowFloor)
    }
}
