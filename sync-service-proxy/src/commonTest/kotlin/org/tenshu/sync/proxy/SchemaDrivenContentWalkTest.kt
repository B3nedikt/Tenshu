package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.Data
import org.tenshu.core.health.ContentId
import org.tenshu.core.file.sync.DeclarableContent
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.file.common.fileServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `content-plane-refcount.md` §9.6: the backfill's enumeration is driven by the schema, so
 * *declared ⇒ walkable* holds by construction rather than by a hand-maintained list.
 *
 * ⚠⚠ Two properties here are the ones that lose data if they are wrong, and both are the same
 * mistake in different clothes: **reading "I could not look" as "there is nothing"**. The walk must
 * report incomplete when the store cannot enumerate and when the registry is unreachable, because
 * the backfill latches a once-only marker on a complete walk.
 */
class SchemaDrivenContentWalkTest {

    /** `Workout` — a real type, used as the one a store cannot enumerate. */
    private val WORKOUT_TYPE_ID = "tenshu/health/workout"

    /**
     * Saves a `File` row naming [contentId], or none. ⚠ The walk reads a row's content
     * from the schema's declaration (`File.ContentId` + each property's `ContentDerivation`), not from
     * the port, so a row with no content id declares nothing however the port is stubbed.
     */
    private fun TestProxy.saveFile(path: String, contentId: String? = "cid-$path") {
        val composite = org.tenshu.core.file.File.create(tContext, path)
        executionContext.saveData(
            if (contentId == null) composite
            else Entity.create<org.tenshu.core.file.File>(context = tContext, composite = composite) {
                set(org.tenshu.core.file.File.ContentId, contentId)
            },
        )
    }

    /**
     * A store that enumerates everything **except** one type: with `Workout` not enumerable, a walk
     * that dropped to the Drive tree would declare files only and report itself complete.
     */
    private class BlocksOneTypeStore(
        private val delegate: ObjectStore,
        private val blockedTypeId: String,
    ) : ObjectStore by delegate {
        override fun rowsOfTypes(typeIds: Set<String>): List<ObjectStore.DbEntry>? =
            if (typeIds.any { it == blockedTypeId }) null else delegate.rowsOfTypes(typeIds)

        override fun scanRowsOfType(
            typeId: String,
            afterHash: Long,
            matchLimit: Int,
            examineLimit: Int,
        ): ObjectStore.TypeScanPage? =
            if (typeId == blockedTypeId) null
            else delegate.scanRowsOfType(typeId, afterHash, matchLimit, examineLimit)
    }

    /** A store that refuses to enumerate — the JS `IndexedDbObjectStore` posture. */
    private class UnenumerableStore(private val delegate: ObjectStore) : ObjectStore by delegate {
        override fun rowsOfTypes(typeIds: Set<String>): List<ObjectStore.DbEntry>? = null
        override fun scanRowsOfType(
            typeId: String,
            afterHash: Long,
            matchLimit: Int,
            examineLimit: Int,
        ): ObjectStore.TypeScanPage? = null
    }

    private class StubWriter(private val contentIdFor: (String) -> String?) : ExternalPropertyWriter {
        override fun supports(propertyId: String) = true
        override fun write(
            executionContext: ExecutionContext,
            targetEntityId: DataId,
            propertyId: String,
            value: Data,
        ) = Unit

        override fun resolveContentId(
            executionContext: ExecutionContext,
            targetEntityId: DataId,
            propertyId: String,
        ): String? = contentIdFor(propertyId)
    }

    /**
     * ⚠ A registry that actually declares. With none, the walk answers "nothing declares" and
     * returns **before touching the store** — correct, and it would make the two store tests below
     * pass without exercising anything. `fileServiceCommonModule` brings `File.Data` and the four
     * thumbnail classes, the only `OnDemand` properties that module registers.
     */
    private fun declaringProxy(
        store: ObjectStore = InMemoryObjectStore(),
        writer: ExternalPropertyWriter? = null,
    ): TestProxy = TestProxy(
        schemaRegistry = createSyntheticTestRegistry().apply {
            registerConfig(fileServiceCommonModule)
            registerConfig(healthServiceCommonModule)
        },
        additionalComponents = writer?.let { mapOf(ExternalPropertyWriter.ID to it) } ?: emptyMap(),
        objectStore = store,
    )

    private fun walk(
        proxy: TestProxy,
        store: ObjectStore,
        fallback: (suspend () -> DeclarableContent.Result)? = null,
    ): SchemaDrivenContentWalk {
        val registry = proxy.executionContext.schemaProvider as SchemaRegistry
        return SchemaDrivenContentWalk(
            executionContext = proxy.executionContext,
            objectStore = store,
            mapper = DbEntryMapper(
                proxy.tContext,
                registry,
                registry,
                proxy.executionContext.traitResolver,
            ),
            fallback = fallback,
        )
    }

    @Test
    fun aStoreThatCannotEnumerateFallsBackRatherThanReportingEmpty() = runTest {
        // ⚠⚠ `null` from the store is "do not ask me", never "no rows". Reading it as empty would
        // let the backfill latch its marker over a realm it never examined — and the fallback is
        // the tree walk, which works on any store.
        // ⚠⚠ A writer, or this never reaches the store: the walk checks the port FIRST, so without
        // one it falls back for that reason and the test passes having exercised nothing — the
        // mutation that reads "cannot enumerate" as empty would survive it.
        val proxy = declaringProxy(writer = StubWriter { "cid-it" })
        var fellBack = false
        val result = walk(
            proxy = proxy,
            store = UnenumerableStore(InMemoryObjectStore()),
            fallback = {
                fellBack = true
                DeclarableContent.Result(
                    entries = listOf(
                        DeclarableContent.Entry(DataId.StringDataId.create(proxy.tContext, "tenshu/test/holder", "h"), "p", "c"),
                    ),
                    walkComplete = true,
                )
            },
        ).enumerate()

        assertTrue(fellBack, "an unenumerable store must reach the fallback walk")
        assertEquals(1, result.entries.size)
        // ⚠⚠ The stub fallback above claims `walkComplete = true` and the walk must NOT pass that
        // through. The fallback is the Drive tree walk, narrower than the declarable set by
        // construction, so however cleanly it ran it has not seen everything. Letting its own
        // answer through would let the backfill latch a once-only marker over types it never
        // asked about — every file edge and no health one.
        assertFalse(result.walkComplete, "a fallback is never a complete walk, whatever it reports")
    }

    /**
     * ⚠⚠ **One awkward type must not cost every other type.** Falling back on the first type the
     * store cannot enumerate would discard every entry already collected and drop to the Drive tree
     * — so a realm with a `Workout` would declare its files and nothing else, and say it was done.
     * The undeclared content then reads as orphaned to anything that counts.
     */
    @Test
    fun oneUnenumerableTypeIsSkipped_theOthersStillDeclare_andTheWalkIsIncomplete() = runTest {
        val backing = InMemoryObjectStore()
        val store = BlocksOneTypeStore(backing, blockedTypeId = WORKOUT_TYPE_ID)
        val proxy = declaringProxy(store, StubWriter { propertyId -> "cid-" + propertyId })
        proxy.saveFile("/photo.jpg")

        val result = walk(proxy, store, fallback = null).enumerate()

        assertTrue(
            result.entries.any { it.propertyId.startsWith("tenshu/file/") },
            "the enumerable type must still be declared; got ${result.entries}",
        )
        assertFalse(
            result.walkComplete,
            "a type that could not be asked means the walk did not see everything",
        )
    }

    /**
     * §9.6's invariant — *declared ⇒ walkable* — across **more than one declaring service**. Every
     * other test here uses a single one, so none of them can tell "walks the graph" from "walks
     * Drive"; this one holds the registry to both.
     *
     * ⚠⚠ **It does NOT guard the store-capability failure** — a type the store cannot enumerate
     * collapsing the whole walk to the Drive tree. `InMemoryObjectStore` enumerates everything, so
     * that branch never runs here and this test passes against broken code too.
     * [oneUnenumerableTypeIsSkipped_theOthersStillDeclare_andTheWalkIsIncomplete] is the guard for
     * that path, and it is mutation-checked.
     *
     * ⇒ What this one is for: a regression that narrows the walk *by construction* — a hardcoded
     * type list, a filter, a registry that stops reporting a service — none of which the
     * capability test would notice. The production symptom to recognise is the same either way:
     * all declared edges naming **one** service.
     */
    @Test
    fun aRegistryWithTwoDeclaringServicesWalksBoth_notJustTheFirst() = runTest {
        val store = InMemoryObjectStore()
        val proxy = declaringProxy(store, StubWriter { propertyId -> "cid-" + propertyId })
        proxy.saveFile("/photo.jpg")
        proxy.executionContext.saveData(
            Entity.create<org.tenshu.core.health.Workout>(
                context = proxy.tContext,
                composite = org.tenshu.core.health.Workout.create(
                    context = proxy.tContext,
                    workoutId = "w-1",
                    activityType = "run",
                    source = "test",
                    start = org.tenshu.core.common.impl.UnixInstantDto(1_000L),
                    end = org.tenshu.core.common.impl.UnixInstantDto(2_000L),
                    durationSec = 1_000L,
                ),
            ) { set(org.tenshu.core.health.Workout.ContentId, "cid-workout") },
        )

        val result = walk(proxy, store, fallback = null).enumerate()

        val services = result.entries.map { it.propertyId.substringBeforeLast('/') }.distinct()
        assertTrue(
            result.entries.any { it.propertyId.startsWith("tenshu/file/") },
            "Drive content must be declared; got ${result.entries.map { it.propertyId }}",
        )
        assertTrue(
            result.entries.any { it.propertyId.startsWith("tenshu/health/") },
            "Fit content must be declared too, or the walk is Drive's tree by another name; got " +
                "${result.entries.map { it.propertyId }}",
        )
        assertTrue(
            services.size >= 2,
            "the walk must name more than one service, which is the one-line check for this whole " +
                "class of defect; got $services",
        )
        assertTrue(result.walkComplete, "both types enumerated, so the walk saw everything")
    }

    /**
     * ⚠ The counterpart: when nothing is blocked the walk is complete, or the fix above would make
     * the marker unlatchable on every device for ever — the failure `ContentDeclarationBackfill`'s
     * own KDoc warns about.
     */
    @Test
    fun withEveryTypeEnumerableTheWalkIsStillComplete() = runTest {
        val store = InMemoryObjectStore()
        val proxy = declaringProxy(store, StubWriter { propertyId -> "cid-" + propertyId })
        proxy.saveFile("/photo.jpg")

        val result = walk(proxy, store, fallback = null).enumerate()

        assertTrue(result.walkComplete, "nothing was refused, so the walk saw everything")
        assertTrue(result.entries.isNotEmpty())
    }

    @Test
    fun withNoFallbackAnUnenumerableStoreIsIncomplete_neverAnEmptyComplete() = runTest {
        // ⚠⚠ The failure this whole shape exists to prevent: `walkComplete = true` with no entries
        // is what tells the backfill "this realm holds no content, record yourself done".
        val proxy = declaringProxy(writer = StubWriter { "cid-it" })
        val result = walk(proxy, UnenumerableStore(InMemoryObjectStore()), fallback = null).enumerate()

        assertTrue(result.entries.isEmpty())
        assertFalse(result.walkComplete, "no capability and no fallback is INCOMPLETE, not empty")
    }

    @Test
    fun everyOnDemandPropertyOnAStoredRowIsWalked_withoutKnowingWhichTheyAre() = runTest {
        // ⭐⭐ The point of a schema-driven walk, held through the SCHEMA: the walk names one
        // entry per `Syncable(OnDemand)` property of the stored row's type, from that property's
        // declared derivation. The walk itself contains no list of properties and no knowledge that
        // five of these are thumbnails.
        val store = InMemoryObjectStore()
        // ⚠ The stub answers something different for every property, so a walk that still asked the
        // port would fail the derived-id assertion below.
        val proxy = declaringProxy(store, StubWriter { propertyId -> "port-" + propertyId })
        proxy.saveFile("/photo.jpg", contentId = "cid-golden")

        val result = walk(proxy, store).enumerate()

        assertTrue(result.walkComplete)
        assertEquals(
            6,
            result.entries.size,
            "File.Data plus the five thumbnail classes — one per OnDemand property; got ${result.entries}",
        )
        assertEquals(
            6,
            result.entries.map { it.propertyId }.distinct().size,
            "each must name its own property, or two would share an edge slot",
        )
        assertEquals(6, result.entries.map { it.contentId }.distinct().size, "and its own content id")
        assertEquals(
            1,
            result.entries.map { it.holder }.distinct().size,
            "and all six hang off the one row that holds them",
        )
        assertEquals(
            "cid-golden",
            result.entries.single { it.propertyId == org.tenshu.core.file.File.Data.id }.contentId,
        )
        assertTrue(result.entries.none { it.contentId.startsWith("port-") }, "the walk must not ask the port")
    }

    @Test
    fun aRowWithNoContentNamesNone_andThatIsNotASkip() = runTest {
        // ⚠ "None" is an answer: a text file with no content id, a folder. Counting it as a failed
        // read would report most of a realm as unreadable, and could make the marker unlatchable.
        val store = InMemoryObjectStore()
        val proxy = declaringProxy(store, StubWriter { "port-answer" })
        proxy.saveFile("/empty.txt", contentId = null)
        proxy.saveFile("/photo.jpg")

        val result = walk(proxy, store).enumerate()

        assertEquals(2, result.rowsRead)
        assertEquals(0, result.skipped.unresolvedProperties, "a row with no content is not a skip; got ${result.skipped}")
        assertEquals(0, result.skipped.rows)
        assertEquals(6, result.entries.size, "only the row with content declares; got ${result.entries}")
        assertTrue(result.walkComplete)
    }

    /**
     * A store whose scan stops **early with rows left** — a short page that is not the end.
     *
     * ⚠⚠ The two budgets make `rows.size < matchLimit` ambiguous between "no more rows" and "I
     * stopped early", which is why [ObjectStore.TypeScanPage.exhausted] exists. A walk that keys
     * completion on the page size silently drops everything after the first short page.
     */
    private class PagingStore(private val delegate: ObjectStore) : ObjectStore by delegate {
        override fun rowsOfTypes(typeIds: Set<String>): List<ObjectStore.DbEntry>? = null

        override fun scanRowsOfType(
            typeId: String,
            afterHash: Long,
            matchLimit: Int,
            examineLimit: Int,
        ): ObjectStore.TypeScanPage {
            val all = (delegate.rowsOfTypes(setOf(typeId)) ?: emptyList()).sortedBy { it.dataIdHash }
            val remaining = all.filter { it.dataIdHash > afterHash }
            // One row per page, and never `exhausted` until the tail — the shape a budget produces.
            val row = remaining.firstOrNull()
                ?: return ObjectStore.TypeScanPage(emptyList(), afterHash, exhausted = true)
            return ObjectStore.TypeScanPage(
                rows = listOf(row),
                nextCursor = row.dataIdHash,
                exhausted = false,
            )
        }
    }

    @Test
    fun aScanThatPagesIsFollowedToExhaustion_notStoppedAtTheFirstShortPage() = runTest {
        // ⚠⚠ Guards the mutation `if (page.rows.size < MATCH_LIMIT) stop`, which reads a budgeted
        // page as the end of the type and declares everything after it undeclared.
        val store = PagingStore(InMemoryObjectStore())
        val proxy = declaringProxy(store, StubWriter { propertyId -> "cid-" + propertyId })
        repeat(3) { i ->
            proxy.saveFile("/photo-$i.jpg")
        }

        val result = walk(proxy, store).enumerate()

        assertTrue(result.walkComplete)
        assertEquals(
            3,
            result.entries.map { it.holder }.distinct().size,
            "all three rows must be reached across pages; got ${result.entries.size} entries",
        )
    }

    /** Hands the walk one extra row of [typeId] that cannot be decoded, beside the real ones. */
    private class OneUndecodableRowStore(
        private val delegate: ObjectStore,
        private val typeId: String,
    ) : ObjectStore by delegate {
        override fun scanRowsOfType(
            typeId: String,
            afterHash: Long,
            matchLimit: Int,
            examineLimit: Int,
        ): ObjectStore.TypeScanPage? = null

        override fun rowsOfTypes(typeIds: Set<String>): List<ObjectStore.DbEntry>? {
            val rows = delegate.rowsOfTypes(typeIds) ?: return null
            if (this.typeId !in typeIds || rows.isEmpty()) return rows
            return rows + rows.first().copy(dataIdHash = rows.first().dataIdHash + 1, fields = emptyList())
        }
    }

    /**
     * ⚠⚠ A row the walk cannot decode must not be dropped with a bare `continue`, or a store it could
     * mostly not read produces the same result as a small realm. The readable row must still be
     * declared, and the unreadable one must be COUNTED.
     */
    @Test
    fun aRowThatWillNotDecodeIsCounted_notSilentlyDropped() = runTest {
        val backing = InMemoryObjectStore()
        val store = OneUndecodableRowStore(backing, org.tenshu.core.file.File.ID)
        val proxy = declaringProxy(store, StubWriter { propertyId -> "cid-" + propertyId })
        proxy.saveFile("/photo.jpg")

        val result = walk(proxy, store).enumerate()

        assertEquals(2, result.rowsRead, "both rows were read")
        assertEquals(1, result.skipped.undecodable, "the row with no fields cannot decode; got ${result.skipped}")
        assertEquals(1, result.skipped.rows)
        assertEquals(1, result.entries.map { it.holder }.distinct().size, "the readable row still declares")
    }

    /**
     * ⚠ A row whose edges cannot be computed is "could not ask", counted once per content-bearing
     * property of its type — the unit the skip line reports in. A `none` edge is "no content here" and
     * is not counted ([aRowWithNoContentNamesNone_andThatIsNotASkip]).
     */
    @Test
    fun edgesThatCannotBeComputedAreCounted_perProperty_andTheRowIsNotASkippedRow() = runTest {
        val store = InMemoryObjectStore()
        val proxy = declaringProxy(store, StubWriter { "port-answer" })
        proxy.saveFile("/photo.jpg")

        val registry = proxy.executionContext.schemaProvider as SchemaRegistry
        val result = SchemaDrivenContentWalk(
            executionContext = proxy.executionContext,
            objectStore = store,
            mapper = DbEntryMapper(proxy.tContext, registry, registry, proxy.executionContext.traitResolver),
            fallback = null,
            edgesOf = { error("the declaration could not be read for this row") },
        ).enumerate()

        assertEquals(1, result.rowsRead)
        assertEquals(6, result.skipped.unresolvedProperties, "six content-bearing properties went unasked; got ${result.skipped}")
        // ⚠ A failed PROPERTY is not a skipped ROW: a row with several failing properties must not push
        // the row total past the rows read.
        assertEquals(0, result.skipped.rows)
        assertTrue(result.entries.isEmpty())
        assertTrue(result.walkComplete, "a skip is reported; it does not, by itself, re-run the pass")
    }

    @Test
    fun aGraphWithNoOnDemandPropertyIsCompleteAndEmpty() = runTest {
        // ⚠ The other side of the same coin, and it must NOT be incomplete: the synthetic registry
        // registers no OnDemand property, so "nothing declares" is a true and final answer. Were
        // this incomplete, no realm would ever finish its backfill.
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val result = walk(proxy, InMemoryObjectStore()).enumerate()

        assertTrue(result.entries.isEmpty())
        assertTrue(result.walkComplete)
        assertEquals(0, result.rowsRead, "a counted walk over nothing is 0, not 'not counted'")
    }
}
