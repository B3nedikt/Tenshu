package org.tenshu.core.common.di

import org.koin.dsl.koinApplication
import org.tenshu.core.core.TContext
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Regression for the Koin module-sharing leak that surfaced in
 * `:system-test-utils`'s `PairingSystemTest`. Top-level `val` Koin modules
 * (and `val` modules transitively pulled in via `includes(...)`) cause
 * `single<>` instances to be shared across separate `koinApplication { }`
 * scopes in Koin 4.2 — joiner-side ops in a SystemTest were dispatching
 * through the host's `TContext` because the `single<TContext>` declaration
 * leaked across the two per-client KoinApps.
 *
 * The fix: declare `coreCommonModule()` and `cryptoBackendsModule()` as
 * functions returning a fresh `Module` per call. This test guards both
 * shapes — within-app caching still works, but across-app instances are
 * distinct.
 *
 * Production paths use one global Koin (`startKoin { ... }`) and aren't
 * affected by the underlying leak; this test exists for the multi-app
 * use case (SystemTests today; potentially other test fixtures later).
 */
class CoreCommonModuleIsolationTest {

    @Test fun two_koinApps_get_distinct_TContext_instances() {
        val a = koinApplication { modules(coreCommonModule()) }
        val b = koinApplication { modules(coreCommonModule()) }
        try {
            val ta: TContext = a.koin.get()
            val tb: TContext = b.koin.get()
            assertNotSame(
                ta, tb,
                "Two koinApps loading coreCommonModule() must produce DISTINCT TContext singletons"
            )
        } finally {
            a.close(); b.close()
        }
    }

    @Test fun within_one_koinApp_TContext_is_cached() {
        val a = koinApplication { modules(coreCommonModule()) }
        try {
            val first: TContext = a.koin.get()
            val second: TContext = a.koin.get()
            assertSame(
                first, second,
                "Within one koinApp, single<TContext>() must be cached"
            )
        } finally {
            a.close()
        }
    }
}
