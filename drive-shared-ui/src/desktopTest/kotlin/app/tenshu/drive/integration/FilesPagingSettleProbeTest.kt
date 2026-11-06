package app.tenshu.drive.integration

import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.integration.utils.PagingSettleProbe
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.OwnedViewModels

/**
 * The `folderRow_carriesChildCount_withoutProjectingChildren` scenario, driven through
 * [PagingSettleProbe] instead of `asSnapshot`, so a failure to settle prints **why**.
 *
 * ⚠⚠ This is a **diagnostic harness for an open investigation**, not a guard — see
 * `docs/tech-debt.md` § "The hang, narrowed to one seam". The hang it hunts reproduces at roughly
 * 5 in 34 full-gate runs and has never been reproduced in isolation, so this test passing means
 * nothing at all; its only job is to be *ready* when the hang next lands, and to be cheap to loop.
 *
 * ⚠ Deliberately mirrors the failing test's fixture exactly — one folder, four children, one loose
 * file, then a single listing read. Every difference from that fixture is a difference from the
 * thing under investigation, which is why this duplicates rather than shares a helper.
 */
class FilesPagingSettleProbeTest {

    private lateinit var filesRepository: FilesRepository
    private lateinit var probeScope: CoroutineScope

    private lateinit var appKoin: AppIntegrationKoin

    @Before
    fun setup() {
        appKoin = startAppIntegrationKoin()
        filesRepository = appKoin.get()
        probeScope = CoroutineScope(Job())
    }

    @After
    fun tearDown() {
        // ⚠⚠ cancel-AND-JOIN, not `cancel`. Cancellation is cooperative, so a bare cancel returns
        // while the presenter's collectors are still unwinding — and a coroutine that throws after
        // its test has finished is attributed to whatever runs NEXT (`docs/tech-debt.md`, the
        // thirteenth victim). A diagnostic added to hunt one flake must not manufacture another;
        // this is the same rule `OwnedViewModels` exists to enforce in `:maps-shared-logic`.
        runBlocking { probeScope.coroutineContext.job.cancelAndJoin() }
        appKoin.stop()
    }

    @Test
    fun theListingSettles_andSaysWhyWhenItDoesNot() = runBlocking {
        filesRepository.createFolder("/docs")
        repeat(4) { i ->
            filesRepository.uploadFile(
                uriString = "mock://doc$i.txt",
                parentPath = "/docs",
                fileName = "doc$i.txt",
            )
        }
        filesRepository.uploadFile(
            uriString = "mock://loose.txt",
            parentPath = "/",
            fileName = "loose.txt",
        )

        val probe = PagingSettleProbe<FileModel>(probeScope)
        val items = probe.awaitSettle(filesRepository.getFilesPaged("/"))

        // Printed on the SUCCESS path too, deliberately: this is a diagnostic, and a healthy
        // timeline is the control you compare a sick one against. It is also the only way to
        // notice the probe has quietly stopped observing anything.
        println("PROBE-TIMELINE (settled)\n" + probe.timelineSnapshot().joinToString("\n"))

        assertTrue(
            items.any { it.name == "docs" },
            "the folder row must be present once the listing settles; timeline:\n" +
                probe.timelineSnapshot().joinToString("\n"),
        )
    }
}
