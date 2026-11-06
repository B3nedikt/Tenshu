package org.tenshu.inference.proxy.onnx

/**
 * Unicode Normalization Form KC of [text] — the one step of [SiglipTextTokenizer] a platform
 * supplies.
 *
 * ⚠⚠ **Each actual is its platform's own Unicode tables**, so two platforms can disagree on a
 * character one of them is too old to know. A disagreement is a different query vector with
 * nothing to compare it against, which is why `NfkcParityTest` runs one table on every platform.
 */
internal expect fun nfkc(text: String): String
