package org.tenshu.systemtest

import org.tenshu.calendar.client.calendarServiceClientModule
import org.tenshu.calendar.common.calendarServiceCommonModule
import org.tenshu.photo.client.photoServiceClientModule
import org.tenshu.photo.common.photoServiceCommonModule
import org.tenshu.contact.client.contactServiceClientModule
import org.tenshu.contact.common.contactServiceCommonModule
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.schema.migration.SchemaShapeBaseline
import org.tenshu.core.common.schema.migration.formatShapeBaselineFailure
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.home.device.client.homeDeviceServiceClientModule
import org.tenshu.home.device.common.homeDeviceServiceCommonModule
import org.tenshu.mia.client.miaServiceClientModule
import org.tenshu.mia.common.miaServiceCommonModule
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
 * Enforces the E2j-1 shape baseline (`core-schema-evolution.md` §8.9) across the
 * service graph: **a composite whose positional shape changed must have seen a
 * service-version bump.**
 *
 * Composites serialize positionally (§2 fact 1), so a field added, removed,
 * reordered or required/optional-flipped changes how every older reader decodes
 * everything after it. Today House Rule #9's format bump absorbs that with a
 * store wipe; **E2j-2 retires the wipe, and then this check is the only thing
 * between a careless reshape and silent corruption.** It is therefore worth
 * having *before* the flip, not with it.
 *
 * ## Why this lives here
 *
 * The check needs the widest registry available, and this module already wires
 * the service graph for the SystemTests. ⚠ **Coverage is that graph, not the
 * whole repo:** `fit`/`timeline`/`logging`/`ingest`/`place`/`map-region`/
 * `storage` schemas are not registered here, so they are not pinned. Widening it
 * means adding those `*-common` modules to this module's dependencies — a build
 * change, deliberately not bundled into this slice. Everything registered below
 * *is* judged exhaustively, including removals.
 *
 * ## Regenerating
 *
 * ```
 * ./gradlew :system-test-utils:test --tests '*SchemaShapeBaselineEnforcementTest*' \
 *     -Dtenshu.schemaBaseline.regenerate=true
 * ```
 *
 * That rewrites the pinned file and **fails on purpose** — a regeneration must
 * never be silently green, because the diff is the review artifact. Read it: for
 * a positional format, "which field moved to which position" is exactly the
 * question a reviewer needs answered.
 */
class SchemaShapeBaselineEnforcementTest {

    private val baselineFile = File("src/test/resources/schema-shape-baseline.txt")

    private val regenerateCommand =
        "./gradlew :system-test-utils:test --tests '*SchemaShapeBaselineEnforcementTest*' " +
            "-Dtenshu.schemaBaseline.regenerate=true"

    @Test
    fun noCompositeChangedShapeWithoutSeeingAVersionBump() {
        val live = serviceGraphRegistry()
        val pinnedText = baselineFile.takeIf { it.exists() }?.readText()

        if (pinnedText == null || System.getProperty("tenshu.schemaBaseline.regenerate") == "true") {
            baselineFile.parentFile.mkdirs()
            baselineFile.writeText(fileHeader + SchemaShapeBaseline.render(live) + "\n")
            fail(
                "Shape baseline written to ${baselineFile.path} (${
                    baselineFile.readLines().count { it.isNotBlank() && !it.startsWith("#") }
                } types). Review the diff — for a positional format the moved field IS the " +
                    "review signal — then re-run without the regenerate flag."
            )
        }

        val violations = SchemaShapeBaseline
            .parse(pinnedText)
            .check(live, expectExhaustive = true)

        formatShapeBaselineFailure(
            violations = violations,
            regenerateHint = "Once every change above is accounted for, regenerate:\n  $regenerateCommand",
        )?.let { fail(it) }
    }

    private val fileHeader = """
        # Pinned positional shape of every composite in the service graph this module wires.
        # Generated — do not hand-edit. See SchemaShapeBaselineEnforcementTest and
        # docs/proposals/core-schema-evolution.md §8.9 (E2j-1).
        #
        # Format: typeId|serviceId|serviceVersion|field@type[?],…   ('?' = optional, '-' = no service)
        # A changed shape here must come with a service-version bump and a SchemaMigration step.
        #
        # Regenerate with:
        #   $regenerateCommand

    """.trimIndent() + "\n"

    /**
     * The same module list [org.tenshu.systemtest.di.systemTestProxyModule] registers as
     * `coreSchemaConfigs`, over the core registry. Client modules are included
     * deliberately: op parameter objects are client-serialized composites too
     * (§2 fact 3), so reshaping one breaks old *callers* mid-parse exactly like a
     * stored row breaks old readers.
     */
    private fun serviceGraphRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
        serviceGraphConfigs().forEach { registerConfig(it) }
    }
}
