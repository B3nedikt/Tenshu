package org.tenshu.file.proxy

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.contains
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.files
import org.tenshu.core.file.name
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.fold
import org.tenshu.core.get
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guard rail for `RecursiveListWalk`'s **skeleton projection**.
 *
 * The walk re-loads each element to reach its `TList` fields so it can descend. That read is
 * projected (`SKELETON_PROJECTION`) so it stops materialising every node's relation map — which is
 * where `File.Thumbnail` / `File.ThumbnailLarge` blobs live, and was the bulk of a Drive search's
 * cost (the `name contains` predicate itself is ~1% of it).
 *
 * The skeleton element is never emitted, so this *should* be invisible. This test pins that: it
 * runs the exact shape of `TenshuFilesRepository.searchFiles` — `traverse().filter { name
 * contains q }.select { relations(…) }` — and asserts a **descendant reached through the
 * recursion** still resolves its relations to real values. If the projection ever leaked onto an
 * emitted row (or onto the child `resolveList`, which feeds rows a no-`select` consumer like
 * `tenshu-sdk-js`'s `CompositeSnapshot` reads `properties` off directly), relations would come back
 * null and this fails.
 *
 * Nothing covered this before: `TraverseOperationTest` asserts only `File.Field.PATH`, a scalar
 * *field*, which no projection can strip — so the relation path was unguarded.
 */
class TraverseRelationResolutionTest {

    private fun fileService(proxy: TestProxy): FileService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return env.getService(FileService.ID)
    }

    @Test
    fun traverseFilterSelect_resolvesRelationsOnADescendantReachedByRecursion() = runTest {
        val fileService = fileService(createFileProxy(backgroundScope))
        val fs = fileService.defaultFileSystem

        // Two levels down, so the hit is only reachable *through* the recursive walk —
        // not from the root array the caller already materialised.
        fs.createFolder("/dir")
        fs.createFolder("/dir/sub")
        fs.uploadFile("/dir/sub", "needle.txt", byteArrayOf(1, 2, 3, 4, 5))
        fs.uploadFile("/", "unrelated.txt", byteArrayOf(9))

        // Exactly `TenshuFilesRepository.searchFiles`' chain + terminal.
        val hits = fs.root.files
            .traverse()
            .filter<File> { name.contains("needle", ignoreCase = true) }
            .select {
                // `fields(PATH)` is not optional: the streaming terminal derives each row's
                // DataId from the path, so omitting it fails with
                // "Field tenshu/file/path not found in caller". Production's
                // `searchFiles` projects it for the same reason.
                fields(File.Field.PATH)
                relations(File.CreatedAt, File.LastModifiedAt)
            }
            .flow()
            .first()
            .fold(
                onSuccess = { it.toList() },
                onFailure = { error("traverse+filter+select failed on proxy: $it") },
            )

        assertEquals(1, hits.size, "only the nested needle.txt should match")

        @Suppress("UNCHECKED_CAST")
        val hit = hits.single() as Entity<File>

        assertNotNull(
            hit.get(File.CreatedAt)?.get(),
            "a descendant reached through the walk must still resolve File.CreatedAt — " +
                "if this is null the skeleton projection leaked onto an emitted row"
        )
        assertTrue(
            (hit.get(File.LastModifiedAt)?.get() ?: 0L) > 0L,
            "relations must resolve to real values, not defaults"
        )
    }
}
