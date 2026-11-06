package org.tenshu.systemtest

import org.tenshu.calendar.client.calendarServiceClientModule
import org.tenshu.calendar.common.calendarServiceCommonModule
import org.tenshu.contact.client.contactServiceClientModule
import org.tenshu.contact.common.contactServiceCommonModule
import org.tenshu.core.Composite
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.DelegatingExternalPropertyWriter
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.collection.list.toList
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable
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
import org.tenshu.photo.client.photoServiceClientModule
import org.tenshu.photo.common.photoServiceCommonModule
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.routine.client.routineServiceClientModule
import org.tenshu.routine.common.routineServiceCommonModule
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ⭐ **The gate on the whole refcount** (`content-plane-refcount.md` §5, §7b.3 #1): every property
 * carrying `Syncable(SyncStrategy.OnDemand)` must have a writer registered in
 * `DelegatingExternalPropertyWriter`.
 *
 * ## Why this, and not "every operation that writes a contentId declares"
 *
 * ⚠⚠ Byte ownership is marked **per property by a schema trait**, not by an operation. An audit of
 * declaring operations would miss `Workout.Streams` entirely — Fit has no `contentId` field, its
 * content is entity-keyed — and would make every service know about reclamation. With declaration
 * at the emit (CR-4) there is no call an operation *can* fail to make; the only way a property's
 * bytes escape the edge set is by having no writer, which is exactly what this asserts.
 *
 * The writer is load-bearing in both directions: `resolveContentId` is what the emit declares
 * *with*, so a property whose bytes reach the content plane through no writer is content the relay
 * is never told about — and once something frees on that table (CR-5), untold is indistinguishable
 * from unreferenced.
 *
 * ## ⚠ Coverage
 *
 * The same service graph `SchemaShapeBaselineEnforcementTest` and `PropertyKindBaselineEnforcementTest`
 * judge, and the same caveat: `fit`/`timeline`/`logging`/`ingest`/`place`/`map-region`/`storage` are
 * not wired into this module, so their properties are not seen. A new service owes its `-common`
 * module to that list before this assertion covers it.
 *
 * The writer set mirrors `SystemTestPlatformModule` and the three production `PlatformProxyModule`s
 * — file bytes, workout stream blobs and a contact card's relocated photos. ⚠ Constructed by *id* rather than by instantiating the real
 * writers, because their constructors want a `FileDatabase` this test has no use for; `supports` is
 * the only method under assertion and it reads nothing else.
 */
class OnDemandWriterCompletenessTest {

    @Test
    fun everyOnDemandPropertyHasARegisteredWriter() {
        val onDemand = serviceGraphRegistry().onDemandPropertyIds()

        // ⚠ A guard on the guard: if the trait walk below stops finding anything — a schema-DSL
        // change, a registry that stopped exposing relations — the assertion would pass by
        // vacuum. `File.Data` is the oldest OnDemand property there is; its absence means the
        // walk broke, not that the codebase did.
        assertTrue(
            File.Data.id in onDemand,
            "The OnDemand walk found ${onDemand.size} properties and File.Data was not among " +
                "them — the walk is broken, not the codebase. Found: $onDemand",
        )

        val writer = registeredWriters()
        val unwritable = onDemand.filterNot { writer.supports(it) }.sorted()
        if (unwritable.isNotEmpty()) {
            fail(
                "OnDemand properties with no registered ExternalPropertyWriter:\n" +
                    unwritable.joinToString("\n") { "  - $it" } +
                    "\n\nEach one's bytes reach the content plane with nothing able to resolve " +
                    "their contentId, so the emit declares no edge for them " +
                    "(content-plane-refcount.md §5.2). Register a writer for the property, or " +
                    "drop the OnDemand trait if the bytes are not going to the content plane.",
            )
        }
    }

    /**
     * The registration under test — the same fan-out `SystemTestPlatformModule` and every
     * `PlatformProxyModule` build. ⚠ If a fourth service starts storing bytes off the entity, it
     * is added here *and* there; the pair going out of step is what this test then catches, one
     * property at a time.
     */
    private fun registeredWriters() = DelegatingExternalPropertyWriter(
        org.tenshu.file.proxy.FileBytesPropertyWriter(fileDatabase = InMemoryFileDatabase()),
        org.tenshu.health.proxy.WorkoutStreamsPropertyWriter(fileDatabase = InMemoryFileDatabase()),
        org.tenshu.contact.proxy.ContactPhotosPropertyWriter(fileDatabase = InMemoryFileDatabase()),
    )

    /**
     * Every registered property id whose own schema carries `Syncable(SyncStrategy.OnDemand)`.
     *
     * ⚠ Read straight off the schema's trait list rather than through an `ExecutionContext`'s
     * `TraitResolver`: this test has no runtime, and the DSL's `Syncable(...)` returns a
     * `TraitSchema` that is itself a [Composite] carrying the strategy — the same object the
     * resolver would hand back.
     */
    private fun SchemaRegistry.onDemandPropertyIds(): Set<String> =
        registeredRelations()
            .map { (_, propertyId) -> propertyId }
            .distinct()
            .filterTo(mutableSetOf()) { propertyId ->
                val schema = getSchema(propertyId) as? CompositeSchema ?: return@filterTo false
                schema.traits.toList().any { trait ->
                    (trait as? Composite)
                        ?.getOptionalField<SyncStrategy>(Syncable.Fields.STRATEGY) ==
                        SyncStrategy.OnDemand
                }
            }

    /** The same graph the two baseline enforcement tests judge — kept in step deliberately. */
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
