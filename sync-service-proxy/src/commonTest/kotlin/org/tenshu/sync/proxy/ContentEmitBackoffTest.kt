package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `control-background-work-off-fgs.md` §5.7: against a content plane that fails every `PUT`, a
 * drain that retries the whole journal on every tick sends about 3,000 `PUT`s a minute. An entry
 * whose upload failed waits, exponentially longer with jitter, up to a cap; anything landing ends
 * every wait.
 */
class ContentEmitBackoffTest {

    // ── the schedule itself ─────────────────────────────────────────────────────────────────────

    @Test
    fun theWaitDoublesFromTheBase_isCapped_andIsJitteredWithinItsUpperHalf() {
        val b = ContentEmitBackoff(nowMs = { 0L })
        val base = ContentEmitBackoff.BASE_MS
        val cap = ContentEmitBackoff.MAX_MS
        // Upper bound (jitter → 1): base, 2×, 4× … until the cap.
        assertEquals(base, b.delayFor(1, 0.999_999) + 1)
        assertEquals(2 * base, b.delayFor(2, 0.999_999) + 1)
        assertEquals(4 * base, b.delayFor(3, 0.999_999) + 1)
        assertEquals(cap, b.delayFor(10, 0.999_999) + 1, "capped")
        assertEquals(cap, b.delayFor(60, 0.999_999) + 1, "and no overflow far past it")
        // Lower bound (jitter 0): half the ceiling, so a backlog that failed together spreads out.
        assertEquals(base / 2, b.delayFor(1, 0.0))
        assertEquals(cap / 2, b.delayFor(60, 0.0))
    }

    // ── wired into the drain ────────────────────────────────────────────────────────────────────

    private class Fixture(
        val observer: SyncObserver,
        val store: InMemoryContentBlobStore,
        val files: InMemoryFileDatabase,
        val proxy: TestProxy,
        val backoff: ContentEmitBackoff,
        val advance: (Long) -> Unit,
    )

    private suspend fun fixture(scope: kotlinx.coroutines.CoroutineScope): Fixture {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = scope)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            context = proxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        syncManager.initialize("mock phrase")
        val files = InMemoryFileDatabase()
        val store = InMemoryContentBlobStore(proxy.tContext)
        var now = 0L
        val backoff = ContentEmitBackoff(nowMs = { now }, random = Random(7))
        val observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(proxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
            contentBlobStore = store,
            fileDatabase = files,
            contentGate = SyncGate { true },
            contentEmitBackoff = backoff,
        )
        return Fixture(observer, store, files, proxy, backoff) { now += it }
    }

    /** Journals [path]'s content through a failing inline upload, as an automatic backup leaves it. */
    private suspend fun Fixture.journal(path: String) {
        files.writeRawByteArray(path, ByteArray(64) { (it + path.length).toByte() })
        observer.emitFileContent(
            targetEntityId = DataId.StringDataId.create(context = proxy.tContext, type = SyntheticTestData.ID, keyValue = path),
            propertyId = "${SyntheticTestData.ID}/data",
            filePath = path,
        )
    }

    @Test
    fun aFailedUploadWaits_soAFailingPlaneIsNotRetriedEveryTick() = runTest {
        // ⚠ Two entries, four refusals in all: under PLANE_TRIP, so it is the PER-ENTRY wait holding
        // them and not the plane's (with five, the breaker would mask this guard).
        val f = fixture(this)
        f.store.failPuts = true
        repeat(2) { f.journal("/f$it.bin") }        // two refused inline uploads; both wait
        f.advance(ContentEmitBackoff.BASE_MS)        // …until their waits end

        f.observer.drainDeferredContent()
        val afterFirst = f.store.attemptedRefs.size
        assertEquals(4, afterFirst, "precondition: inline twice, then the pass tries both again")
        assertEquals(false, f.backoff.planeWaiting(), "precondition: the plane never tripped")

        // The next ticks, a second apart: every entry is waiting out its failure.
        repeat(10) {
            f.advance(1_000L)
            assertEquals(2, f.observer.drainDeferredContent(), "nothing is dropped — only deferred")
        }
        assertEquals(afterFirst, f.store.attemptedRefs.size, "no PUT while every entry waits")

        // Past the cap, they are tried again.
        f.advance(ContentEmitBackoff.MAX_MS)
        f.observer.drainDeferredContent()
        assertTrue(f.store.attemptedRefs.size > afterFirst, "a wait ends; the entry is retried")
    }

    /**
     * An early landing must not exempt the rest of the pass from the plane check: against a relay
     * failing most PUTs (one lands, then a run of 503s) the drain would send the whole journal
     * anyway — the storm back.
     */
    @Test
    fun aLandingEarlyInThePass_doesNotExemptTheRestFromARetrippedPlane() = runTest {
        val f = fixture(this)
        f.store.failPuts = true
        repeat(20) { f.journal("/m$it.bin") }        // journalled; the inline refusals trip the plane
        f.advance(ContentEmitBackoff.MAX_MS)         // every wait over, the plane's too
        f.store.failPuts = false
        val before = f.store.attemptedRefs.size
        f.store.failPutsAfterCount = f.store.putCount + 2   // the first file (chunk + manifest) lands

        f.observer.drainDeferredContent()

        val thisPass = f.store.attemptedRefs.size - before
        assertTrue(
            thisPass <= 2 + ContentEmitBackoff.PLANE_TRIP,
            "one file landed, then the next run of refusals re-tripped the plane and ended the pass; sent $thisPass",
        )
        assertTrue(f.backoff.planeWaiting(), "the plane waits again")
    }

    /** The drain's own landing clears the waits, not only an inline one. */
    @Test
    fun aDrainedUploadThatLands_endsEveryOtherWait() = runTest {
        val f = fixture(this)
        f.store.failPuts = true
        repeat(2) { f.journal("/w$it.bin") }         // both refused inline: both wait
        f.store.failPuts = false
        f.files.writeRawByteArray("/late.bin", ByteArray(64) { 9 })
        // A third entry journalled while the plane is healthy, left for the drain: journal it, then
        // drop its inline attempt's effect by having its PUT fail once, with no wait of its own.
        f.store.failPutsWith = "${CONTENT_PUT_ERROR_PREFIX} 413 Payload Too Large"   // not an outage: no wait
        f.journal("/late.bin")
        f.store.failPutsWith = null
        assertEquals(2, f.observer.drainDeferredContent(), "the two still wait; the third lands in the drain")
        // ⚠ No time passes: only the drain's landing can have ended their waits.
        assertEquals(0, f.observer.drainDeferredContent(), "the drain's landing cleared the others' waits")
    }

    /**
     * Only a 5xx, a 429 or a transport failure is the plane failing. A 507 has its own stop, a
     * 402/401 their own backoffs, and a 4xx is about this object — and one breaker serves every
     * realm, so counting a lapsed shared realm's 402s would hold the personal realm's uploads.
     */
    @Test
    fun onlyAPlaneOutageCountsTowardsTheBreaker() = runTest {
        val f = fixture(this)
        // ⚠ The 507 last: it latches the inline path shut, and the reasons after it would never be sent.
        for (reason in listOf(
            "$ENTITLEMENT_REJECTION_MARKER: content PUT rejected (402 Payment Required)",
            "$CONTENT_PUT_ERROR_PREFIX 401 Unauthorized",
            "$CONTENT_PUT_ERROR_PREFIX 413 Payload Too Large",
            "$STORAGE_REJECTION_MARKER: content PUT rejected (507 Insufficient Storage)",
        )) {
            val sentBefore = f.store.attemptedRefs.size
            f.store.failPutsWith = reason
            repeat(ContentEmitBackoff.PLANE_TRIP + 1) { f.journal("/n${reason.length}-$it.bin") }
            assertTrue(f.store.attemptedRefs.size > sentBefore, "precondition: sent and refused: $reason")
            assertEquals(false, f.backoff.planeWaiting(), "not an outage: $reason")
            assertEquals(0, f.backoff.waitingCount, "and no entry waits for it: $reason")
        }
        // ⚠ A fresh fixture: the 507 above latched the inline path shut.
        val g = fixture(this)
        g.store.failPutsWith = "$CONTENT_PUT_ERROR_PREFIX 503 Service Unavailable"
        repeat(ContentEmitBackoff.PLANE_TRIP) { g.journal("/o$it.bin") }
        assertEquals(true, g.backoff.planeWaiting(), "a run of 503s is")
    }

    @Test
    fun theOutageClassification() {
        for (yes in listOf(null, "link dropped", "$CONTENT_PUT_ERROR_PREFIX 500 Internal Server Error",
            "$CONTENT_PUT_ERROR_PREFIX 503 Service Unavailable", "$CONTENT_PUT_ERROR_PREFIX 429 Too Many Requests",
            "SyncObserver -> chunk PUT failed — $CONTENT_PUT_ERROR_PREFIX 502 Bad Gateway")) {
            assertEquals(true, isContentPlaneOutage(yes), "outage: $yes")
        }
        for (no in listOf("$CONTENT_PUT_ERROR_PREFIX 400 Bad Request", "$CONTENT_PUT_ERROR_PREFIX 404 Not Found",
            "$STORAGE_REJECTION_MARKER: content PUT rejected (507 Insufficient Storage)",
            "$ENTITLEMENT_REJECTION_MARKER: content PUT rejected (402 Payment Required)")) {
            assertEquals(false, isContentPlaneOutage(no), "not an outage: $no")
        }
    }

    /**
     * After a long outage, if only a landing ended the wait, nothing could land while it waited and
     * recovery would take up to a whole wait. Half-open lets the next write through; one more refusal
     * re-trips it, at the next longer wait.
     */
    @Test
    fun halfOpen_letsTheNextWriteThrough_andOneRefusalTripsItAgain() = runTest {
        val f = fixture(this)
        f.store.failPuts = true
        repeat(ContentEmitBackoff.PLANE_TRIP) { f.journal("/h$it.bin") }
        assertTrue(f.backoff.planeWaiting(), "precondition: tripped")
        val firstWait = f.backoff.planeWaitRemainingMs()

        f.observer.retryContentPlane()
        assertEquals(false, f.backoff.planeWaiting(), "the user's upload, a reconnect, the app in front: try now")
        f.journal("/h-again.bin")                    // still refused
        assertTrue(f.backoff.planeWaiting(), "one refusal trips it again")
        assertTrue(f.backoff.planeWaitRemainingMs() > firstWait / 2, "at the next, longer wait")

        f.observer.retryContentPlane()
        f.store.failPuts = false
        f.journal("/h-heals.bin")
        assertEquals(false, f.backoff.planeWaiting())
        assertEquals(0, f.observer.drainDeferredContent(), "the heal lands, and every waiting entry goes with it")
    }

    /**
     * §5.7: per-entry waits bound only the repeats, so a backlog's first attempts alone are a storm
     * (1,095 entries cost ~9,000 failing PUTs in 3.5 min). After [ContentEmitBackoff.PLANE_TRIP]
     * refused writes in a row the whole plane waits — the rest of the pass, and the inline uploads.
     */
    @Test
    fun aRunOfRefusedWritesStopsThePass_andTheInlineUploads() = runTest {
        val f = fixture(this)
        f.store.failPuts = true
        // Journalled with the plane healthy: the inline attempts are not what this test counts.
        f.store.failPuts = false
        f.store.failPutsForRefs.clear()
        repeat(30) { f.files.writeRawByteArray("/b$it.bin", ByteArray(64) { b -> (b + it).toByte() }) }
        f.store.failPuts = true
        repeat(30) { f.journal("/b$it.bin") }        // inline attempts: they trip the plane too
        val inlineAttempts = f.store.attemptedRefs.size
        assertTrue(inlineAttempts < 30, "after a few refused inline uploads the rest only journal; got $inlineAttempts")

        f.observer.drainDeferredContent()
        assertEquals(inlineAttempts, f.store.attemptedRefs.size, "the drain sends nothing while the plane waits")

        // The plane's wait ends; one pass tries until the next trip, not the whole journal.
        f.advance(ContentEmitBackoff.MAX_MS)
        f.observer.drainDeferredContent()
        assertTrue(f.store.attemptedRefs.size - inlineAttempts <= ContentEmitBackoff.PLANE_TRIP, "a pass stops at the next run of refusals")
    }

    @Test
    fun anUploadThatLands_endsEveryWait() = runTest {
        val f = fixture(this)
        f.store.failPuts = true
        repeat(3) { f.journal("/f$it.bin") }
        f.observer.drainDeferredContent()           // all three fail, all three wait

        // The plane heals. A new file uploads inline on a client's call and lands.
        f.store.failPuts = false
        f.journal("/new.bin")

        // ⚠ No time passes: without the reset these would sit out up to 15 minutes each.
        assertEquals(0, f.observer.drainDeferredContent(), "a landed upload says the plane answers: the waiting entries go now")
    }

    @Test
    fun theUsersOwnOverrideIsNeverMadeToWait() = runTest {
        val f = fixture(this)
        f.store.failPuts = true
        f.journal("/f.bin")
        f.observer.drainDeferredContent()           // fails, waits
        f.store.failPuts = false

        assertEquals(0, f.observer.drainDeferredContent(force = true), "\"upload over cellular now\" goes now")
    }
}
