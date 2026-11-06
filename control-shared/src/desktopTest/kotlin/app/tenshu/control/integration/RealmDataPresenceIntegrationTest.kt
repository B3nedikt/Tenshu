package app.tenshu.control.integration

import app.tenshu.control.data.TenshuRealmDataClient
import app.tenshu.control.integration.utils.startAppIntegrationKoin
import app.tenshu.control.integration.utils.stopAppIntegrationKoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.tenshu.core.TResult
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.file.File
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.Workout
import org.tenshu.core.health.recordWorkout
import org.tenshu.file.client.fileService
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `TenshuRealmDataClient.hasData` over the real in-process proxy — the Data section lists only
 * types that answer something other than `false` here.
 *
 * ⚠ The view-model tests use a fake client, so they cannot catch the two ways this goes wrong
 * for real: a probe that answers `null` for a type (its card then shows for every user, which is
 * the bug this fixes), and a probe that stays `false` after data is written (the card never
 * shows at all).
 */
class RealmDataPresenceIntegrationTest {

    @After
    fun tearDown() = stopAppIntegrationKoin()

    @Test
    fun anEmptyRealm_answersEmptyForEveryDeclaredType() = runBlocking {
        val client = startAppIntegrationKoin()
        val realmData = TenshuRealmDataClient(client)

        val declared = realmData.realmDataTypeIds()
        assertTrue(declared.any { it.contains("routine", ignoreCase = true) }, "harness has Routine")

        // Every type must give a definite answer. A `null` would put its card back on every
        // user's screen, whatever apps they use.
        val answers = declared.associateWith { realmData.hasData(it) }
        assertEquals(
            declared.associateWith { false },
            answers,
            "a fresh realm holds no realm data, and every type must be able to say so",
        )
    }

    @Test
    fun aFolder_makesFilesPresent() = runBlocking {
        val client = startAppIntegrationKoin()
        val realmData = TenshuRealmDataClient(client)
        assertEquals(false, realmData.hasData(File.ID))

        val made = client.fileService.defaultFileSystem.createFolder("/Documents")
        assertTrue(made is TResult.Success<*>, "createFolder failed: $made")

        assertEquals(true, realmData.hasData(File.ID))
    }

    @Test
    fun aWorkout_makesWorkoutsPresent_andNothingElse() = runBlocking {
        val client = startAppIntegrationKoin()
        val realmData = TenshuRealmDataClient(client)
        val health = client.getService<HealthService>(HealthService.ID)
        val ctx = client.environment.tContext

        val recorded = health.recordWorkout(
            Workout.create(
                ctx, "wo-1", "running", "manual",
                UnixInstantDto(1_700_000_000_000L), UnixInstantDto(1_700_003_600_000L), 3600,
            ),
        )
        assertTrue(recorded is TResult.Success<*>, "recordWorkout failed: $recorded")

        assertEquals(true, realmData.hasData(Workout.ID))
        assertEquals(false, realmData.hasData(File.ID))
    }
}
