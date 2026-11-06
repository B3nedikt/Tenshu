package app.tenshu.control.di

import app.tenshu.control.inference.IndexCoverage
import app.tenshu.control.inference.IndexCoverageSource
import app.tenshu.control.inference.IndexPassPosition
import app.tenshu.control.inference.IndexProgressSource
import app.tenshu.control.inference.LastIndexCoverage
import app.tenshu.control.inference.ModelStoreAccess
import app.tenshu.control.inference.ModelStoreAccessSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.inference.proxy.IndexProgressHolder
import org.tenshu.inference.proxy.catalog.ModelStoreOwnership
import org.tenshu.inference.proxy.catalog.ModelStoreRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * **The Models screen's holders resolve under the read interfaces `:control-shared-logic` asks
 * for** (PIO-7 PR-4; index progress since the Refresh button went).
 *
 * ⚠⚠ The failure this catches is silent. `ClientModelsRepository` takes both as nullable and its
 * Koin provider reads them with `getOrNull`, so a holder bound only under its concrete type
 * compiles, starts, and behaves as if it were absent: the coverage line never appears, and a
 * second desktop Control offers buttons that cannot work.
 *
 * ⭐ The real `proxyModule`, not a copy. Both providers are leaves once the ownership is given, so
 * nothing else in the graph is built — in particular not desktop's file lock, which would be taken
 * in the developer's real data directory.
 */
class ModelsHolderBindingTest {

    private val refusing = module {
        single<ModelStoreOwnership> {
            ModelStoreOwnership { ModelStoreRefusal.OwnedElsewhere("another Control owns it") }
        }
    }

    @Test
    fun `the coverage holder the indexer writes is the one the interface reads`() = runBlocking {
        val koin = koinApplication { modules(proxyModule, refusing) }.koin
        try {
            val source = koin.get<IndexCoverageSource>()
            assertSame<Any>(koin.get<LastIndexCoverage>(), source)

            koin.get<LastIndexCoverage>().record(total = 1345, notOnDevice = 1290, ran = true)
            assertEquals(IndexCoverage(1345, 1290), source.coverage.first())
        } finally {
            koin.close()
        }
    }

    /**
     * ⚠⚠ The index bar reads the holder the indexer ticks. A source over a second holder would compile,
     * resolve and never move — and the repository asks for this one with `get`, so this test is what
     * pins that the production graph can answer it at all.
     */
    @Test
    fun `the progress holder the indexer ticks is the one the interface reads`() = runBlocking {
        val koin = koinApplication { modules(proxyModule, refusing) }.koin
        try {
            val source = koin.get<IndexProgressSource>()
            val holder = koin.get<IndexProgressHolder>()
            assertEquals(null, source.progress.first())

            holder.start(total = 1345)
            holder.advance(done = 7)
            assertEquals(IndexPassPosition(done = 7, total = 1345), source.progress.first())

            holder.finish()
            assertEquals(null, source.progress.first())
        } finally {
            koin.close()
        }
    }

    @Test
    fun `the store's refusal is readable under the interface`() {
        val koin = koinApplication { modules(proxyModule, refusing) }.koin
        try {
            val source = koin.get<ModelStoreAccessSource>()
            assertSame<Any>(koin.get<ModelStoreAccess>(), source)
            assertEquals(ModelStoreAccessSource.Refusal.OwnedElsewhere, source.refusal)
        } finally {
            koin.close()
        }
    }
}
