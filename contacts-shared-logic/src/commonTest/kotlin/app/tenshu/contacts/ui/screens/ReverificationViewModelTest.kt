package app.tenshu.contacts.ui.screens

import app.tenshu.contacts.data.MockContactsRepository
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.ContactIdentity
import app.tenshu.contacts.model.TrustState
import app.tenshu.contacts.model.Verification
import app.tenshu.contacts.ui.screens.detail.ContactDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * Re-verification from the screen's side (`contacts-app.md` D4/Q2a/Q2b).
 *
 * The through-line: **a key only becomes verified because a person said the digits matched.**
 * Every test here is a way that could go wrong — confirming with nothing on screen, a code
 * arriving for a sheet the user already closed, or a local verdict overwriting an
 * attestation.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReverificationViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val saraOldKey = "7e5f0c39a1bb24d806fe93cc5a17d240b8e6f091"
    private val saraNewKey = "20a7cc61d4e93b05f8172ba0ce3d9f4471e02b8d"
    private val miraKey = "3ab19c7f2e08d641b5c0aa93e77f10d2846bc5e1"

    @Test
    fun openingTheSheetFetchesTheCode() = vmTest { vms ->
        val vm = vms.own(ContactDetailViewModel(MockContactsRepository(), personId = "person-sara"))

        vm.onVerifyClicked(saraNewKey)

        val prompt = assertNotNull(vm.uiState.value.verifying)
        assertEquals(saraNewKey, prompt.fingerprint)
        assertTrue(prompt.hasSas, "the six digits are what the user compares")
        assertFalse(prompt.loading)
    }

    @Test
    fun confirmingResolvesTheKeyChangedDemotion() = vmTest { vms ->
        // ⚠ The Q2a → Q2b round trip. Sara reads as "key changed" because her new key is
        // unverified beside an old verified one; comparing the code on the new key is the
        // remedy that keeps both.
        val repository = MockContactsRepository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-sara"))
        assertEquals(TrustState.NeedsReverification, vm.uiState.value.contact?.trust)

        vm.onVerifyClicked(saraNewKey)
        vm.onVerifyConfirmed()

        val sara = repository.contacts().first().single { it.personId == "person-sara" }
        assertEquals(2, sara.identities.size, "verifying keeps the keyring intact")
        assertEquals(TrustState.Verified, sara.trust)
        assertNull(vm.uiState.value.verifying, "the sheet closes on success")
    }

    @Test
    fun confirmingWithNoCodeOnScreenDoesNothing() = vmTest { vms ->
        // ⚠⚠ The load-bearing guard. A confirm that fires while the digits are absent —
        // still loading, or a locked realm — records a comparison that never happened.
        val repository = MockContactsRepository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-mira"))
        // A key that belongs to nobody yields no code, exactly as a locked realm would.
        vm.onVerifyClicked("not-a-key")
        assertFalse(assertNotNull(vm.uiState.value.verifying).hasSas)

        vm.onVerifyConfirmed()

        val mira = repository.contacts().first().single { it.personId == "person-mira" }
        assertEquals(
            Verification.VERIFIED,
            mira.identities.single().verification,
            "Mira's own key is untouched — nothing was verified by that tap",
        )
    }

    @Test
    fun dismissingBeforeTheCodeArrivesLeavesNothingBehind() = vmTest { vms ->
        val vm = vms.own(ContactDetailViewModel(MockContactsRepository(), personId = "person-sara"))

        vm.onVerifyClicked(saraNewKey)
        vm.onVerifyDismissed()

        assertNull(vm.uiState.value.verifying)
    }

    @Test
    fun unVerifyingWithdrawsTrustWithoutTouchingTheKey() = vmTest { vms ->
        // Q2b's way back. Withdrawing is the safe direction, so it needs no confirmation
        // step — but it must not remove the key, only the claim about it.
        val repository = MockContactsRepository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-mira"))

        vm.onUnverifyClicked(miraKey)

        val mira = repository.contacts().first().single { it.personId == "person-mira" }
        assertEquals(1, mira.identities.size, "the key stays")
        assertEquals(Verification.UNVERIFIED, mira.identities.single().verification)
        assertEquals(TrustState.Unverified, mira.trust)
    }

    @Test
    fun verifyingTheOldKeyAlsoResolvesTheDemotion_bothDirectionsAreOffered() = vmTest { vms ->
        // Either key can be the one you compare — the demotion is about the keyring being
        // mixed, not about which row is "current" (which nothing can tell).
        val repository = MockContactsRepository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-sara"))

        vm.onUnverifyClicked(saraOldKey)

        val sara = repository.contacts().first().single { it.personId == "person-sara" }
        assertEquals(TrustState.Unverified, sara.trust, "no longer mixed, and honestly so")
    }

    @Test
    fun anAttestedKeyIsNeverMovedByALocalVerdict() = vmTest { vms ->
        // ⚠ ATTESTED is Rei-carried. The mock mirrors the proxy here on purpose: if the
        // two disagreed, the screen would be built against a rule the service does not have.
        val repository = MockContactsRepository(
            initial = listOf(
                Contact(
                    personId = "person-rei",
                    displayName = "Rei",
                    identities = listOf(
                        ContactIdentity(
                            fingerprint = "attested-key",
                            selfAssertedName = "Rei",
                            verification = Verification.ATTESTED,
                        ),
                    ),
                ),
            ),
        )
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-rei"))

        vm.onUnverifyClicked("attested-key")

        assertEquals(
            Verification.ATTESTED,
            repository.contacts().first().single().identities.single().verification,
        )
    }
}
