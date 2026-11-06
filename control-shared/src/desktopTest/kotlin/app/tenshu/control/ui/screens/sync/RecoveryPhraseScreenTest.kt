package app.tenshu.control.ui.screens.sync

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.tenshu.control.data.AppLockAuthResult
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.ReauthPromptCopy
import app.tenshu.control.data.Reauthenticator
import app.tenshu.control.data.SyncRepository
import app.tenshu.ui.theme.TenshuTheme
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.tenshu.core.TString
import org.tenshu.core.common.impl.TStringDto
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.core.TResult
import kotlin.test.Test

/**
 * The recovery-phrase screen must tell the truth about whether **this** device has the phrase.
 *
 * ⚠⚠ The bug this pins was found by a user, not by a test: on a device with no stored phrase the
 * reveal button did nothing at all, forever, with no message. The render collapsed "hidden" and
 * "absent" into one branch (`if (isVisible && phrase != null) … else placeholder`), so tapping the
 * eye toggled a flag that changed nothing.
 *
 * That is the worst place in the app for a silent failure: a device added by pairing **never**
 * receives the phrase, so the state is common rather than exotic, and the phrase is the only thing
 * that survives losing every device. A user who taps and sees nothing concludes the feature is
 * broken, not that they need to look on another device — and stops looking.
 */
class RecoveryPhraseScreenTest {

    private val fakePreferences = FakeAffordancePreferences()

    private fun startKoinWith(
        phrase: String?,
        // Ph-reauth: Success is the default so the pre-existing cases keep asserting what they
        // were written to assert — the reveal itself, not the gate in front of it.
        reauth: AppLockAuthResult = AppLockAuthResult.Success,
    ): FakeReauthenticator {
        val reauthenticator = FakeReauthenticator(reauth)
        stopKoin()
        startKoin {
            modules(
                module {
                    single<SyncRepository> { FakeRecoveryRepository(phrase) }
                    single<app.tenshu.control.data.RecoveryAffordancePreferences> { fakePreferences }
                    single<Reauthenticator> { reauthenticator }
                },
            )
        }
        return reauthenticator
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aDeviceWithNoPhrase_saysSo_ratherThanRenderingADeadButton() = runComposeUiTest {
        startKoinWith(null)

        setContent { TenshuTheme { RecoveryPhraseScreen(onBack = {}) } }
        waitForIdle()

        onNodeWithText("This device doesn't have your recovery phrase").assertIsDisplayed()
    }

    /**
     * ⚠ The control is *absent*, not merely disabled: a greyed-out eye still invites the tap that
     * taught the user the screen was broken. Asserting on its absence is what makes this a guard
     * against the original bug rather than a restatement of it.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aDeviceWithNoPhrase_offersNoRevealControlAtAll() = runComposeUiTest {
        startKoinWith(null)

        setContent { TenshuTheme { RecoveryPhraseScreen(onBack = {}) } }
        waitForIdle()

        val revealNodes = onNodeWithContentDescription("Show").let {
            runCatching { it.assertIsDisplayed(); true }.getOrDefault(false)
        }
        kotlin.test.assertFalse(
            revealNodes,
            "no phrase means no reveal button — a dead control is how this bug reached a user",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aDeviceWithAPhrase_revealsItOnTap() = runComposeUiTest {
        val phrase = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        startKoinWith(phrase)

        setContent { TenshuTheme { RecoveryPhraseScreen(onBack = {}) } }
        waitForIdle()

        // Hidden until asked — the phrase must not be on screen by default.
        kotlin.test.assertFalse(
            runCatching { onNodeWithText(phrase).assertIsDisplayed(); true }.getOrDefault(false),
            "the phrase must start hidden",
        )

        onNodeWithContentDescription("Show").performClick()
        waitForIdle()

        onNodeWithText(phrase).assertIsDisplayed()
    }
    /**
     * ⭐ Closes the loop: the banner reads `phraseSaved`, and until this action existed nothing
     * wrote it — so the warning was dismissible for 72h but never resolvable.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun revealingThenAcknowledging_recordsThatThePhraseWasSaved() = runComposeUiTest {
        startKoinWith("abandon ability able about above absent absorb abstract absurd abuse access accident")

        setContent { TenshuTheme { RecoveryPhraseScreen(onBack = {}) } }
        waitForIdle()

        onNodeWithContentDescription("Show").performClick()
        waitForIdle()
        onNodeWithText("I have written this down").performClick()
        waitForIdle()

        kotlin.test.assertEquals(
            true,
            fakePreferences.lastWritten,
            "acknowledging must record it, or the banner can never be resolved",
        )
    }

    /**
     * ⚠⚠ THE guard. An acknowledgement offered before the reveal would let a user silence the
     * warning without ever seeing what they are meant to save — which is precisely the state that
     * cost a realm its master identity (§5b.11). Seeing it is the point.
     */
    /**
     * ⚠⚠ THE Ph-reauth guard (`control-keep-device-unlocked.md` §6.1). Before this, the recovery
     * phrase — the one secret that grants the whole realm — was one tap away behind no
     * authentication at all for any user who had turned the soft app-lock off.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun refusingTheReauthPrompt_keepsThePhraseHidden() = runComposeUiTest {
        val phrase = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        val reauthenticator = startKoinWith(phrase, reauth = AppLockAuthResult.Cancelled)

        setContent { TenshuTheme { RecoveryPhraseScreen(onBack = {}) } }
        waitForIdle()

        onNodeWithContentDescription("Show").performClick()
        waitForIdle()

        kotlin.test.assertEquals(1, reauthenticator.calls, "the reveal must ask")
        kotlin.test.assertFalse(
            runCatching { onNodeWithText(phrase).assertIsDisplayed(); true }.getOrDefault(false),
            "a refused prompt must leave the phrase concealed",
        )
    }

    /**
     * ⚠⚠ Copy is the *other* door out of concealment — it puts the phrase on the clipboard without
     * ever rendering it, so a gate on the eye alone would be theatre. This test is the reason the
     * screen gates the transition (concealed → disclosed) rather than the reveal control.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun copyingWhileConcealed_alsoAsks() = runComposeUiTest {
        val reauthenticator = startKoinWith(
            "abandon ability able about above absent absorb abstract absurd abuse access accident",
            reauth = AppLockAuthResult.Cancelled,
        )

        setContent { TenshuTheme { RecoveryPhraseScreen(onBack = {}) } }
        waitForIdle()

        onNodeWithContentDescription("Copy").performClick()
        waitForIdle()

        kotlin.test.assertEquals(1, reauthenticator.calls, "copy must ask while still concealed")
    }

    /**
     * ⚠⚠ The pass-through case, and the one that must never regress into failing closed: on a
     * device that cannot present any prompt the phrase stays reachable. Failing closed here would
     * not hide the phrase, it would make it unrecoverable on that device — and that is the
     * majority path on desktop today.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aDeviceThatCannotPrompt_stillRevealsThePhrase() = runComposeUiTest {
        val phrase = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        startKoinWith(phrase, reauth = AppLockAuthResult.Unavailable)

        setContent { TenshuTheme { RecoveryPhraseScreen(onBack = {}) } }
        waitForIdle()

        onNodeWithContentDescription("Show").performClick()
        waitForIdle()

        onNodeWithText(phrase).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theAcknowledgementIsNotOfferedBeforeTheReveal() = runComposeUiTest {
        startKoinWith("abandon ability able about above absent absorb abstract absurd abuse access accident")

        setContent { TenshuTheme { RecoveryPhraseScreen(onBack = {}) } }
        waitForIdle()

        kotlin.test.assertFalse(
            runCatching {
                onNodeWithText("I have written this down").assertIsDisplayed(); true
            }.getOrDefault(false),
            "no acknowledgement until the phrase has actually been shown",
        )
    }

}

/**
 * ⚠ Extends the shared [FakeSyncRepository] rather than hand-rolling a 29-method duplicate — only
 * `getRecoveryPhrase` matters to this screen, and a private copy would drift from the interface.
 */
private class FakeRecoveryRepository(private val phrase: String?) : FakeSyncRepository() {
    // ⚠ Widening SyncRepository broke every implementor — the interface change is
    // only 'additive' from the caller's side.
    override suspend fun forgetThisDevice(): TResult<TString> = error("not used")

    override suspend fun getRecoveryPhrase(): TResult<TString> =
        if (phrase == null) ResultSuccessDto(null) else ResultSuccessDto(TStringDto(phrase))
}

/** Records what the screen wrote, so the test asserts the effect rather than the rendering. */
private class FakeAffordancePreferences : app.tenshu.control.data.RecoveryAffordancePreferences {
    private val saved = kotlinx.coroutines.flow.MutableStateFlow(false)
    var lastWritten: Boolean? = null

    override fun observeDismissedUntilMs() = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override suspend fun setDismissedUntilMs(epochMs: Long) {}
    override fun observePhraseSaved() = saved
    override suspend fun setPhraseSaved(saved: Boolean) {
        lastWritten = saved
        this.saved.value = saved
    }
}
