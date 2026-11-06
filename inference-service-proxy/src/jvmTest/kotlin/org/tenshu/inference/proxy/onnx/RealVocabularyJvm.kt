package org.tenshu.inference.proxy.onnx

import java.io.File

internal actual fun realVocabularyBytes(): ByteArray? =
    System.getProperty("tenshu.inference.tokenizer")?.let(::File)?.takeIf { it.isFile }?.readBytes()
