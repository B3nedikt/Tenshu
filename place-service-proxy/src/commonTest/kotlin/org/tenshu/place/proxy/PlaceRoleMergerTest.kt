package org.tenshu.place.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.entity.Entity
import org.tenshu.core.place.PlaceRole
import org.tenshu.core.place.PlaceRoles
import org.tenshu.core.place.RoleSource
import org.tenshu.place.test.createPlaceProxy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cross-device half of "inference must never overwrite the user" ([PlaceRoleMerger]). The local
 * write path can enforce it by reading before it writes; a block arriving from a peer is the case
 * where nothing else can, so each rule is asserted **in both directions** — a merger that only works
 * when the good row happens to already be here is a merger that works by arrival order.
 */
class PlaceRoleMergerTest {

    private val placeId = "cap:490320000,209030000"

    @Test
    fun theUsersAnswerAlreadyHereSurvivesAnInferenceFromAPeer() = runTest {
        val proxy = createPlaceProxy(this)

        val merged = merge(
            proxy,
            existing = role(proxy, PlaceRoles.WORK, RoleSource.USER, at = 1_000L),
            // Newer, and still loses: a later guess does not get to revise a decision.
            incoming = role(proxy, PlaceRoles.HOME, RoleSource.INFERRED, at = 9_000L),
        )

        assertEquals(PlaceRoles.WORK, merged.role())
        assertEquals(RoleSource.USER, merged.roleSource())
        assertEquals(1_000L, merged.decidedAtMs())
    }

    @Test
    fun theUsersAnswerArrivingFromAPeerBeatsALocalInference() = runTest {
        val proxy = createPlaceProxy(this)

        val merged = merge(
            proxy,
            existing = role(proxy, PlaceRoles.HOME, RoleSource.INFERRED, at = 9_000L),
            // Older, and still wins — the rule is about provenance, not recency.
            incoming = role(proxy, PlaceRoles.WORK, RoleSource.USER, at = 1_000L),
        )

        assertEquals(PlaceRoles.WORK, merged.role())
        assertEquals(RoleSource.USER, merged.roleSource())
        assertEquals(1_000L, merged.decidedAtMs())
    }

    @Test
    fun aDismissalBeatsAnInferredSuggestion() = runTest {
        // ⚠ The case a `role != NONE` shortcut would silently get wrong: "no, this place is not my
        // home" is the user answering, and must outrank the inference exactly as `home` would.
        val proxy = createPlaceProxy(this)

        val merged = merge(
            proxy,
            existing = role(proxy, PlaceRoles.HOME, RoleSource.INFERRED, at = 9_000L),
            incoming = role(proxy, PlaceRoles.NONE, RoleSource.USER, at = 2_000L),
        )

        assertEquals(PlaceRoles.NONE, merged.role())
        assertEquals(RoleSource.USER, merged.roleSource())
    }

    @Test
    fun aDismissalAlreadyHereIsNotUndoneByAnInferenceFromAPeer() = runTest {
        val proxy = createPlaceProxy(this)

        val merged = merge(
            proxy,
            existing = role(proxy, PlaceRoles.NONE, RoleSource.USER, at = 2_000L),
            incoming = role(proxy, PlaceRoles.HOME, RoleSource.INFERRED, at = 9_000L),
        )

        assertEquals(PlaceRoles.NONE, merged.role())
        assertEquals(RoleSource.USER, merged.roleSource())
    }

    @Test
    fun twoInferencesResolveToTheNewerOne() = runTest {
        val proxy = createPlaceProxy(this)

        val merged = merge(
            proxy,
            existing = role(proxy, PlaceRoles.HOME, RoleSource.INFERRED, at = 1_000L),
            incoming = role(proxy, PlaceRoles.WORK, RoleSource.INFERRED, at = 5_000L),
        )

        assertEquals(PlaceRoles.WORK, merged.role())
        assertEquals(5_000L, merged.decidedAtMs())
    }

    @Test
    fun aStaleInferenceFromAPeerDoesNotDisplaceANewerLocalOne() = runTest {
        // The same rule from the other side — otherwise "newest wins" is really "theirs wins".
        val proxy = createPlaceProxy(this)

        val merged = merge(
            proxy,
            existing = role(proxy, PlaceRoles.WORK, RoleSource.INFERRED, at = 5_000L),
            incoming = role(proxy, PlaceRoles.HOME, RoleSource.INFERRED, at = 1_000L),
        )

        assertEquals(PlaceRoles.WORK, merged.role())
        assertEquals(5_000L, merged.decidedAtMs())
    }

    @Test
    fun twoUserAnswersAlsoResolveToTheNewerOne() = runTest {
        // Same-provenance recency applies to the user too: the person changed their mind.
        val proxy = createPlaceProxy(this)

        val merged = merge(
            proxy,
            existing = role(proxy, PlaceRoles.HOME, RoleSource.USER, at = 1_000L),
            incoming = role(proxy, PlaceRoles.NONE, RoleSource.USER, at = 5_000L),
        )

        assertEquals(PlaceRoles.NONE, merged.role())
        assertEquals(5_000L, merged.decidedAtMs())
    }

    @Test
    fun mergingARowWithItselfIsANoOp() = runTest {
        // Idempotence: a replayed block must leave the row exactly as it was.
        val proxy = createPlaceProxy(this)
        val row = role(proxy, PlaceRoles.HOME, RoleSource.USER, at = 4_000L)

        val merged = merge(proxy, existing = row, incoming = row)

        assertEquals(placeId, merged.placeId())
        assertEquals(PlaceRoles.HOME, merged.role())
        assertEquals(RoleSource.USER, merged.roleSource())
        assertEquals(4_000L, merged.decidedAtMs())
    }

    @Test
    fun anUnknownRoleSourceDegradesToTheWeakerOne() = runTest {
        // A newer peer's vocabulary, or a garbled value: it must not be able to outrank the user.
        val proxy = createPlaceProxy(this)

        val merged = merge(
            proxy,
            existing = role(proxy, PlaceRoles.HOME, RoleSource.USER, at = 1_000L),
            incoming = role(proxy, PlaceRoles.WORK, roleSource = "administrator", at = 9_000L),
        )

        assertEquals(PlaceRoles.HOME, merged.role())
        assertEquals(RoleSource.USER, merged.roleSource())
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun merge(proxy: TestProxy, existing: PlaceRole, incoming: PlaceRole): Data =
        PlaceRoleMerger().merge(proxy.executionContext, existing, incoming)

    private fun role(
        proxy: TestProxy,
        role: String,
        roleSource: String,
        at: Long,
    ): PlaceRole = PlaceRole.create(
        context = proxy.executionContext.context,
        placeId = placeId,
        role = role,
        roleSource = roleSource,
        decidedAt = UnixInstantDto(at),
    )

    private fun Data.placeId(): String = string(PlaceRole.Fields.PLACE_ID)
    private fun Data.role(): String = string(PlaceRole.Fields.ROLE)
    private fun Data.roleSource(): String = string(PlaceRole.Fields.ROLE_SOURCE)

    private fun Data.decidedAtMs(): Long =
        (this as Entity<*>)
            .getFieldValue<org.tenshu.core.time.UnixInstant>(PlaceRole.Fields.DECIDED_AT)
            .toEpochMilliseconds()

    /** The merger returns an `Entity`, so read fields off the entity rather than the composite. */
    private fun Data.string(fieldId: String): String =
        (this as Entity<*>).getFieldValue<TString>(fieldId).get()
}
