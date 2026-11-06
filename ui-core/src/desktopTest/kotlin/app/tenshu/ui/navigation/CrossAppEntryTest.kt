package app.tenshu.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/** CO-D11 — what a back stack looks like after another app names a row. */
class CrossAppEntryTest {

    @Test
    fun theTargetIsTheOnlyEntry() {
        // ⚠⚠ The claim worth pinning: nothing is left underneath. `[Home, FilePreview]` is what
        // made back land on Drive's Home instead of returning to the app that sent the user.
        val backStack = mutableListOf("Home")
        backStack.enterFromAnotherApp("FilePreview(/Camera/IMG_1.jpg)")
        assertEquals(listOf("FilePreview(/Camera/IMG_1.jpg)"), backStack)
    }

    @Test
    fun anAppThatWasAlreadyDeepInItsOwnHistoryIsAlsoReplaced() {
        // The cost, asserted so it is a decision rather than an accident: a warm app loses its
        // place. One rule, one behaviour — a stack that is sometimes replaced and sometimes pushed
        // makes back mean two different things on the same screen.
        val backStack = mutableListOf("Home", "Files", "Folder(/Backup)")
        backStack.enterFromAnotherApp("FilePreview(/Camera/IMG_1.jpg)")
        assertEquals(listOf("FilePreview(/Camera/IMG_1.jpg)"), backStack)
    }

    @Test
    fun aSecondCrossAppOpenDoesNotStack() {
        // Two taps in a row must not build a history of other apps' requests: each one is its own
        // entry point, and back after the second should still leave the app.
        val backStack = mutableListOf("Home")
        backStack.enterFromAnotherApp("FilePreview(/a.jpg)")
        backStack.enterFromAnotherApp("FilePreview(/b.jpg)")
        assertEquals(listOf("FilePreview(/b.jpg)"), backStack)
    }

    @Test
    fun backOffTheCrossAppEntryLeavesTheAppInsteadOfEmptyingTheStack() {
        // ⚠⚠ The crash this guards: `NavDisplay` requires a non-empty back stack, and after
        // CO-D11 the bottom entry is a detail screen with a back arrow on it.
        val backStack = mutableListOf("FilePreview(/a.jpg)")
        var left = 0
        backStack.popOrLeaveApp { left++ }
        assertEquals(listOf("FilePreview(/a.jpg)"), backStack, "the stack must never empty")
        assertEquals(1, left)
    }

    @Test
    fun anOrdinaryBackStillPops() {
        val backStack = mutableListOf("Home", "Files", "FilePreview(/a.jpg)")
        var left = 0
        backStack.popOrLeaveApp { left++ }
        assertEquals(listOf("Home", "Files"), backStack)
        assertEquals(0, left, "there was somewhere to go back to — do not leave the app")
    }

    @Test
    fun aPlatformWithNoExitIsAQuietNoOp() {
        // Desktop and iOS pass `null` (see `rememberAppExit`). ⚠ It must not fall through to a pop:
        // that is exactly the empty stack the guard exists for.
        val backStack = mutableListOf("MomentDetail(m-7)")
        backStack.popOrLeaveApp(null)
        assertEquals(listOf("MomentDetail(m-7)"), backStack)
    }
}
