package org.tenshu.file.test

import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.storage.common.storageServiceCommonModule

fun createFileTestRegistry(): SchemaRegistry = createFileServiceCommonTestRegistry().apply {
    registerConfig(fileServiceClientModule)
    // ⚠ Mirrors production: `:file-service-proxy` reads `DeviceSetting` rows
    // directly (`ProxySyncRules.readFileSyncRuleSet` — the offline keeper's sync
    // policy, and the local-cache ceiling beside it). Without the preference
    // module registered here those rows cannot be created *or* read, so every
    // settings-driven keeper behaviour silently degrades to its default in tests
    // while working in the app.
    registerConfig(preferenceServiceCommonModule)
    // ⚠ Mirrors production: the upload/replace quota gate throws `InsufficientStorageError` /
    // `NoStorageError` from `:storage-service-common`. Without this module a refused write fails
    // with "Schema not found" instead of the typed error, so a gate test cannot tell a refusal
    // from a crash.
    registerConfig(storageServiceCommonModule)
}
