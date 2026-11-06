package org.tenshu.core.common.schema.migration

import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.constant
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.constant as constantSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.service as serviceSchema
import org.tenshu.core.schema.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The E2j-1 shape baseline (`core-schema-evolution.md` §8.9): a positional
 * reshape without a service-version bump must fail loudly, because after E2j-2
 * retires the format-version wipe that same mistake is silent corruption rather
 * than a spurious resync.
 */
class SchemaShapeBaselineTest {

    private val pinnedId = "tenshu/test/baseline/pinned"
    private val fieldA = "$pinnedId/a"
    private val fieldB = "$pinnedId/b"

    private val shapeV1: CompositeSchema = composite(pinnedId, fields = { string(fieldA) })

    /** One field appended — the "additive is still breaking" case (§2 fact 1). */
    private val shapeV2: CompositeSchema = composite(
        pinnedId,
        fields = { string(fieldA); string(fieldB, isOptional = true) }
    )

    private fun registry(
        schema: CompositeSchema?,
        serviceVersion: Int = 1,
        withService: Boolean = true,
    ): SchemaRegistry = createCoreTestRegistry().apply {
        registerConfig(tModule {
            if (withService) service(serviceSchema(id = THING_SERVICE_ID, version = serviceVersion))
            schema?.let { composite(it) }
        })
    }

    private fun baselineOf(live: SchemaRegistry) =
        SchemaShapeBaseline.parse(SchemaShapeBaseline.render(live))

    // ── constants (the wire-vocabulary half) ─────────────────────────────────────────────────

    private val familyId = "tenshu/test/baseline/family"
    private val family = constantSchema(familyId)
    private val memberOne = constantSchema("$familyId/one", family)
    private val memberTwo = constantSchema("$familyId/two", family)

    /** A registry holding the family plus whichever members are passed. */
    private fun constantRegistry(
        members: List<org.tenshu.core.schema.ConstantSchema>,
        serviceVersion: Int = 1,
    ): SchemaRegistry = createCoreTestRegistry().apply {
        registerConfig(tModule {
            service(serviceSchema(id = THING_SERVICE_ID, version = serviceVersion))
            constant(family)
            members.forEach { constant(it) }
        })
    }

    @Test
    fun aConstantIsPinnedAtAllAndCarriesItsFamily() {
        // ⚠⚠ The hole this closes. Constants have no positional layout, so they were invisible to
        // a baseline that walked composites only — and adding one changed the wire vocabulary with
        // every guard green and no version bump required.
        val rendered = SchemaShapeBaseline.render(constantRegistry(listOf(memberOne)))

        assertTrue(
            rendered.lines().any { it.startsWith("$familyId/one|") && it.endsWith("constant@$familyId") },
            "a constant must be pinned, and name the family it belongs to. Got:\n$rendered",
        )
    }

    @Test
    fun aNewConstantIsAViolationAndIsFlaggedAsAConstant() {
        // ⚠⚠ Flagged, because the instruction differs from a new composite's. A new composite is
        // safe — nothing older can be handed one. A new constant joins a family older peers already
        // read, so they are sent an id they cannot resolve and throw mid-parse.
        val pinned = baselineOf(constantRegistry(listOf(memberOne)))

        val violations = pinned.check(constantRegistry(listOf(memberOne, memberTwo)))

        val unpinned = violations.filterIsInstance<SchemaShapeBaseline.Violation.Unpinned>()
        assertEquals(listOf("$familyId/two"), unpinned.map { it.typeId })
        assertTrue(unpinned.single().isConstant, "a new constant must be flagged as one")
    }

    @Test
    fun aRemovedConstantIsReportedAsRetired() {
        // ⚠ The other direction, and it is not symmetric-but-harmless: retiring a constant that an
        // older peer still *sends* leaves this build unable to parse it.
        val pinned = baselineOf(constantRegistry(listOf(memberOne, memberTwo)))

        val violations = pinned.check(constantRegistry(listOf(memberOne)), expectExhaustive = true)

        assertEquals(
            listOf("$familyId/two"),
            violations.filterIsInstance<SchemaShapeBaseline.Violation.Retired>().map { it.typeId },
        )
    }

    @Test
    fun anUnchangedConstantSetReportsNothing() {
        // ⚠ The control: `check` and `render` must read the SAME source. When only `render` knew
        // about constants, every pinned constant came back as Retired on the very first run.
        val live = constantRegistry(listOf(memberOne, memberTwo))

        assertEquals(emptyList(), baselineOf(live).check(live, expectExhaustive = true))
    }

    @Test
    fun renderRoundTripsThroughParseAndFindsNothingToReport() {
        val live = registry(shapeV1)
        assertEquals(emptyList(), baselineOf(live).check(live, expectExhaustive = true))
    }

    @Test
    fun reshapeWithoutABumpIsAHardViolationNamingBothShapes() {
        val baseline = baselineOf(registry(shapeV1, serviceVersion = 1))

        // Same service version, different shape — exactly the mistake E2j-2 makes dangerous.
        val violations = baseline.check(registry(shapeV2, serviceVersion = 1))

        val reshaped = violations.filterIsInstance<SchemaShapeBaseline.Violation.ReshapedWithoutBump>()
        assertEquals(1, reshaped.size, violations.toString())
        assertEquals(pinnedId, reshaped.single().typeId)
        assertEquals(THING_SERVICE_ID, reshaped.single().serviceId)
        assertTrue(fieldB in reshaped.single().liveShape, reshaped.single().liveShape)
        assertTrue(fieldB !in reshaped.single().pinnedShape, reshaped.single().pinnedShape)

        val message = formatShapeBaselineFailure(violations, regenerateHint = "regen hint")!!
        assertTrue("RESHAPED WITHOUT A VERSION BUMP" in message, message)
        assertTrue(pinnedId in message && "regen hint" in message, message)
    }

    @Test
    fun reshapeWithABumpIsOnlyAStaleBaseline() {
        val baseline = baselineOf(registry(shapeV1, serviceVersion = 1))

        val violations = baseline.check(registry(shapeV2, serviceVersion = 2))

        val stale = violations.filterIsInstance<SchemaShapeBaseline.Violation.StaleBaseline>()
        assertEquals(1, stale.size, violations.toString())
        assertEquals(1, stale.single().pinnedVersion)
        assertEquals(2, stale.single().liveVersion)
        assertTrue(
            violations.none { it is SchemaShapeBaseline.Violation.ReshapedWithoutBump },
            violations.toString(),
        )
    }

    @Test
    fun aNewTypeIsReportedAsUnpinnedRatherThanBlocked() {
        val baseline = baselineOf(registry(schema = null))

        val violations = baseline.check(registry(shapeV1))

        assertEquals(
            listOf(SchemaShapeBaseline.Violation.Unpinned(pinnedId, THING_SERVICE_ID)),
            violations,
        )
    }

    @Test
    fun aRetiredTypeIsReportedOnlyWhenTheRegistryIsSupposedToHoldEverything() {
        val baseline = baselineOf(registry(shapeV1))
        val withoutIt = registry(schema = null)

        // A narrower registry legitimately knows fewer types — judging absence would
        // fail every per-app registry that doesn't happen to register this module.
        assertEquals(emptyList(), baseline.check(withoutIt, expectExhaustive = false))
        assertEquals(
            listOf(SchemaShapeBaseline.Violation.Retired(pinnedId)),
            baseline.check(withoutIt, expectExhaustive = true),
        )
    }

    @Test
    fun theSyntheticPerFieldSchemasAreNotPinned() {
        // `ModuleConfigurationImpl.registerFields` registers a placeholder schema for
        // every field id it sees. They are composites with empty `values`, so without
        // the fieldless filter the baseline would carry thousands of content-free
        // lines and bury the diff it exists to produce.
        val rendered = SchemaShapeBaseline.render(registry(shapeV1))

        assertTrue(rendered.lineSequence().any { it.startsWith("$pinnedId|") }, rendered)
        assertTrue(rendered.lineSequence().none { it.startsWith("$fieldA|") }, rendered)
    }

    @Test
    fun anUnattributableTypeIsJudgedByTheCoreVersion() {
        // No service in the module ⇒ owningServiceOf is null ⇒ the core slot, which
        // `currentVersionVector` fills with TENSHU_STORAGE_FORMAT_VERSION.
        val live = registry(shapeV1, withService = false)

        val line = SchemaShapeBaseline.render(live)
            .lineSequence()
            .single { it.startsWith("$pinnedId|") }

        assertEquals(
            "$pinnedId|${SchemaShapeBaseline.NO_SERVICE}|$TENSHU_STORAGE_FORMAT_VERSION",
            line.split("|", limit = 4).take(3).joinToString("|"),
        )
        assertEquals(emptyList(), baselineOf(live).check(live, expectExhaustive = true))
    }

    @Test
    fun theFingerprintSeparatesEveryPositionalHazard() {
        val base = composite(pinnedId, fields = { string(fieldA); int(fieldB) })

        // Reordered: identical ids, swapped positions — the case a set-based
        // comparison would call equal (the REORDERED_* fixture's whole point).
        assertNotEquals(
            base.shapeFingerprint(),
            composite(pinnedId, fields = { int(fieldB); string(fieldA) }).shapeFingerprint(),
        )
        // Optionality flipped: same ids, same order, different decode plan.
        assertNotEquals(
            base.shapeFingerprint(),
            composite(pinnedId, fields = { string(fieldA); int(fieldB, isOptional = true) })
                .shapeFingerprint(),
        )
        // Field type changed.
        assertNotEquals(
            base.shapeFingerprint(),
            composite(pinnedId, fields = { string(fieldA); string(fieldB) }).shapeFingerprint(),
        )
        // Field appended.
        assertNotEquals(
            base.shapeFingerprint(),
            composite(pinnedId, fields = { string(fieldA); int(fieldB); string("$pinnedId/c") })
                .shapeFingerprint(),
        )
        // And a re-declaration of the same shape is stable.
        assertEquals(
            base.shapeFingerprint(),
            composite(pinnedId, fields = { string(fieldA); int(fieldB) }).shapeFingerprint(),
        )
    }

    @Test
    fun theFingerprintAgreesWithTheChainCheckOnTheFixtureTypes() {
        // The baseline and SchemaMigrationRegistry.verify must judge a schema by the
        // same triple, or a reshape could pass one and fail the other.
        assertEquals(
            THING_AT_V1.structurallyEquals(THING_LIVE),
            THING_AT_V1.shapeFingerprint() == THING_LIVE.shapeFingerprint(),
        )
        assertEquals(
            REORDERED_AT_V1.structurallyEquals(REORDERED_LIVE),
            REORDERED_AT_V1.shapeFingerprint() == REORDERED_LIVE.shapeFingerprint(),
        )
    }

    @Test
    fun parseSkipsCommentsAndBlankLinesAndRejectsMalformedOnes() {
        val parsed = SchemaShapeBaseline.parse(
            """
            # regenerate with: ./gradlew …

            $pinnedId|$THING_SERVICE_ID|3|$fieldA@deliberately/wrong
            """.trimIndent()
        )
        val violations = parsed.check(registry(shapeV1, serviceVersion = 3))
        // The pinned shape is deliberately not the live one, so it must be reported —
        // proving parse read the shape column rather than ignoring it, and that the
        // header comment and blank line did not become entries.
        assertTrue(
            violations.any { it is SchemaShapeBaseline.Violation.ReshapedWithoutBump && it.typeId == pinnedId },
            violations.toString(),
        )

        assertFailsWith<IllegalArgumentException> {
            SchemaShapeBaseline.parse("$pinnedId|$THING_SERVICE_ID|3")
        }
        assertFailsWith<IllegalArgumentException> {
            SchemaShapeBaseline.parse("$pinnedId|$THING_SERVICE_ID|notAVersion|shape")
        }
    }

    @Test
    fun nothingToReportRendersNoFailureMessage() {
        assertNull(formatShapeBaselineFailure(emptyList(), regenerateHint = "hint"))
    }
}
