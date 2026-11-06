package app.tenshu.control.bridge

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JSON-shaping tests for the Phase 2 file-read bridge methods. The
 * read+map logic (the chain DSL against a real file-service) is exercised
 * end-to-end over a real UDS in drive-shared's `FileBridgeRoundTripTest`, where
 * an in-process `TenshuClient` is available.
 */
class ControlFileBridgeTest {

    private val file = BridgeFileItem(
        id = "id-cat",
        parentId = "id-photos",
        name = "cat.jpg",
        isFolder = false,
        size = 1234L,
        createdAtMillis = 100L,
        modifiedAtMillis = 200L,
        category = "image",
        contentVersion = "hash-1",
        path = "/Photos/cat.jpg",
    )

    private val folder = BridgeFileItem(
        id = "id-photos",
        parentId = FileProviderIds.ROOT_ID,
        name = "Photos",
        isFolder = true,
        size = 0L,
        createdAtMillis = 50L,
        modifiedAtMillis = null,
        category = null,
        contentVersion = "",
        path = "/Photos",
    )

    @Test
    fun fileItemMapCarriesAllFields() {
        val map = fileItemMap(file)
        assertEquals("id-cat", map["id"])
        assertEquals("id-photos", map["parentId"])
        assertEquals("hash-1", map["contentVersion"])
        // The realm path is the daemon's business; the extension addresses items by id only.
        assertTrue("path" !in map)
        assertEquals("cat.jpg", map["name"])
        assertEquals(false, map["isFolder"])
        assertEquals(1234L, map["size"])
        assertEquals(100L, map["created"])
        assertEquals(200L, map["modified"])
        assertEquals("image", map["category"])
    }

    @Test
    fun fileItemMapKeepsNullsForFolderTimestampsAndCategory() {
        val map = fileItemMap(folder)
        assertEquals(true, map["isFolder"])
        assertEquals(0L, map["size"])
        assertNull(map["modified"])
        assertNull(map["category"])
    }

    @Test
    fun folderResultWrapsEachChild() {
        @Suppress("UNCHECKED_CAST")
        val rows = folderResult(listOf(folder, file))["items"] as List<Map<String, Any?>>
        assertEquals(2, rows.size)
        assertEquals(listOf("Photos", "cat.jpg"), rows.map { it["name"] })
        assertEquals(listOf(true, false), rows.map { it["isFolder"] })
    }

    @Test
    fun metadataResultNestsSingleItem() {
        @Suppress("UNCHECKED_CAST")
        val item = metadataResult(file)["item"] as Map<String, Any?>
        assertEquals("cat.jpg", item["name"])
        assertEquals(1234L, item["size"])
    }

    @Test
    fun changesResultResolvesUpdatedIdsToItems_andPassesDeletedIdsThrough() {
        val changes = FileProviderChangeFeed.Changes("e:2", updated = setOf("id-cat"), deleted = setOf("id-gone"))
        val result = changesResult(changes, listOf(folder, file))
        assertEquals(false, result["expired"])
        assertEquals("e:2", result["anchor"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("cat.jpg"), (result["updated"] as List<Map<String, Any?>>).map { it["name"] })
        assertEquals(listOf("id-gone"), result["deleted"])
    }

    @Test
    fun anUnanswerableAnchorIsReportedExpired_withNothingElse() {
        assertEquals(mapOf<String, Any?>("expired" to true), changesResult(null, listOf(file)))
    }

    @Test
    fun aVersionChangesWithAnythingFinderCanSee() {
        val base = file.version()
        assertTrue(file.copy(name = "dog.jpg").version() != base)
        assertTrue(file.copy(parentId = "elsewhere").version() != base)
        assertTrue(file.copy(contentVersion = "hash-2").version() != base)
        assertTrue(file.copy(createdAtMillis = 99L).version() != base)
        assertTrue(file.copy(modifiedAtMillis = 201L).version() != base)
        // …and not with the path alone, which the id map already accounts for.
        assertEquals(base, file.copy(path = "/Moved/cat.jpg").version())
    }

    @Test
    fun contentsResultBase64EncodesBytes() {
        val bytes = byteArrayOf(0, 1, 2, 3, 65, 66, 67)
        val result = contentsResult(bytes)
        assertEquals("base64", result["encoding"])
        assertEquals(bytes.size, result["size"])
        val decoded = Base64.getDecoder().decode(result["data"] as String)
        assertTrue(bytes.contentEquals(decoded), "round-tripped bytes differ")
    }

    @Test
    fun thumbnailResultCarriesBytesOrSaysThereIsNone() {
        val bytes = byteArrayOf(-1, -40, -1, 0, 7)
        val found = thumbnailResult(bytes)
        assertEquals(true, found["available"])
        assertEquals(bytes.size, found["size"])
        assertTrue(bytes.contentEquals(Base64.getDecoder().decode(found["data"] as String)))

        // No thumbnail is an answer with no payload — not an error, and not empty bytes.
        assertEquals(mapOf<String, Any?>("available" to false), thumbnailResult(null))
    }

    @Test
    fun bestStoredClassIsTheSmallestThatCoversElseWhicheverExists() {
        val list = byteArrayOf(1)
        val large = byteArrayOf(2)
        assertEquals(list, bestStoredClass(100, list, large))
        assertEquals(large, bestStoredClass(101, list, large))
        // Too small or too big still beats nothing.
        assertEquals(list, bestStoredClass(512, list, null))
        assertEquals(large, bestStoredClass(32, null, large))
        assertEquals(null, bestStoredClass(256, null, null))
    }
}
