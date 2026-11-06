package org.tenshu.logging.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 6 of `docs/proposals/core-error-channel.md` (§5 PII-by-construction;
 * threat-model T-L-03): pins the upload-seam redaction contract.
 *
 * Two omissions are load-bearing — `deviceId` is identifying across uploads,
 * and `internalDetail` is the only freetext field in the system. Both must
 * be **dropped** before any payload reaches the Console wire. If a future
 * change adds a new `ErrorEvent` field, this test should fail until the
 * redaction list explicitly accounts for the new field (either preserve it
 * verbatim if it's safe, or document why it's dropped).
 */
class ReportEventRedactionTest {

    private val sample = TestUploadableEvent(
        errorTypeId = "tenshu/core/error/dispatch/missing-parameter",
        operationId = "tenshu/sync/operation/createrealm",
        callstackDepth = 3,
        capturedAtMillis = 1_717_200_000_000L,
        appId = "control",
        appVersion = "1.0.0",
        osLabel = "macOS 14",
    )

    @Test
    fun reportEvent_preservesAllSafeFieldsVerbatim() {
        val report = sample.toReportEvent()
        assertEquals(sample.errorTypeId, report.errorTypeId)
        assertEquals(sample.operationId, report.operationId)
        assertEquals(sample.callstackDepth, report.callstackDepth)
        assertEquals(sample.capturedAtMillis, report.capturedAtMillis)
        assertEquals(sample.appId, report.appId)
        assertEquals(sample.appVersion, report.appVersion)
        assertEquals(sample.osLabel, report.osLabel)
    }

    @Test
    fun reportEvent_doesNotMentionDeviceIdOrInternalDetail() {
        // Structural guard against future "let's just add it back" edits:
        // a Kotlin `data class`'s `toString()` lists every component by name,
        // so checking for these tokens catches anyone adding them as fields
        // (the proposal §5 / T-L-03 redaction is structural, not regex-based —
        // these names must not exist on `ReportEvent` at all).
        val rendered = sample.toReportEvent().toString()
        assertFalse(
            "deviceId" in rendered,
            "ReportEvent.toString() must not mention deviceId — proposal §5 / T-L-03; got: $rendered"
        )
        assertFalse(
            "internalDetail" in rendered,
            "ReportEvent.toString() must not mention internalDetail — proposal §5 / T-L-03; got: $rendered"
        )
    }

    @Test
    fun errorReport_create_mintsFreshUploadIdPerCall() {
        val a = ErrorReport.create(listOf(sample))
        val b = ErrorReport.create(listOf(sample))
        assertTrue(
            a.uploadId != b.uploadId,
            "uploadId must be freshly minted per upload — proposal §4.3 (no persistent identifier)"
        )
        assertEquals(1, a.events.size)
        assertEquals(sample.errorTypeId, a.events.single().errorTypeId)
    }

    private data class TestUploadableEvent(
        override val errorTypeId: String,
        override val operationId: String,
        override val callstackDepth: Int,
        override val capturedAtMillis: Long,
        override val appId: String,
        override val appVersion: String,
        override val osLabel: String,
    ) : UploadableEvent
}
