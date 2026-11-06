package org.tenshu.systemtest

import org.tenshu.calendar.client.calendarServiceClientModule
import org.tenshu.calendar.common.calendarServiceCommonModule
import org.tenshu.photo.client.photoServiceClientModule
import org.tenshu.photo.common.photoServiceCommonModule
import org.tenshu.contact.client.contactServiceClientModule
import org.tenshu.contact.common.contactServiceCommonModule
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
import org.tenshu.mia.client.miaServiceClientModule
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.mia.common.miaServiceCommonModule
import org.tenshu.notifications.client.notificationServiceClientModule
import org.tenshu.notifications.common.notificationServiceCommonModule
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.routine.client.routineServiceClientModule
import org.tenshu.routine.common.routineServiceCommonModule
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.timeline.client.timelineServiceClientModule
import org.tenshu.logging.common.loggingServiceCommonModule
import org.tenshu.logging.client.loggingServiceClientModule
import org.tenshu.ingest.common.ingestServiceCommonModule
import org.tenshu.ingest.client.ingestServiceClientModule
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.mapregion.common.mapRegionServiceCommonModule
import org.tenshu.mapregion.client.mapRegionServiceClientModule
import org.tenshu.storage.common.storageServiceCommonModule
import org.tenshu.storage.client.storageServiceClientModule

/**
 * The service graph this module wires, as a list — shared by every test that needs to judge
 * "everything registered" rather than one module's worth.
 *
 * ⚠ It is deliberately the same list `systemTestProxyModule` registers as `coreSchemaConfigs`,
 * and **coverage is that graph, not the whole repo**: `fit`/`timeline`/`logging`/`ingest`/
 * `place`/`map-region`/`storage` are absent, so their types are judged by nothing here.
 * Widening it means adding those `*-common` modules to this module's dependencies.
 *
 * Client modules are included on purpose: op parameter objects are client-serialized
 * composites too, and a client module can *shadow* a common module's registration (the later
 * config wins), which is exactly the failure `TypedPredicateReceiverTest` exists to catch.
 */
internal fun serviceGraphConfigs(): List<ModuleConfiguration> = listOf(
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
            inferenceServiceCommonModule,
            inferenceServiceClientModule,
            miaServiceCommonModule,
            miaServiceClientModule,
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
            // CA-1's operation surface. ⚠ Op parameter objects are
            // client-serialized composites too, so reshaping one breaks old
            // *callers* mid-parse and owes a version bump exactly as a stored
            // row does — which is why they belong in the baseline.
            calendarServiceClientModule,
            photoServiceCommonModule,
            photoServiceClientModule,
            // ── The six services this graph used to omit ────────────────────────────────
            // Each as a `-common` + `-client` PAIR: element request objects live only in
            // `-client`, so a common-only entry makes every element type of that service
            // look unregistered (the false positive `:inference-service` produced).
            timelineServiceCommonModule,
            timelineServiceClientModule,
            loggingServiceCommonModule,
            loggingServiceClientModule,
            ingestServiceCommonModule,
            ingestServiceClientModule,
            placeServiceCommonModule,
            placeServiceClientModule,
            mapRegionServiceCommonModule,
            mapRegionServiceClientModule,
            storageServiceCommonModule,
            storageServiceClientModule,
)
