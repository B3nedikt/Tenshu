package org.tenshu.core.common.schema.migration

import org.tenshu.core.app.AppService
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.schemaMigration
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.schema.currentVersionFloors
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.schema.toMap
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.service as serviceSchema
import org.tenshu.core.schema.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Init-time chain integrity + derived floors
 * (`core-schema-evolution.md` §4/§8.4): snapshot drift, per-service version
 * gaps and live-schema divergence all refuse to start; the readable floor is
 * DERIVED from the registered chain.
 */
class SchemaMigrationRegistryTest {

    private val chainId = "tenshu/test/migration/chained"
    private val fieldA = "$chainId/a"
    private val fieldB = "$chainId/b"
    private val fieldC = "$chainId/c"

    private val chainV1: CompositeSchema = composite(chainId, fields = { string(fieldA) })
    private val chainV2: CompositeSchema = composite(
        chainId,
        fields = { string(fieldA); string(fieldB, isOptional = true) }
    )
    private val chainV3: CompositeSchema = composite(
        chainId,
        fields = { string(fieldA); string(fieldB, isOptional = true); string(fieldC, isOptional = true) }
    )

    /** Same ids as [chainV2] but B required — structurally different. */
    private val chainV2Drifted: CompositeSchema = composite(
        chainId,
        fields = { string(fieldA); string(fieldB) }
    )

    private fun liveRegistryWith(
        serviceVersion: Int,
        liveChainSchema: CompositeSchema? = null,
    ) = createCoreTestRegistry().apply {
        registerConfig(tModule {
            service(serviceSchema(id = THING_SERVICE_ID, version = serviceVersion))
            liveChainSchema?.let { composite(it) }
        })
    }

    private fun registryOf(vararg steps: SchemaMigration) =
        SchemaMigrationRegistry().apply { steps.forEach(::register) }

    private fun step(fromVersion: Int, from: CompositeSchema, to: CompositeSchema) =
        schemaMigration(THING_SERVICE_ID, fromVersion) {
            migration(chainId, from, to)
        }

    @Test
    fun validChainPassesVerify() {
        registryOf(
            step(1, chainV1, chainV2),
            step(2, chainV2, chainV3),
        ).verify(liveRegistryWith(serviceVersion = 3, liveChainSchema = chainV3))
    }

    @Test
    fun toNotMatchingNextFromFailsVerify() {
        val failure = assertFailsWith<IllegalStateException> {
            registryOf(
                step(1, chainV1, chainV2),
                step(2, chainV2Drifted, chainV3),
            ).verify(liveRegistryWith(serviceVersion = 3, liveChainSchema = chainV3))
        }
        assertTrue(failure.message!!.contains("Snapshot drift for '$chainId'"), failure.message)
    }

    @Test
    fun lastToNotMatchingLiveSchemaFailsVerify() {
        val failure = assertFailsWith<IllegalStateException> {
            registryOf(
                step(1, chainV1, chainV2),
            ).verify(liveRegistryWith(serviceVersion = 2, liveChainSchema = chainV3))
        }
        assertTrue(failure.message!!.contains("live schema"), failure.message)
    }

    @Test
    fun versionGapPerServiceFailsVerify() {
        val failure = assertFailsWith<IllegalStateException> {
            registryOf(
                step(2, chainV1, chainV2),
                step(4, chainV2, chainV3),
            ).verify(liveRegistryWith(serviceVersion = 5))
        }
        assertTrue(failure.message!!.contains("gap"), failure.message)
    }

    @Test
    fun chainNotReachingCurrentVersionFailsVerify() {
        val failure = assertFailsWith<IllegalStateException> {
            registryOf(
                step(1, chainV1, chainV2),
                step(2, chainV2, chainV3),
            ).verify(liveRegistryWith(serviceVersion = 5, liveChainSchema = chainV3))
        }
        assertTrue(failure.message!!.contains("current"), failure.message)
    }

    @Test
    fun floorWithoutMigrationsEqualsCurrent() {
        assertEquals(4, SchemaMigrationRegistry().floorFor(THING_SERVICE_ID, currentVersion = 4))
    }

    @Test
    fun floorIsTheLowestContiguousFromVersion() {
        val registry = registryOf(
            step(2, chainV1, chainV2),
            step(3, chainV2, chainV3),
        )
        assertEquals(2, registry.floorFor(THING_SERVICE_ID, currentVersion = 4))
        // A different service is untouched by this chain.
        assertEquals(7, registry.floorFor("tenshu/test/other/service", currentVersion = 7))
    }

    @Test
    fun stepsForReturnsTheOrderedWindow() {
        val s2 = step(2, chainV1, chainV2)
        val s3 = step(3, chainV2, chainV3)
        val registry = registryOf(s3, s2)
        assertEquals(listOf(s2, s3), registry.stepsFor(THING_SERVICE_ID, fromVersion = 2, toVersion = 4))
        assertEquals(listOf(s3), registry.stepsFor(THING_SERVICE_ID, fromVersion = 3, toVersion = 4))
    }

    @Test
    fun nestedReshapeIsRejectedLoudly() {
        val nestedId = "tenshu/test/migration/nested"
        val nestedLive = composite(nestedId, fields = { string("$nestedId/x") })
        val parentId = "tenshu/test/migration/parent"
        val parentLive = composite(
            parentId,
            fields = {
                string("$parentId/name")
                composite("$parentId/child", type = nestedLive)
            }
        )
        val live = createCoreTestRegistry().apply {
            registerConfig(tModule {
                service(serviceSchema(id = THING_SERVICE_ID, version = 2))
                composite(nestedLive)
                composite(parentLive)
            })
        }
        val failure = assertFailsWith<IllegalStateException> {
            registryOf(
                schemaMigration(THING_SERVICE_ID, fromVersion = 1) {
                    migration(nestedId, from = nestedLive, to = nestedLive)
                }
            ).verify(live)
        }
        assertTrue(failure.message!!.contains("not yet supported"), failure.message)
        assertTrue(failure.message!!.contains(parentId), failure.message)
    }

    @Test
    fun currentVersionFloorsDerivesFromRegisteredModuleMigrations() {
        val registry = createCoreTestRegistry().apply {
            registerConfig(tModule {
                service(serviceSchema(id = THING_SERVICE_ID, version = 3))
                // Two empty steps — bumps that reshaped no types still hold the floor open.
                schemaMigration(schemaMigration(THING_SERVICE_ID, fromVersion = 1) {})
                schemaMigration(schemaMigration(THING_SERVICE_ID, fromVersion = 2) {})
            })
        }
        val tContext = createTestContext()
        val floors = registry.currentVersionFloors(tContext).toMap()
        val current = registry.currentVersionVector(tContext).toMap()

        assertEquals(3, current[THING_SERVICE_ID])
        assertEquals(1, floors[THING_SERVICE_ID])
        // Services without a registered chain keep floor == current.
        // ⚠ `AppService` is excluded because it HAS one since TT-1b (v1 → v2,
        // `control-cross-app-open.md`) — it belongs to the other half of this rule, which
        // `SchemaVersioningWireTest` asserts directly.
        val unchained = { id: String -> id != THING_SERVICE_ID && id != AppService.ID }
        assertEquals(current.filterKeys(unchained), floors.filterKeys(unchained))
    }
}
