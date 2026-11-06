package app.tenshu.mia.ui.screens

import app.tenshu.mia.data.InMemoryOnboardingStore
import app.tenshu.mia.data.MockConversationRepository
import app.tenshu.mia.model.MessageBody
import app.tenshu.mia.ui.LaunchAnnouncer
import app.tenshu.mia.ui.MiaAppViewModel
import app.tenshu.mia.ui.screens.chat.ChatViewModel
import app.tenshu.mia.ui.screens.history.MiaHistoryViewModel
import app.tenshu.mia.ui.screens.onboarding.OnboardingStep
import app.tenshu.mia.ui.screens.onboarding.OnboardingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

@OptIn(ExperimentalCoroutinesApi::class)
class MiaViewModelsTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun chat_sendClearsInputAndDeliversReply() = vmTest { vms ->
        val vm = vms.own(ChatViewModel(MockConversationRepository()))
        vm.setInput("Show me a photo")
        assertTrue(vm.uiState.value.canSend)

        vm.send()
        assertEquals("", vm.uiState.value.input)
        advanceUntilIdle()

        val messages = vm.uiState.value.messages
        assertEquals(2, messages.size)
        assertIs<MessageBody.PhotoGrid>(messages[1].body)
        assertFalse(vm.uiState.value.replying)
    }

    @Test
    fun chat_blankInputCannotSend() = vmTest { vms ->
        val vm = vms.own(ChatViewModel(MockConversationRepository()))
        vm.setInput("   ")
        assertFalse(vm.uiState.value.canSend)
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.messages.isEmpty())
    }

    @Test
    fun app_gateFlipsWhenOnboardingCompletes() = vmTest { vms ->
        val store = InMemoryOnboardingStore()
        val appVm = vms.own(MiaAppViewModel(store))
        assertEquals(false, appVm.onboarded.value)

        val vm = vms.own(OnboardingViewModel(store))
        assertEquals(OnboardingStep.Welcome, vm.uiState.value.step)
        vm.next()
        assertEquals(OnboardingStep.Assistant, vm.uiState.value.step)
        vm.back()
        assertEquals(OnboardingStep.Welcome, vm.uiState.value.step)
        vm.next()
        vm.next()
        assertEquals(OnboardingStep.Done, vm.uiState.value.step)

        vm.complete()
        assertEquals(true, appVm.onboarded.value)
    }

    @Test
    fun app_skipCompletesFromAnyStep() = vmTest { vms ->
        val store = InMemoryOnboardingStore()
        val appVm = vms.own(MiaAppViewModel(store))
        vms.own(OnboardingViewModel(store)).complete()
        assertEquals(true, appVm.onboarded.value)
    }

    @Test
    fun history_listsSeededSessionsNewestFirstAndResolvesById() = vmTest { vms ->
        val vm = vms.own(MiaHistoryViewModel(MockConversationRepository()))
        val state = vm.uiState.value
        assertTrue(state.loaded)
        assertEquals(2, state.conversations.size)
        assertEquals(
            state.conversations.sortedByDescending { it.startedAt },
            state.conversations,
        )
        val first = state.conversations.first()
        assertEquals(first, vm.conversation(first.id))
    }

    // ── Launch announce (docs/tech-debt.md 2026-08-14) ────────────────────────────────────────

    @Test
    fun theAppAnnouncesItselfAtLaunch_withoutAnyUserInteraction() = vmTest { vms ->
        // The defect this pins: registration used to ride the session's FIRST CALL, and Mia's chat
        // issues none until you type — so an installed, connected, perfectly working Mia was
        // invisible in Control until its first message. Constructing the root is the whole trigger;
        // no screen is opened and no message is sent below.
        var announces = 0
        vms.own(MiaAppViewModel(InMemoryOnboardingStore(), LaunchAnnouncer { announces++ }))
        advanceUntilIdle()

        assertEquals(1, announces)
    }

    @Test
    fun anUnreachableControlDoesNotBreakTheLaunch() = vmTest { vms ->
        // `announce()` throws a typed error when Control is down or refuses admission. Mia is
        // designed to start standalone and say so per turn, so the launch must survive it — and the
        // preflight still retries ahead of the first real call.
        val model = vms.own(MiaAppViewModel(
            InMemoryOnboardingStore(),
            LaunchAnnouncer { error("Control is not running") },
        ))
        advanceUntilIdle()

        assertEquals(false, model.onboarded.value)
    }

    @Test
    fun noAnnouncerBoundIsNotAFailure() = vmTest { vms ->
        // The not-connected shell, tests and previews bind no client. Absence means "nothing to
        // announce to", never an error.
        val model = vms.own(MiaAppViewModel(InMemoryOnboardingStore()))
        advanceUntilIdle()

        assertEquals(false, model.onboarded.value)
    }

}
