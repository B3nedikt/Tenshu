package app.tenshu.control.di

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.PhotosLike
import org.tenshu.inference.SimilarPhoto
import org.tenshu.mia.MiaSource
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * **A service ships when it is in [coreSchemaConfigurations], not when its tests are green.**
 *
 * ⚠⚠ This test exists because the same omission has now happened twice, silently, and both times
 * every test in the affected track was passing:
 *
 * - **Calendar** was missing from the proxy's schema list until CA-5c, "which is why Calendar never
 *   worked on a device".
 * - **The whole inference track** was missing through IN-3a, IN-4a, IN-4b and MQ-4 — four merged
 *   slices, each with an as-built section describing behaviour no device could produce.
 *
 * ⚠⚠ **The reason it is invisible is structural, and it is worth stating plainly: every service
 * test builds its own `TestProxy` registry.** That is the right way to test a service — but it
 * means a registry assembled by hand in a test is *never* the registry the app assembles, so no
 * amount of unit or integration testing inside a service can observe this. The check has to live
 * here, over the production list.
 *
 * ⚠ It asserts *reachability*, not correctness: that the proxy can materialise these types at all.
 * A service that is registered and broken fails elsewhere; a service that is absent fails nowhere.
 */
class ProxySchemaCoverageTest {

    private fun productionRegistry() = createCoreTestRegistry().apply {
        coreSchemaConfigurations().forEach { registerConfig(it) }
    }

    @Test
    fun everyMiaSourceIsAServiceTheProxyCanActuallyRead() {
        val registry = productionRegistry()

        // ⚠⚠ A grant in Mia's Settings for a service the proxy cannot materialise is a switch that
        // controls nothing — the user turns it on and the chain behind it fails. `MiaSource` is the
        // exhaustive list of what Mia claims to reach, so it is the right thing to check against.
        MiaSource.entries.forEach { source ->
            assertNotNull(
                registry.getSchema(source.serviceId),
                "${source.name} is offered as a grant but ${source.serviceId} is not registered " +
                    "in coreSchemaConfigurations() — the switch would control nothing",
            )
        }
    }

    @Test
    fun theInferenceTypesMiaAndDriveReadAreRegistered() {
        val registry = productionRegistry()

        // The three the track actually depends on: the stored row (IN-3a), the op's return shape
        // and the op itself (IN-4b). ⚠ An op absent from the registry does not fail loudly at the
        // call site — it comes back as an unsupported operation, a long way from this list.
        assertNotNull(registry.getSchema(MediaLabel.ID))
        assertNotNull(registry.getSchema(SimilarPhoto.ID))
        assertNotNull(registry.getSchema(PhotosLike.ID))
    }
}
