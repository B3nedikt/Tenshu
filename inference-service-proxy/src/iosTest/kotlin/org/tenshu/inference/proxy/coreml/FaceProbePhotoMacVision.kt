package org.tenshu.inference.proxy.coreml

import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.VisionFaceLandmarks

/**
 * **macOS** Vision's answer for the bundled face ([FaceProbePhoto]), revision 3, recorded on the build
 * Mac (`ios-face-grouping.md` §14.4) by F1a's `vision_detect.swift`: pixels, bottom-left origin.
 *
 * ⚠⚠ Why a recording: the iOS **simulator**'s Vision finds the face's box and returns landmarks that
 * are not the face's (both pupils within 6 px of each other, on a 140 px face), with or without
 * `usesCPUOnly`. A device's Vision is the product's; this stands in for it on the simulator only, to
 * measure the host face margin and run the preflight end to end. Never a golden of Vision (§11.3).
 */
internal object FaceProbePhotoMacVision {
    const val WIDTH: Int = 254
    const val HEIGHT: Int = 256

    val box = FaceBox(left = 0.223884f, top = 0.393138f, right = 0.804958f, bottom = 0.969672f, confidence = 1f)

    val regions = VisionFaceLandmarks.Regions(
        leftEye = floatArrayOf(86.8603f, 118.1817f, 93.7482f, 121.8766f, 104.1905f, 119.8685f, 110.4157f, 114.5269f, 103.0258f, 114.4466f, 93.1859f, 114.6875f),
        rightEye = floatArrayOf(169.8964f, 114.0048f, 162.3458f, 118.4628f, 151.1003f, 117.6596f, 144.3530f, 112.6794f, 152.2248f, 111.7958f, 162.7474f, 111.0328f),
        leftPupil = floatArrayOf(96.1580f, 119.1054f),
        rightPupil = floatArrayOf(157.7673f, 115.8523f),
        nose = floatArrayOf(124.3922f, 101.0724f, 111.1787f, 83.8026f, 109.8132f, 70.9506f, 116.6007f, 67.7376f, 123.1873f, 64.2836f, 131.5813f, 66.7737f, 140.0154f, 68.3802f, 140.0154f, 81.8748f),
        noseCrest = floatArrayOf(125.7176f, 115.5711f, 124.3922f, 101.0724f, 123.2275f, 86.1722f, 122.1431f, 71.6735f, 113.7893f, 74.0029f, 133.9911f, 73.0390f),
        outerLips = floatArrayOf(110.0542f, 50.7890f, 115.2753f, 49.5038f, 120.1350f, 48.3792f, 124.5529f, 46.8530f, 129.3322f, 47.4153f, 135.7582f, 47.2547f, 142.6662f, 47.3350f, 149.6544f, 47.6563f, 142.1842f, 43.1581f, 133.3485f, 40.2664f, 124.3521f, 39.9451f, 116.5204f, 42.0335f, 109.7731f, 46.3711f, 104.3110f, 51.9938f),
    )
}
