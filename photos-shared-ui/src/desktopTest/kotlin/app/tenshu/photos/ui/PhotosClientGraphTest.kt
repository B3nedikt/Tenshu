package app.tenshu.photos.ui

import app.tenshu.photos.di.photosSchemaConfigs
import org.tenshu.core.common.assertClientGraphRegisters
import org.tenshu.core.file.FileService
import org.tenshu.core.ingest.IngestService
import org.tenshu.core.place.PlaceService
import org.tenshu.inference.InferenceService
import org.tenshu.photo.PhotoService
import org.tenshu.preference.PreferenceService
import org.tenshu.storage.StorageService
import org.tenshu.sync.SyncService
import kotlin.test.Test

/**
 * Every service Photos reaches is registered in its client graph.
 *
 * ## ⚠⚠ Why this test exists — Photos is where the failure was first paid for
 *
 * `TenshuEnvironment.getService` **errors** on an unregistered schema — *"No schema registered for
 * service id"* — and every caller in this app catches. So a missing registration is not a crash;
 * it is a screen that renders as though the data were simply absent.
 *
 * Three phases shipped that way before S2 went looking, and each looked like a different bug:
 *
 * | phase | service | what the user saw |
 * |---|---|---|
 * | PH-3 | `:place-service` | the Places section, permanently empty |
 * | PH-4a | `:ingest-service` | Settings saying "Can't reach Tenshu Control" |
 * | PH-6b | `:preference-service`, `:sync-service` | the duplicate-skip toggle stuck at its default |
 *
 * ⚠ The pattern is what makes it worth a test rather than a fix: each phase added a dependency to
 * `:photos-shared-logic`, which compiles fine, and nothing anywhere connected that to the *runtime*
 * list two modules away. The compiler cannot see the gap and neither can a view-model test, because
 * both sides of it are real — the code is right and the graph is short.
 *
 * ⚠ The assertion now lives in `assertClientGraphRegisters`, shared with the eight sibling apps
 * that grew the same guard afterwards — including the "the list is not accidentally empty" check
 * that used to be a second test here.
 */
class PhotosClientGraphTest {

    /**
     * The services this app's repositories and registries actually call.
     *
     * ⚠ Grepped, not guessed — `client.<x>Service` across `:photos-shared-logic` and
     * `:app-ingest`. **When a repository reaches a new service, add it here and to
     * `photosSchemaConfigs`.**
     */
    private val reached = mapOf(
        "the feed, search and every file read" to FileService.ID,
        "albums, ordering and flags" to PhotoService.ID,
        "the Places section's join (PH-3)" to PlaceService.ID,
        "the Settings tab's source list (PH-4a)" to IngestService.ID,
        "the duplicate-skip setting (PH-6b)" to PreferenceService.ID,
        "this device's identity, which every per-device row is keyed on" to SyncService.ID,
        "search sources 2 and 3 (S2)" to InferenceService.ID,
        "the realm storage banner" to StorageService.ID,
    )

    @Test
    fun everyServiceTheAppReachesIsRegistered() =
        assertClientGraphRegisters("Photos", photosSchemaConfigs, reached)
}
