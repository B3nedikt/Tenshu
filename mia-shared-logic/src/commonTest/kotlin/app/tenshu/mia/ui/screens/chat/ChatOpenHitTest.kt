package app.tenshu.mia.ui.screens.chat

import app.tenshu.mia.data.ConversationRepository
import app.tenshu.mia.model.Conversation
import app.tenshu.mia.model.Hit
import app.tenshu.mia.model.MiaMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.core.app.OpenTargetUnavailable
import org.tenshu.core.common.app.OpenTargetResult
import org.tenshu.core.common.app.TargetOpener
import org.tenshu.core.entity.DataId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest
import org.tenshu.viewmodel.test.OwnedViewModels

/**
 * Tapping a citation (`control-cross-app-open.md` TT-1c).
 *
 * ⚠⚠ **Every case here asserts what the USER is told**, not that the opener was called. §14's
 * device pass is the reason: two shipped tests pinned a refusal's *explanation* and let its
 * *verdict* say anything, so both stayed green while the surface rendered the one sentence §12.2
 * forbids. A tap is the worse version of that failure — a refusal nobody renders is
 * indistinguishable from a dead button.
 */
class ChatOpenHitTest {

    @BeforeTest
    fun setUp() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.resetMain()
    }

    private class Repo : ConversationRepository {
        val notes = mutableListOf<String>()
        override fun messages(): Flow<List<MiaMessage>> = flowOf(emptyList())
        override fun replying(): Flow<Boolean> = flowOf(false)
        override fun history(): Flow<List<Conversation>> = flowOf(emptyList())
        override suspend fun send(text: String) = Unit
        override suspend fun note(text: String) { notes += text }
    }

    private fun hit(): Hit = Hit(
        target = DataId.StringDataId.create(
            context = null,
            type = "tenshu/file",
            keyValue = "/Photos/a.jpg",
        ),
        title = "a.jpg",
        subtitle = "/Photos",
        atMs = 0,
    )

    /** Records what it was asked to open and answers [result]. */
    private class FakeOpener(private val result: OpenTargetResult) : TargetOpener {
        var seen: DataId? = null
        override suspend fun open(target: DataId): OpenTargetResult {
            seen = target
            return result
        }
    }

    private fun viewModel(vms: OwnedViewModels, repo: Repo, opener: TargetOpener): ChatViewModel =
        vms.own(ChatViewModel(repository = repo, voice = null, opener = opener))

    private fun viewModel(vms: OwnedViewModels, repo: Repo, result: OpenTargetResult): ChatViewModel =
        viewModel(vms, repo, FakeOpener(result))

    @Test
    fun aSuccessfulOpenSaysNothing() = vmTest { vms ->
        val repo = Repo()
        viewModel(vms, repo, OpenTargetResult.Delivered).openHit(hit())

        // ⚠ The app coming to the front IS the acknowledgement. A line of chat under it would be
        // narrating something the user is already looking at.
        assertEquals(emptyList(), repo.notes)
    }

    @Test
    fun aLaunchedOpenAlsoSaysNothing() = vmTest { vms ->
        val repo = Repo()
        viewModel(vms, repo, OpenTargetResult.LaunchRequested).openHit(hit())

        assertEquals(emptyList(), repo.notes)
    }

    @Test
    fun theTargetHandedToControlIsTheHitsOwnId() = vmTest { vms ->
        val opener = FakeOpener(OpenTargetResult.Delivered)
        viewModel(vms, Repo(), opener).openHit(hit())

        // ⚠⚠ CO-D1 at the last hop: the caller passes the ROW's id and never names an app. If this
        // ever became an appId, the whole design's central claim would be false at the one place a
        // reader would not look.
        val target = opener.seen as? DataId.StringDataId
        assertEquals("tenshu/file", target?.type?.get())
        assertEquals("/Photos/a.jpg", target?.keyValue?.get())
    }

    @Test
    fun everyRefusalReasonProducesADistinctSentence() = vmTest { vms ->
        val reasons = listOf(
            OpenTargetUnavailable.Reasons.NO_HANDLER,
            OpenTargetUnavailable.Reasons.NOT_INSTALLED_HERE,
            OpenTargetUnavailable.Reasons.LAUNCH_FAILED,
            OpenTargetUnavailable.Reasons.NOT_PERMITTED,
            OpenTargetUnavailable.Reasons.NO_DELIVERY,
        )
        val sentences = reasons.map { reason ->
            val repo = Repo()
            viewModel(vms, 
                repo,
                OpenTargetResult.Refused(reason = reason, message = "developer text"),
            ).openHit(hit())
            repo.notes.single()
        }

        // ⚠⚠ Distinct, because five reasons collapsed into one sentence is the same defect as no
        // sentence at all — the user learns nothing they can act on either way.
        assertEquals(sentences.size, sentences.toSet().size, "two reasons say the same thing")
        // ⚠⚠ And never the proxy's own `message`: it names an `appId` the user has never seen.
        assertTrue(sentences.none { it.contains("developer text") })
    }

    @Test
    fun anUnknownReasonStillSaysSomething() = vmTest { vms ->
        val repo = Repo()
        viewModel(vms, 
            repo,
            OpenTargetResult.Refused(reason = "somethingThisBuildHasNeverHeardOf", message = ""),
        ).openHit(hit())

        // ⚠ A newer proxy must not be able to produce a silent tap in an older app.
        assertTrue(repo.notes.single().isNotEmpty())
    }

    @Test
    fun anUnreachableControlIsSaidOutLoud() = vmTest { vms ->
        val repo = Repo()
        viewModel(vms, repo, OpenTargetResult.Unreachable).openHit(hit())

        assertTrue(repo.notes.single().isNotEmpty())
    }
}
