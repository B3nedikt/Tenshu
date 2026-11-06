package org.tenshu.core.trait

import org.tenshu.core.collection.list.toList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase B1 of `docs/tracks/track-b-control-data.md` (proposal §3.1): `RealmData` is a fieldless
 * marker whose only structural assertion is the creation-timestamp value requirement the Data
 * section sorts by (§2.6 / §8). Display metadata is the consumer's, not the trait's.
 */
class RealmDataTest {

    @Test
    fun realmDataSchema_declaresCreatedAtValueRequirement() {
        val requirementIds = RealmData.Schema.traitRequirements.toList().map { it.id.get() }

        assertTrue(
            RealmData.CREATED_AT in requirementIds,
            "RealmData must require a creation-timestamp value property for the newest-first " +
                "sort (proposal §2.6); got $requirementIds",
        )
    }
}
