package org.tenshu.inference.proxy.harness

import java.awt.image.BufferedImage
import org.tenshu.inference.proxy.onnx.AwtImageDecoder
import org.tenshu.inference.proxy.onnx.ImagePreprocessing

/**
 * The desktop harness's decode-and-resize (`inference-service.md` §13, §15.2).
 *
 * ⚠⚠ **Both halves are now shipped code, and that is the point.** The resize is
 * [AwtImageDecoder.pixelsOf] and the arithmetic is [ImagePreprocessing.preprocessPixels] — the same
 * two the desktop backend runs, so a number this harness produces describes what a device does. A
 * harness with its own copy of either half measures a program nobody ships.
 */
fun preprocess(
    image: BufferedImage,
    size: Int = 224,
    profile: ImagePreprocessing.Profile = ImagePreprocessing.Profile.SIGLIP,
): FloatArray = ImagePreprocessing.preprocessPixels(
    AwtImageDecoder.pixelsOf(image, size, profile.centreCrop),
    size,
    profile,
)
