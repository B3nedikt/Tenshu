package app.tenshu.control.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The raise policy for the macOS keychain helper, kept honest against
 * `control-desktop/macos/KeychainHelper/main.swift` — which is the only source of truth for which
 * outcomes have a sheet behind them.
 *
 * ⚠⚠ **The asymmetry is the point.** Missing a raise leaves a known bug in one path; a spurious
 * raise is a *new* bug that steals focus from a user who was never interrupted — and it would land
 * on the common desktop configuration, since ad-hoc signing makes `enroll` fail before it prompts.
 * So the interesting cases here are the `false` ones.
 *
 * ⚠ `failure:*` is the case that is **not** uniform across verbs, and reading it as uniform is the
 * mistake this test exists to catch: for `authenticate` it means `evaluatePolicy` rejected the user
 * *after* showing the sheet (a fumbled fingerprint — the raise matters most there), while for
 * `enroll` and `unwrap` it means `Security.framework` refused before presenting anything.
 */
class HelperSheetPolicyTest {

    @Test
    fun authenticate_everythingPastTheCanEvaluateGuardWasOnScreen() {
        assertTrue(helperSheetWasPresented("authenticate", "ok"))
        assertTrue(helperSheetWasPresented("authenticate", "cancelled"))
        assertTrue(
            helperSheetWasPresented("authenticate", "failure:biometry lockout"),
            "a fumbled fingerprint is a failure: — the sheet was up and took activation",
        )
        assertFalse(
            helperSheetWasPresented("authenticate", "unavailable"),
            "the canEvaluatePolicy guard emits this before anything is presented",
        )
    }

    @Test
    fun enroll_failsBeforeThePromptUnderAdHocSigning() {
        assertTrue(helperSheetWasPresented("enroll", "ok"))
        assertTrue(helperSheetWasPresented("enroll", "cancelled"))
        assertFalse(
            helperSheetWasPresented("enroll", "failure:missing entitlement"),
            "SecItemAdd refuses before prompting — the common desktop config, not an edge",
        )
        assertFalse(helperSheetWasPresented("enroll", "failure:invalid wrap-key on stdin"))
        assertFalse(helperSheetWasPresented("enroll", "unavailable"))
    }

    @Test
    fun unwrap_onlyARealMatchOrACancelHadASheet() {
        assertTrue(helperSheetWasPresented("unwrap", "ok:AAAAAAAAAAAAAAAAAAAAAA=="))
        assertTrue(helperSheetWasPresented("unwrap", "cancelled"))
        assertFalse(
            helperSheetWasPresented("unwrap", "not_found"),
            "no item means nothing to authenticate against",
        )
        assertFalse(
            helperSheetWasPresented("unwrap", "invalidated"),
            "an ACL that no longer matches is refused without prompting",
        )
        assertFalse(helperSheetWasPresented("unwrap", "unavailable"))
        assertFalse(helperSheetWasPresented("unwrap", "failure:boom"))
    }

    @Test
    fun noAnswerNeverRaises_andNeitherDoTheMetadataVerbs() {
        for (verb in listOf("authenticate", "enroll", "unwrap")) {
            assertFalse(
                helperSheetWasPresented(verb, null),
                "'$verb' with no answer conflates a helper that never started with one that timed " +
                    "out; neither is worth stealing focus for",
            )
        }
        // Present nothing by construction — and an unknown verb must answer false, not crash.
        for (verb in listOf("is-available", "has-enrolled", "clear", "something-new")) {
            assertFalse(helperSheetWasPresented(verb, "ok"))
        }
    }
}
