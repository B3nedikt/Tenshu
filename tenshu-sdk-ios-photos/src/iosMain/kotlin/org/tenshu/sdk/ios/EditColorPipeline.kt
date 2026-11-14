@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.tenshu.sdk.ios

import app.tenshu.photos.edit.ColorStage
import app.tenshu.photos.edit.EditColor
import app.tenshu.photos.edit.EditRecipe
import app.tenshu.photos.edit.EditShader
import app.tenshu.photos.edit.FilterLook
import app.tenshu.photos.edit.NormRect
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreImage.CIColorKernel
import platform.CoreImage.CIFilter
import platform.CoreImage.CIImage
import platform.CoreImage.CIVector
import platform.CoreImage.filterWithName
import platform.CoreImage.kCIInputImageKey
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.dataWithBytes
import platform.Foundation.numberWithFloat
import platform.Foundation.numberWithInt
import platform.Foundation.setValue

/**
 * Adjust + Filter on Core Image (`photos-editing.md` ED-D8) — the iOS shell's colour stages, called
 * from `EditRendering.swift` after it has drawn the transform.
 *
 * ⚠⚠ In Kotlin rather than Swift so the iOS test (`EditColorPipelineParityTest`) runs **this** chain
 * against the CPU reference — the golden test pins what ships, not a copy of it.
 *
 * The chain, in [EditRecipe]'s order: Core Image's linear working values → sRGB-encoded (the space
 * the shader's math is defined in) → the shared adjust kernel ([EditShader.CORE_IMAGE]) → the look as
 * a `CIColorCube` of the same 8-bit lattice the Skia engines sample, faded by `CIMix` → back to linear.
 */
object EditColorPipeline {

    /** Compiled once. `null` if this OS no longer compiles CIKL — the save then fails, said, never unadjusted. */
    private val kernel: CIColorKernel? by lazy {
        @Suppress("DEPRECATION")
        CIColorKernel.kernelWithString(EditShader.CORE_IMAGE)
    }

    private val cubes: Map<FilterLook, Lazy<NSData>> = FilterLook.entries.associateWith { look ->
        lazy {
            val floats = EditColor.lut(look).toCubeFloats()
            floats.usePinned { NSData.dataWithBytes(it.addressOf(0), (floats.size * 4).toULong()) }
        }
    }

    /**
     * [image] — its extent at the origin, [width]×[height] — with [recipe]'s colour stages, the
     * vignette centred on [vignette] (normalised, y down, as the shared code measures it). `null` if
     * Core Image refused; the caller must not fall back to the unadjusted picture.
     */
    fun apply(image: CIImage, recipe: EditRecipe, width: Int, height: Int, vignette: NormRect = NormRect.FULL): CIImage? =
        apply(image, recipe.colorStage(vignette), width, height)

    fun apply(image: CIImage, stage: ColorStage, width: Int, height: Int): CIImage? {
        if (stage.isIdentity) return image
        val k = kernel ?: return null
        val encoded = filter("CILinearToSRGBToneCurve", image) ?: return null
        val u = EditColor.uniforms(stage.adjust)
        val v = EditColor.vignetteUniforms(stage.vignette, width, height)
        // ⚠ Core Image is y-up: the vignette's centre flips about the height. It is radial, so
        // nothing else moves.
        val args = listOf(
            encoded,
            vec(u[0], u[1], u[2], u[3]),
            vec(u[4], u[5], u[6], u[7]),
            vec(u[8], u[9], u[10], u[11]),
            vec(v[0], height - v[1], v[2], v[3]),
        )
        var out: CIImage = k.applyWithExtent(encoded.extent, args) ?: return null
        val look = stage.filter?.takeIf { it.intensity > 0 }
        if (look != null) {
            val cube = CIFilter.filterWithName("CIColorCube") ?: return null
            cube.setValue(NSNumber.numberWithInt(EditColor.LUT_SIZE), forKey = "inputCubeDimension")
            cube.setValue(cubes.getValue(look.look).value, forKey = "inputCubeData")
            cube.setValue(out, forKey = kCIInputImageKey)
            val looked = cube.outputImage ?: return null
            val mix = CIFilter.filterWithName("CIMix") ?: return null
            mix.setValue(looked, forKey = kCIInputImageKey)
            mix.setValue(out, forKey = "inputBackgroundImage")
            mix.setValue(NSNumber.numberWithFloat(look.intensity / 100f), forKey = "inputAmount")
            out = mix.outputImage ?: return null
        }
        return filter("CISRGBToneCurveToLinear", out)
    }

    private fun filter(name: String, input: CIImage): CIImage? {
        val f = CIFilter.filterWithName(name) ?: return null
        f.setValue(input, forKey = kCIInputImageKey)
        return f.outputImage
    }

    private fun vec(x: Float, y: Float, z: Float, w: Float): CIVector =
        CIVector.vectorWithX(x.toDouble(), Y = y.toDouble(), Z = z.toDouble(), W = w.toDouble())
}
