package org.tenshu.core.common.module

import org.tenshu.core.common.client.FileByteArray
import org.tenshu.core.common.client.InMemoryByteArray
import org.tenshu.core.common.util.idToUuid
import org.tenshu.files.util.ParcelFileDescriptorCache
import org.tenshu.files.util.SharedMemoryCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the two Android OOB byte-array carriers to [coreCommonAndroidModule].
 *
 * The regression this guards: while these registrations lived in `FileService.androidCommonModule` (now folded into `fileServiceCommonModule`),
 * every Android client that did not depend on the file service (Fit, Timeline, Maps, Home) declared
 * `platformSchemaConfigs { emptyList() }` and could not parse a byte-carrying response at all. Fit
 * hit it the moment `Workout.Streams` moved to the content plane — `SchemaRegistry.getTypeId` threw
 * `No type id registered for bytes: f3a3313d…` (= `tenshu/core/array/android/fileByteArray`), the
 * repository's catch mapped the throw to `null`, and every workout with a route rendered as
 * "Workout not found" with no map.
 */
class CoreCommonAndroidModuleTest {

    private val module = coreCommonAndroidModule(
        fileDescriptorCache = ParcelFileDescriptorCache(),
        sharedMemoryCache = SharedMemoryCache(),
    )

    @Test
    fun `registers the file-descriptor-backed byte array by its wire type id`() {
        assertEquals(FileByteArray.ID, module.typeIdOf(FileByteArray.ID))
    }

    @Test
    fun `registers the shared-memory byte array by its wire type id`() {
        assertEquals(InMemoryByteArray.ID, module.typeIdOf(InMemoryByteArray.ID))
    }

    @Test
    fun `both carriers carry a dto factory, so a parsed value can be materialised`() {
        assertTrue(module.valueFactories.keys.contains(FileByteArray.ID))
        assertTrue(module.valueFactories.keys.contains(InMemoryByteArray.ID))
    }

    /** Resolves a type id the way the parser does — hashed bytes in, id out. */
    private fun ModuleConfiguration.typeIdOf(id: String): String? =
        typeMapping.entries.find { it.key.contentEquals(idToUuid(id)) }?.value
}
