package org.tenshu.inference.proxy.onnx

/**
 * The pinned checkpoint's packed vocabulary, or `null` when this run was not handed one.
 *
 * ⚠ 3.1 MB, side-loaded and never committed (the repo is public). The JVM reads the path from
 * `-Dtenshu.inference.tokenizer`; the iOS simulator run gets the same path from Gradle in
 * `TENSHU_INFERENCE_TOKENIZER` (`inference-service-proxy/build.gradle.kts`).
 */
internal expect fun realVocabularyBytes(): ByteArray?
