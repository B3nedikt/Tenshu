package app.tenshu.ui.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import app.tenshu.error.ErrorCopyKey
import app.tenshu.error.errorCopyKeyForId

/**
 * The guard that makes a **derived** catalogue key safe to use.
 *
 * `core-error-copy-catalogue.md` §5.4 derives each key from the error's schema id rather than
 * tabulating it, so the two worlds' keys line up mechanically for the n=2 merge (ADR 0016 anchor
 * #8). The cost, recorded in §5.1: an id with no catalogue entry derives a key that silently
 * resolves to the fallback, where a `when` over known ids would have been exhaustive. This test is
 * what buys that back — it is the only thing standing between a typo and a user seeing
 * "Something went wrong" for an error somebody wrote copy for.
 *
 * ⚠⚠ **What it does not cover.** [TAXONOMY] is a hand-maintained list, so a *new* `TError` subtype
 * added tomorrow is not caught here — it simply renders the fallback, correctly but blandly. Making
 * completeness automatic needs a check where every service module is visible at once
 * (`:system-test-utils`, which is where `SchemaShapeBaselineEnforcementTest` lives); that belongs
 * to EC-5's sweep, not here. What this test *does* catch is the likely mistake today: an entry
 * added to `strings.xml` under a name the derivation never produces.
 */
class ErrorCopyCatalogueTest {

    /**
     * Every concrete `TError` id in the tree, measured 2026-08-28 by
     * `grep -rn 'const val ID = "[^"]*error[^"]*"'` over non-test Kotlin.
     *
     * ⚠ Written as literals rather than as `NotFoundError.ID` references on purpose: `:ui-core`
     * cannot see `:file-service` or `:map-region-service`, so half the list would have to be
     * literals anyway, and a half-and-half list reads as if the literals were the afterthought.
     * The ids are wire-stable (House Rule 9 makes changing one a migration), so the duplication is
     * not the fragile kind.
     */
    private val taxonomy = listOf(
        "tenshu/core/error/dispatch/missing-parameter",
        "tenshu/core/error/dispatch/unknown-parameter",
        "tenshu/core/error/dispatch/wrong-parameter",
        "tenshu/core/error/internal",
        "tenshu/core/error/operation/invalid-parameter",
        "tenshu/file/system/error/alreadyExists",
        "tenshu/file/system/error/contentPinned",
        "tenshu/file/system/error/stillSyncing",
        "tenshu/file/system/error/moveNeedsLocalBytes",
        "tenshu/mapRegion/service/error/meteredNetworkRefused",
        "tenshu/result/error/entitlementUnverified",
        "tenshu/result/error/generic",
        "tenshu/result/error/invalidCaller",
        "tenshu/result/error/notFound",
        "tenshu/result/error/openTargetUnavailable",
        "tenshu/result/error/relayRouteUnsupported",
        "tenshu/sync/service/error/recoveryPhraseUnreadable",
        "tenshu/sync/service/error/deviceAlreadySetUp",
        "tenshu/result/error/semanticSearchUnavailable",
        "tenshu/result/error/faceGroupsUnavailable",
        "tenshu/result/error/modelUnavailable",
        "tenshu/result/error/indexPassRefused",
        "tenshu/result/error/unannouncedSession",
        "tenshu/result/error/unsupportedOperation",
        "tenshu/storage/error/insufficient",
        "tenshu/storage/error/no-storage",
    )

    /** Forward: no known error type falls through to the generic sentence for want of an entry. */
    @Test
    fun everyKnownErrorId_hasACatalogueEntry() {
        val missing = taxonomy.filter { errorCopyKeyForId(it).value !in CATALOGUE }
        assertTrue(
            missing.isEmpty(),
            "No catalogue entry for: " +
                missing.joinToString { "$it -> ${errorCopyKeyForId(it).value}" },
        )
    }

    /**
     * Reverse: no entry is unreachable. Catches the two-place edit going wrong in the other
     * direction — a string added to `strings.xml` (and to `CATALOGUE`) under a name the derivation
     * never produces, which would look correct in review and never render.
     *
     * ⚠ Keys for states that are not `TError`s (Control not running) are legitimate but derive
     * from nothing, so they are declared in `ErrorCopyKey.MINTED` and accepted here. Declaring
     * them in code rather than only in `strings.xml` is what keeps a typo distinguishable from a
     * deliberate key.
     */
    @Test
    fun everyCatalogueEntry_isReachableFromSomeErrorId() {
        val reachable = taxonomy.map { errorCopyKeyForId(it).value }.toSet() +
            ErrorCopyKey.MINTED.map { it.value }
        val orphans = CATALOGUE.keys - reachable
        assertTrue(
            orphans.isEmpty(),
            "Catalogue entries that no error id derives and that are not declared in " +
                "ErrorCopyKey.MINTED: $orphans",
        )
    }

    /**
     * ⚠ Derivation keeps only what follows the last `/error/`, so two services could name errors
     * that collide on one key and silently share copy. None do today; this fails the moment two
     * would, which is the point at which the derivation rule needs revisiting rather than the
     * catalogue.
     */
    @Test
    fun noTwoErrorIds_deriveTheSameKey() {
        val byKey = taxonomy.groupBy { errorCopyKeyForId(it).value }
        val collisions = byKey.filterValues { it.size > 1 }
        assertTrue(collisions.isEmpty(), "Ids sharing a derived key: $collisions")
    }

    /** The fallback entry every user-facing renderer needs is present and is not itself derived. */
    @Test
    fun fallbackKey_isNotSomethingAnErrorDerives() {
        assertTrue(
            taxonomy.none { errorCopyKeyForId(it) == ErrorCopyKey.UNEXPECTED },
            "An error id derives the fallback key, so a real error would be indistinguishable " +
                "from a catalogue miss",
        )
        assertEquals("error_unexpected", ErrorCopyKey.UNEXPECTED.value)
    }
}
