package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-text audit: **the Models repository asks Koin for its two holders by the interface types
 * the proxy binds** (PIO-7 PR-4).
 *
 * ⚠⚠ Both parameters are nullable and read with `getOrNull`, so asking for a type nobody binds is
 * not an error: the repository is built without the holder, and the screen silently loses its
 * coverage line and its read-only notice. `ModelsHolderBindingTest` in `:control-shared-proxy` pins
 * the other half — that the proxy binds exactly these two types.
 */
class ModelsRepositoryWiringTest {

    private val viewModels = File("src/commonMain/kotlin/app/tenshu/control/di/ViewModelsModule.kt")

    @Test
    fun `the provider names both read interfaces`() {
        // ⚠ Anti-vacuity: if the path moves, go red rather than quietly stop guarding.
        assertTrue(viewModels.isFile, "ViewModelsModule.kt moved; this test guards nothing")
        val text = viewModels.readText()
        val provider = text.substringAfter("single<ModelsRepository>", missingDelimiterValue = "")
            .substringBefore("viewModel {")

        assertTrue(provider.isNotBlank(), "the ModelsRepository provider moved out of ViewModelsModule")
        assertTrue(
            "getOrNull<IndexCoverageSource>()" in provider,
            "the Models repository no longer asks for IndexCoverageSource, the type ProxyModule binds",
        )
        // ⚠⚠ `get`, so an unbound source fails at resolution instead of bringing back a bar that
        // misses every later pass.
        assertTrue(
            "get<IndexProgressSource>()" in provider,
            "the Models repository no longer asks for IndexProgressSource, the type ProxyModule binds",
        )
        // ⚠⚠ The roster comes through `ClientModelsDevices`, whose failed read is *not known*.
        // `SyncRepository.devices` reads one as an empty list, which says *no other device*.
        assertTrue(
            "devices = ClientModelsDevices(" in provider,
            "the Models repository no longer reads the realm's devices through ClientModelsDevices",
        )
        assertTrue(
            "getOrNull<ModelStoreAccessSource>()" in provider,
            "the Models repository no longer asks for ModelStoreAccessSource, the type ProxyModule binds",
        )
    }
}
