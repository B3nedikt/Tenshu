package org.tenshu.systemtest

import org.tenshu.calendar.client.calendarServiceClientModule
import org.tenshu.calendar.common.calendarServiceCommonModule
import org.tenshu.photo.client.photoServiceClientModule
import org.tenshu.photo.common.photoServiceCommonModule
import org.tenshu.contact.client.contactServiceClientModule
import org.tenshu.contact.common.contactServiceCommonModule
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.schema.migration.PropertyKindBaseline
import org.tenshu.core.common.schema.migration.formatPropertyKindBaselineFailure
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.home.device.client.homeDeviceServiceClientModule
import org.tenshu.home.device.common.homeDeviceServiceCommonModule
import org.tenshu.hub.management.client.hubManagementServiceClientModule
import org.tenshu.hub.management.common.hubManagementServiceCommonModule
import org.tenshu.identity.client.identityServiceClientModule
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.notifications.client.notificationServiceClientModule
import org.tenshu.notifications.common.notificationServiceCommonModule
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.routine.client.routineServiceClientModule
import org.tenshu.routine.common.routineServiceCommonModule
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Enforces the **property-kind baseline**: a property id must not change how its value is stored.
 *
 * The sibling of `SchemaShapeBaselineEnforcementTest`, and it exists because that one structurally
 * cannot see this: it pins *positional* composite layout, while a property relation is stored in a
 * **named map**. Moving a property from `valueProperty` to `dynamicProperty` leaves the shape
 * baseline honestly green ("0 changed") — and every device that already holds rows of it reads their
 * inline bytes as a child-row id, throws `ClassCastException` on the ingest path, and **wedges sync
 * permanently**.
 *
 * ⚠⚠ The reader cannot be taught to catch it. It closes the arm it can — a stored kind that
 * *cannot* be a child-row reference is dropped — but on a `TInteger` property the stale slot **is**
 * a valid `LongValue`, so the stored value is dereferenced as a row id and a collision returns
 * another row's data with no error anywhere. A reader can reject a kind mismatch, never a kind
 * match that means something else. The check has to see both versions, which only a pinned
 * baseline does.
 *
 * ## Coverage
 *
 * ⚠ Its own list ([serviceGraphRegistry]), narrower than the shape baseline's
 * `serviceGraphConfigs()`: `timeline`/`logging`/`ingest`/`place`/`map-region`/`storage`/
 * `inference`/`mia` are not in it (Fit's rows are `:health-service`, which is), so their properties
 * are not pinned. Everything registered below
 * *is* judged exhaustively, including removals.
 *
 * ## Regenerating
 *
 * ```
 * ./gradlew :system-test-utils:test --tests '*PropertyKindBaselineEnforcementTest*' \
 *     -Dtenshu.propertyKindBaseline.regenerate=true
 * ```
 *
 * Rewrites the pinned file and **fails on purpose** — the diff is the review artifact, exactly as
 * for its sibling.
 */
class PropertyKindBaselineEnforcementTest {

    private val baselineFile = File("src/test/resources/property-kind-baseline.txt")

    private val regenerateCommand =
        "./gradlew :system-test-utils:test --tests '*PropertyKindBaselineEnforcementTest*' " +
            "-Dtenshu.propertyKindBaseline.regenerate=true"

    @Test
    fun noPropertyChangedHowItsValueIsStored() {
        val live = serviceGraphRegistry()
        val pinnedText = baselineFile.takeIf { it.exists() }?.readText()

        if (pinnedText == null ||
            System.getProperty("tenshu.propertyKindBaseline.regenerate") == "true"
        ) {
            baselineFile.parentFile.mkdirs()
            baselineFile.writeText(fileHeader + PropertyKindBaseline.render(live) + "\n")
            fail(
                "Property-kind baseline written to ${baselineFile.path} (${
                    baselineFile.readLines().count { it.isNotBlank() && !it.startsWith("#") }
                } properties). Review the diff — an INLINE↔CHILD_ROW flip is the signal — then " +
                    "re-run without the regenerate flag."
            )
        }

        val violations = PropertyKindBaseline
            .parse(pinnedText)
            .check(live, expectExhaustive = true)

        formatPropertyKindBaselineFailure(
            violations = violations,
            regenerateHint = "Once every change above is accounted for, regenerate:\n  $regenerateCommand",
        )?.let { fail(it) }
    }

    private val fileHeader = """
        # Pinned storage kind of every property AND service relation in the graph this module
        # wires — both are slots addressed by id in a row's named map, so both take the same
        # DbEntryMapper read arm and both can be flipped.
        # Generated — do not hand-edit. See PropertyKindBaselineEnforcementTest and
        # docs/proposals/core-schema-evolution.md §8.9.
        #
        # Format: propertyId|receiverTypeId|INLINE|CHILD_ROW
        #   INLINE    = valueProperty  -> a ValueSchema  -> the value sits in the row
        #   CHILD_ROW = dynamicProperty -> a RelationSchema -> the slot is a child-row id
        #
        # A flip between them re-reads every stored slot through the other arm. It is allowed,
        # but it owes a migration that strips the stale slots, shipped with the version bump.
        #
        # Regenerate with:
        #   $regenerateCommand

    """.trimIndent() + "\n"

    /** The graph this baseline judges — narrower than `serviceGraphConfigs()`, see Coverage above. */
    private fun serviceGraphRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
        listOf(
            fileServiceCommonModule,
            fileServiceClientModule,
            healthServiceCommonModule,
            healthServiceClientModule,
            notificationServiceCommonModule,
            notificationServiceClientModule,
            preferenceServiceCommonModule,
            preferenceServiceClientModule,
            hubManagementServiceCommonModule,
            hubManagementServiceClientModule,
            homeDeviceServiceCommonModule,
            homeDeviceServiceClientModule,
            routineServiceCommonModule,
            routineServiceClientModule,
            contactServiceCommonModule,
            contactServiceClientModule,
            identityServiceCommonModule,
            identityServiceClientModule,
            syncServiceCommonModule,
            syncServiceClientModule,
            calendarServiceCommonModule,
            calendarServiceClientModule,
            photoServiceCommonModule,
            photoServiceClientModule,
        ).forEach { registerConfig(it) }
    }
}
