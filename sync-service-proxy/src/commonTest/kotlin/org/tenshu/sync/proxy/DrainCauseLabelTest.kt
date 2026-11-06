package org.tenshu.sync.proxy

import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for the one line a maintainer reads when sync has stopped: the stuck-queue summary's
 * cause label ([drainCauseLabel], emitted by `DrainFailureLog`).
 *
 * ⚠ The regression this pins: a [TenshuException]'s `message` is the typed error's **id**, not its
 * payload, so every distinct relay rejection used to print as the same
 * `TenshuException: tenshu/result/error/generic` — discarding exactly what `RestSyncRelay` attaches
 * for this purpose (the HTTP status, the network message, the entitlement marker). On a real iPhone
 * that reduced a stalled Fit queue to "it is stuck", with no way to tell a 402 from an unreachable
 * relay. The label must carry the detail through.
 */
class DrainCauseLabelTest {

    private fun context() = TestProxy(schemaRegistry = createSyntheticTestRegistry()).tContext

    @Test
    fun a_relay_rejection_carries_its_status_not_just_the_error_id() {
        val ex: Throwable = TenshuException(
            GenericError.create(context(), "Server error: 401 Unauthorized")
        )

        val label = ex.drainCauseLabel()

        assertTrue(
            label.contains("401 Unauthorized"),
            "the label must name the rejection, else a stuck queue is undiagnosable: $label",
        )
        assertTrue(label.contains("TenshuException"), label)
        assertTrue(label.contains(GenericError.ID), label)
    }

    @Test
    fun the_entitlement_marker_survives_into_the_label() {
        // A 402 is the one stall with a *user-visible* remedy (subscription), so it must be
        // distinguishable from a transient outage in the log, not only to `isEntitlementRejection`.
        val ex: Throwable = TenshuException(
            GenericError.create(context(), "$ENTITLEMENT_REJECTION_MARKER: push rejected (402 Payment Required)")
        )

        val label = ex.drainCauseLabel()

        assertTrue(label.contains(ENTITLEMENT_REJECTION_MARKER), label)
        assertTrue(label.contains("402"), label)
    }

    @Test
    fun a_plain_throwable_still_reports_type_and_message() {
        assertEquals(
            "IllegalStateException: relay socket closed",
            IllegalStateException("relay socket closed").drainCauseLabel(),
        )
    }

    @Test
    fun a_messageless_throwable_degrades_to_its_type() {
        assertEquals("IllegalStateException", IllegalStateException().drainCauseLabel())
    }
}
