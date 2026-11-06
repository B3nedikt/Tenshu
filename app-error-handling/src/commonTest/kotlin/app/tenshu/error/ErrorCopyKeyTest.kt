package app.tenshu.error

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The derivation rule of `core-error-copy-catalogue.md` §5.4, pinned by example.
 *
 * These are the shapes that actually occur in the taxonomy — `/error/` at different depths, a
 * multi-segment tail, camelCase and kebab-case ids side by side — not invented cases. The
 * catalogue-coverage half of the contract lives in `:ui-core`'s `ErrorCopyCatalogueTest`; this
 * only fixes what the rule does.
 */
class ErrorCopyKeyTest {

    @Test
    fun camelCaseTail_splitsIntoSnakeCase() {
        assertEquals("error_not_found", errorCopyKeyForId("tenshu/result/error/notFound").value)
        assertEquals(
            "error_open_target_unavailable",
            errorCopyKeyForId("tenshu/result/error/openTargetUnavailable").value,
        )
    }

    @Test
    fun kebabCaseTail_flattensToUnderscores() {
        assertEquals("error_no_storage", errorCopyKeyForId("tenshu/storage/error/no-storage").value)
    }

    @Test
    fun multiSegmentTail_keepsEverySegment() {
        assertEquals(
            "error_dispatch_missing_parameter",
            errorCopyKeyForId("tenshu/core/error/dispatch/missing-parameter").value,
        )
        assertEquals(
            "error_operation_invalid_parameter",
            errorCopyKeyForId("tenshu/core/error/operation/invalid-parameter").value,
        )
    }

    /** `/error/` is matched at whatever depth it occurs, not at a fixed segment index. */
    @Test
    fun errorSegment_isFoundAtAnyDepth() {
        assertEquals(
            "error_already_exists",
            errorCopyKeyForId("tenshu/file/system/error/alreadyExists").value,
        )
        assertEquals("error_internal", errorCopyKeyForId("tenshu/core/error/internal").value)
    }

    /** An id with no `/error/` segment still derives something usable rather than throwing. */
    @Test
    fun idWithoutErrorSegment_fallsBackToLastSegment() {
        assertEquals("error_something_odd", errorCopyKeyForId("tenshu/somewhere/somethingOdd").value)
        assertEquals("error_bare", errorCopyKeyForId("bare").value)
    }

    /**
     * ⚠⚠ Developer surfaces only. Asserted so that a change making [ErrorCopyKey.humanised]
     * prettier does not accidentally make it look like copy — it is deliberately mechanical.
     */
    @Test
    fun humanised_isMechanicalDePrefixing_notCopy() {
        assertEquals("Not found", ErrorCopyKey("error_not_found").humanised())
        assertEquals("Dispatch missing parameter", ErrorCopyKey("error_dispatch_missing_parameter").humanised())
    }
}
