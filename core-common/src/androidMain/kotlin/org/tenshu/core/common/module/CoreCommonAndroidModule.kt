package org.tenshu.core.common.module

import org.tenshu.core.common.client.FileByteArray
import org.tenshu.core.common.client.InMemoryByteArray
import org.tenshu.core.common.client.ParcelFileDescriptorFileByteArrayDto
import org.tenshu.core.common.client.SharedMemoryByteArrayDto
import org.tenshu.files.util.ParcelFileDescriptorCache
import org.tenshu.files.util.SharedMemoryCache

/**
 * The Android out-of-band byte-array carriers, as schema registrations.
 *
 * Any byte-carrying value that crosses Binder is tokenized by `AndroidClientConnection` into a
 * file-descriptor-backed [FileByteArray] or a shared-memory [InMemoryByteArray] — never inline
 * bytes. A client that does not register these two types cannot *parse* what the proxy sends it:
 * the read dies with `No type id registered for bytes: …` from `SchemaRegistry.getTypeId`.
 *
 * This lived in `FileService.androidCommonModule` (now folded into `fileServiceCommonModule`) while Drive was the only app reading bytes, which
 * left every other Android client one byte-carrying property away from an unparseable response —
 * and Fit walked into exactly that when `Workout.Streams` moved to the content plane: its detail
 * read threw, the repository's catch turned the throw into `null`, and every routed workout
 * rendered as "Workout not found" with no route map. The carriers are `:core-common` types, so
 * their registration belongs here, where every Binder client can pick it up.
 *
 * A function, not a `val`: the dto factories capture the per-process caches (see
 * `FileService.androidCommonModule` (now folded into `fileServiceCommonModule`)'s KDoc for why a process-wide static was wrong).
 */
fun coreCommonAndroidModule(
    fileDescriptorCache: ParcelFileDescriptorCache,
    sharedMemoryCache: SharedMemoryCache,
): ModuleConfiguration = tModule {
    value(
        interfaceClass = FileByteArray::class,
        dataSchema = FileByteArray.Schema,
        dtoFactory = { rawValue ->
            ParcelFileDescriptorFileByteArrayDto(rawValue, fileDescriptorCache)
        }
    )

    value(
        interfaceClass = InMemoryByteArray::class,
        dataSchema = InMemoryByteArray.Schema,
        dtoFactory = { rawValue ->
            SharedMemoryByteArrayDto(rawValue, sharedMemoryCache)
        }
    )
}
