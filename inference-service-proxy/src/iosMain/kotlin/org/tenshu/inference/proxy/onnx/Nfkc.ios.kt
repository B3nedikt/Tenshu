package org.tenshu.inference.proxy.onnx

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.autoreleasepool
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCompatibilityMapping
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Foundation.stringByApplyingTransform

/**
 * NFKC through ICU's own normaliser, which Foundation reaches by transform id.
 *
 * ⚠⚠ **Not `precomposedStringWithCompatibilityMapping`, which is named NFKC and is not it.**
 * Measured on the simulator against `java.text.Normalizer` over every code point and 11 200
 * sequences (`ios-on-device-inference.md` §10.3): it does not compose what a compatibility mapping
 * leaves composable, so half-width `ｶﾞ` stays `カ` + a combining mark where NFKC is `ガ`, and it
 * leaves a Hangul syllable followed by a trailing consonant uncomposed. The transform agrees with
 * the JVM on all of them. `NfkcParityTest` holds four of those rows.
 *
 * ⚠ The pool: the result is autoreleased, and a query is embedded on a worker thread with no run
 * loop to drain it.
 */
@OptIn(BetaInteropApi::class)
@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun nfkc(text: String): String = autoreleasepool {
    val source = text as NSString
    source.stringByApplyingTransform(ICU_NFKC, reverse = false)
        // ⚠ `nil` is documented for a transform that cannot be applied, and this id is ICU's own.
        // NFKC by its definition, then: decompose by compatibility, compose canonically. Right
        // everywhere the transform was except three Kirat Rai vowels (§10.3).
        ?: (source.decomposedStringWithCompatibilityMapping as NSString).precomposedStringWithCanonicalMapping
}

private const val ICU_NFKC: String = "Any-NFKC"
