package org.tenshu.core.common.schema.migration

import org.tenshu.core.TByteArray
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.module.dynamicProperty
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.valueProperty
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.dynamicProperty
import org.tenshu.core.relation.valueProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The guard [PropertyKindBaseline] exists to be — driven through the transition it guards (a value
 * property becoming a dynamic one, which wedges sync on every device holding the old rows), so it
 * is **seen firing** rather than assumed to.
 *
 * ⚠ A detector exercised only on the clean case passes against an implementation that always
 * answers "fine". Every test here that matters asserts a *violation*; the clean case is the
 * control.
 */
class PropertyKindBaselineTest {

    private val propertyId = "test/synthetic/thumbnail"

    private val asValueProperty = valueProperty<TByteArray>(propertyId, TByteArray.Schema)

    private val asDynamicProperty: Property.Dynamic<TByteArray> =
        dynamicProperty<SyntheticTestData, TByteArray>(
            id = propertyId,
            receiver = SyntheticTestData.Schema,
        ) { TByteArrayDto(ByteArray(0)) }

    /** v1: the property is an eagerly-stored inline value. */
    private fun oldRegistry(): SchemaRegistry = createSyntheticTestRegistry().apply {
        registerConfig(
            tModule {
                valueProperty(fromId = SyntheticTestData.ID, property = asValueProperty) {
                    TByteArrayDto(it as ByteArray)
                }
            }
        )
    }

    /** v2: the same id is an on-demand dynamic property. */
    private fun newRegistry(): SchemaRegistry = createSyntheticTestRegistry().apply {
        registerConfig(
            tModule { dynamicProperty(fromId = SyntheticTestData.ID, property = asDynamicProperty) }
        )
    }

    @Test
    fun aValuePropertyBecomingDynamicIsRefused() {
        val pinned = PropertyKindBaseline.render(oldRegistry())
        assertTrue(
            pinned.lineSequence().any { it == "$propertyId|${SyntheticTestData.ID}|INLINE" },
            "the old build must pin the property as INLINE; got:\n$pinned",
        )

        val violations = PropertyKindBaseline.parse(pinned).check(newRegistry())

        val changed = violations
            .filterIsInstance<PropertyKindBaseline.Violation.KindChanged>()
            .firstOrNull { it.propertyId == propertyId }
        assertNotNull(changed, "the INLINE → CHILD_ROW flip must be reported; got $violations")
        assertEquals(PropertyKindBaseline.StorageKind.INLINE, changed.pinnedKind)
        assertEquals(PropertyKindBaseline.StorageKind.CHILD_ROW, changed.liveKind)
    }

    /** ⚠ Both directions: reverting a property to inline re-reads every child-row id as a value. */
    @Test
    fun aDynamicPropertyBecomingAValueIsRefusedToo() {
        val violations = PropertyKindBaseline
            .parse(PropertyKindBaseline.render(newRegistry()))
            .check(oldRegistry())

        val changed = violations
            .filterIsInstance<PropertyKindBaseline.Violation.KindChanged>()
            .firstOrNull { it.propertyId == propertyId }
        assertNotNull(changed, "the CHILD_ROW → INLINE flip must be reported too")
        assertEquals(PropertyKindBaseline.StorageKind.CHILD_ROW, changed.pinnedKind)
        assertEquals(PropertyKindBaseline.StorageKind.INLINE, changed.liveKind)
    }

    /** The control: an unchanged graph must be silent, or the guard is unusable. */
    @Test
    fun anUnchangedGraphReportsNothing() {
        assertEquals(
            emptyList(),
            PropertyKindBaseline
                .parse(PropertyKindBaseline.render(oldRegistry()))
                .check(oldRegistry(), expectExhaustive = true),
        )
    }

    @Test
    fun aNewPropertyIsRecordedNotBlocked() {
        val violations = PropertyKindBaseline
            .parse(PropertyKindBaseline.render(createSyntheticTestRegistry()))
            .check(oldRegistry())

        assertTrue(
            violations.filterIsInstance<PropertyKindBaseline.Violation.Unpinned>()
                .any { it.propertyId == propertyId },
            "an addition is an Unpinned to record, never a KindChanged: $violations",
        )
        assertTrue(violations.none { it is PropertyKindBaseline.Violation.KindChanged })
    }

    /**
     * ⚠ A removal is only judged when the caller says its registry is complete — a narrower
     * registry legitimately knows fewer properties, and reporting those as retired would make the
     * check unusable from any partial graph.
     */
    @Test
    fun aRemovedPropertyIsReportedOnlyWhenExhaustivenessIsClaimed() {
        val pinned = PropertyKindBaseline.parse(PropertyKindBaseline.render(oldRegistry()))
        val narrower = createSyntheticTestRegistry()

        assertTrue(pinned.check(narrower).none { it is PropertyKindBaseline.Violation.Retired })
        assertTrue(
            pinned.check(narrower, expectExhaustive = true)
                .filterIsInstance<PropertyKindBaseline.Violation.Retired>()
                .any { it.propertyId == propertyId },
        )
    }

    @Test
    fun theFailureMessageNamesThePropertyAndBothKinds() {
        val message = formatPropertyKindBaselineFailure(
            violations = PropertyKindBaseline
                .parse(PropertyKindBaseline.render(oldRegistry()))
                .check(newRegistry()),
            regenerateHint = "REGEN",
        )
        assertNotNull(message)
        assertTrue(propertyId in message, "must name the property: $message")
        assertTrue("INLINE" in message && "CHILD_ROW" in message, "must name both kinds: $message")
        assertTrue("migration" in message, "must say what is owed: $message")
    }

    /** The pinned format has to survive the round trip, or a regenerated file silently under-reports. */
    @Test
    fun parseIsTheInverseOfRender() {
        val rendered = PropertyKindBaseline.render(oldRegistry())
        assertEquals(
            emptyList(),
            PropertyKindBaseline.parse(rendered).check(oldRegistry(), expectExhaustive = true),
        )
    }
}
