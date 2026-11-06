package app.tenshu.control.data

/**
 * Test helper. Lets the test pre-program the next enroll/unwrap
 * outcome and inspect the wrap-key bytes the VM produced for
 * round-trip assertions.
 */
class FakeBiometricUnlockDelegate(
    var available: Boolean = true,
    var enrolled: Boolean = false,
    var nextEnrollResult: EnrollResult = EnrollResult.Success,
    var nextUnwrapResult: UnwrapResult = UnwrapResult.NotAvailable,
) : BiometricUnlockDelegate {

    /** wrap-key passed into the most recent [enrollWrapKey] call. */
    var lastEnrolledWrapKey: ByteArray? = null
        private set

    /** Count of [enrollWrapKey] / [unwrapWrapKey] / [clear] calls. */
    var enrollCalls: Int = 0
        private set
    var unwrapCalls: Int = 0
        private set
    var clearCalls: Int = 0
        private set

    override suspend fun isAvailable(): Boolean = available

    override suspend fun hasEnrolled(): Boolean = enrolled

    override suspend fun enrollWrapKey(
        wrapKey: ByteArray,
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): EnrollResult {
        enrollCalls += 1
        lastEnrolledWrapKey = wrapKey
        val result = nextEnrollResult
        if (result is EnrollResult.Success) {
            enrolled = true
        }
        return result
    }

    override suspend fun unwrapWrapKey(
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): UnwrapResult {
        unwrapCalls += 1
        return nextUnwrapResult
    }

    override fun clear() {
        clearCalls += 1
        enrolled = false
    }
}
