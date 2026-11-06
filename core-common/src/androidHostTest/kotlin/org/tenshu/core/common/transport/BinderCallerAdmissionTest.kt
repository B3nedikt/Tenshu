package org.tenshu.core.common.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Control serves first-party Tenshu apps and nothing else (threat model T-C-20). ⚠ `ProxyService`'s
 * stub cannot be driven on the host (the host `android.jar` has no working `Binder.getCallingUid()`),
 * so these pin the decision every one of its methods takes its client id from.
 */
class BinderCallerAdmissionTest {

    private var now = 0L
    private var lookups = 0
    private val refusals = mutableListOf<Pair<Int, List<String>>>()

    /** uid → the packages the package manager says it holds. */
    private val installed = mutableMapOf(
        DRIVE_UID to arrayOf(DRIVE),
        STRANGER_UID to arrayOf("com.example.flashlight"),
        IMPOSTOR_UID to arrayOf(MAPS),
    )

    /** Packages whose signer passes [FirstPartySigning]. The impostor holds Maps' name, not its key. */
    private val genuinelySigned = mutableSetOf(DRIVE, CONTROL)

    private val admission = BinderCallerAdmission(
        selfUid = CONTROL_UID,
        selfPackage = CONTROL,
        packagesForUid = { uid -> lookups++; installed[uid] },
        isFirstParty = { it in genuinelySigned },
        onRefused = { uid, packages -> refusals += uid to packages },
        nanoTime = { now },
    )

    @Test
    fun aFirstPartyCallerIsAdmittedAsItsPackage() {
        assertEquals(DRIVE, admission.admittedPackage(DRIVE_UID))
        assertEquals(DRIVE, admission.requireAdmitted(DRIVE_UID, "call"))
    }

    @Test
    fun aForeignCallerIsRefused() {
        assertNull(admission.admittedPackage(STRANGER_UID))
        val refused = assertFailsWith<SecurityException> { admission.requireAdmitted(STRANGER_UID, "call") }
        assertEquals("call: uid $STRANGER_UID is not a first-party Tenshu app", refused.message)
    }

    /** ⚠⚠ The name is not the identity: a sideloaded app can take any name that is not installed. */
    @Test
    fun anAppHoldingAFirstPartyNameButNotItsKeyIsRefused() {
        assertNull(admission.admittedPackage(IMPOSTOR_UID))
        assertEquals(listOf(IMPOSTOR_UID to listOf(MAPS)), refusals)
    }

    /** A signer check alone would admit a first-party-signed package Control does not know. */
    @Test
    fun aPackageOutsideTheFirstPartySetIsRefusedWhateverItsSigner() {
        installed[STRANGER_UID] = arrayOf("app.tenshu.notes.android")
        genuinelySigned += "app.tenshu.notes.android"
        assertNull(admission.admittedPackage(STRANGER_UID))
    }

    @Test
    fun aUidThatNamesNoPackageIsRefused() {
        assertNull(admission.admittedPackage(4_242))
    }

    @Test
    fun controlItselfIsAdmittedWithoutALookup() {
        assertEquals(CONTROL, admission.admittedPackage(CONTROL_UID))
        assertEquals(0, lookups)
    }

    /** A shared uid answers several packages; the first-party one is the identity, not the first. */
    @Test
    fun theFirstPartyPackageOfASharedUidIsTheOneServed() {
        installed[DRIVE_UID] = arrayOf("com.example.other", DRIVE)
        assertEquals(DRIVE, admission.admittedPackage(DRIVE_UID))
    }

    @Test
    fun anAdmittedCallerIsAnsweredFromTheCache() {
        repeat(1_000) { assertEquals(DRIVE, admission.admittedPackage(DRIVE_UID)) }
        assertEquals(1, lookups)
    }

    /** ⚠ Each refusal must not be a package-manager call, nor a log line. */
    @Test
    fun aStrangerCannotMakeEveryCallALookup() {
        repeat(1_000) { assertNull(admission.admittedPackage(STRANGER_UID)) }
        assertEquals(1, lookups)
        assertEquals(1, refusals.size)

        now += BinderCallerAdmission.REFUSED_TTL_NANOS
        assertNull(admission.admittedPackage(STRANGER_UID))
        assertEquals(2, lookups, "at most one lookup per uid per refusal window")
    }

    /**
     * ⚠⚠ Below API 33 an uninstalled app's uid can go to the next app installed. An admission must
     * not outlive the app it was made for: neither past its window nor past the last unbind.
     */
    @Test
    fun anAdmissionDoesNotOutliveTheAppItWasFor() {
        assertEquals(DRIVE, admission.admittedPackage(DRIVE_UID))
        installed[DRIVE_UID] = arrayOf("com.example.flashlight")

        now += BinderCallerAdmission.ADMITTED_TTL_NANOS
        assertNull(admission.admittedPackage(DRIVE_UID), "re-asked once the window closed")

        installed[DRIVE_UID] = arrayOf(DRIVE)
        now += BinderCallerAdmission.REFUSED_TTL_NANOS
        assertEquals(DRIVE, admission.admittedPackage(DRIVE_UID))
        installed[DRIVE_UID] = arrayOf("com.example.flashlight")
        admission.forgetAdmitted()
        assertNull(admission.admittedPackage(DRIVE_UID), "re-asked after every binding went away")
    }

    private companion object {
        const val CONTROL = FirstPartySigning.CONTROL_PACKAGE
        const val DRIVE = "app.tenshu.drive.android"
        const val MAPS = "app.tenshu.maps.android"
        const val CONTROL_UID = 10_100
        const val DRIVE_UID = 10_200
        const val STRANGER_UID = 10_300
        const val IMPOSTOR_UID = 10_400
    }
}
