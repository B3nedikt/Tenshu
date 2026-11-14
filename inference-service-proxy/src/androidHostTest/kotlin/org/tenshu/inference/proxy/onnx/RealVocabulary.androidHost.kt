package org.tenshu.inference.proxy.onnx

/** The packed vocabulary is a desktop-harness flag; the Android host run never has one. */
internal actual fun realVocabularyBytes(): ByteArray? = null
