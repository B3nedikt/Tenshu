package org.tenshu.inference.proxy.onnx

/** ⚠ No backend runs in a browser; this exists so `commonMain` has its actual on every target. */
internal actual fun nfkc(text: String): String = text.asDynamic().normalize("NFKC").unsafeCast<String>()
