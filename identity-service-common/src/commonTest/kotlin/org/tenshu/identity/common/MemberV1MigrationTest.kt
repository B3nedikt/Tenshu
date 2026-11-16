package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.schema.migration.MigrationContext
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.toTString
import org.tenshu.identity.IdentityService
import org.tenshu.identity.Member
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `shared-realm-membership-records.md` MR-3 reshaped `Member` (two optional signature fields appended), so the
 * Identity v1 → v2 step must carry a v1 row across, not wipe it: every v1 field copies, and the signature
 * fields arrive **absent** (the row is then unsigned, and not listed — no production shares, RO D8).
 * Runs the step the module actually registers.
 */
class MemberV1MigrationTest {

    private val registry = createCoreTestRegistry().also { it.registerConfig(identityServiceCommonModule) }
    private val context = createTestContext(registry)

    private class V1Row(private val bound: CompositeSchema, fields: List<Data?>) : CompositeDto(fields) {
        override val schema: CompositeSchema get() = bound
    }

    @Test
    fun aV1Row_migratesWithEveryFieldAndNoSignature() {
        val step = registry.schemaMigrations().single { it.serviceId == IdentityService.ID && it.fromVersion == 1 }
        val migration = assertNotNull(step.migrations.singleOrNull { it.typeId == Member.ID }, "the step migrates Member")
        val v1 = V1Row(
            MEMBER_AT_V1,
            listOf(
                "realm-1".toTString(context),
                "realm-1::member-abc".toTString(context),
                "member-abc".toTString(context),
                "Bob".toTString(context),
                "fp-bob".toTString(context),
                Member.Role.MEMBER.toTString(context),
                UnixInstantDto(1_900_000_000_000L),
            ),
        )

        val migrated = migration.migrate(v1, MigrationContext(context, deviceId = "test-device")) as Member

        assertEquals("realm-1", migrated.realmId.get())
        assertEquals("member-abc", migrated.memberId.get())
        assertEquals("Bob", migrated.displayName.get())
        assertEquals("fp-bob", migrated.contactFingerprint.get())
        assertEquals(Member.Role.MEMBER, migrated.role.get())
        assertEquals(1_900_000_000_000L, migrated.joinedAt.get())
        assertNull(migrated.signerPublicKey)
        assertNull(migrated.signature)
    }
}
