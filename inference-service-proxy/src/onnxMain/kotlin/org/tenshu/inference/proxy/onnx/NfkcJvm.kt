package org.tenshu.inference.proxy.onnx

import java.text.Normalizer

/** ⚠ In `onnxMain` because that directory is the one compiled into both the JVM and Android artifacts. */
internal actual fun nfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
