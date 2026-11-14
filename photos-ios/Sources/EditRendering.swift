import CoreImage
import ImageIO
import TenshuSdk
import UIKit
import UniformTypeIdentifiers

// The iOS renderer for the photo editor (`photos-editing.md` §4: Core Image on iOS) — the transform
// here, Adjust + Filter through the shared `EditColorPipeline` (ED-D8), Markup through
// `MarkupDrawing` (ED-4).
//
// ⚠⚠ It draws the SHARED plan — `TransformEdit.plan`, the same 3×3 the Compose renderer draws — as
// four corner points through `CIPerspectiveTransform`. One matrix, two engines: what the preview lines
// up is what the save writes, on both platforms.
//
// ⚠⚠ Upright end to end: `.applyOrientationProperty` applies the EXIF tag at decode, and the encode
// writes orientation 1 and nothing else (`photos-creation-features.md` §9.1/§9.2).

enum EditRendering {
    /// One context for the app's life: creating one is the expensive part of Core Image.
    static let context = CIContext(options: [.cacheIntermediates: false])

    /// The largest edge a save keeps — Compose's `MAX_DECODE_EDGE`, so a copy is the same size on
    /// every platform.
    static let maxExportEdge: CGFloat = 4096

    /// The original, upright, its extent moved to the origin and scaled to fit [maxEdge].
    static func upright(_ data: Foundation.Data, maxEdge: CGFloat) -> CIImage? {
        guard var image = CIImage(data: data, options: [.applyOrientationProperty: true]) else { return nil }
        image = image.transformed(by: CGAffineTransform(translationX: -image.extent.minX, y: -image.extent.minY))
        let edge = max(image.extent.width, image.extent.height)
        if edge > maxEdge {
            let s = maxEdge / edge
            image = image.transformed(by: CGAffineTransform(scaleX: s, y: s))
            image = image.transformed(by: CGAffineTransform(translationX: -image.extent.minX, y: -image.extent.minY))
        }
        return image
    }

    /// Draw [edit] from [source]. `includeCrop: false` is the editor's whole-frame preview.
    static func draw(_ source: CIImage, edit: TransformEdit, includeCrop: Bool = true) -> CIImage? {
        let w = Int32(source.extent.width.rounded())
        let h = Int32(source.extent.height.rounded())
        guard w > 0, h > 0 else { return nil }
        let plan = edit.plan(sourceWidth: w, sourceHeight: h, includeCrop: includeCrop)
        let corners = plan.corners(sourceWidth: w, sourceHeight: h)
        let outH = CGFloat(plan.outputHeight)
        // ⚠ The plan is y-down; Core Image is y-up, so every y flips about the output's height.
        func point(_ i: Int32) -> CIVector {
            CIVector(x: CGFloat(corners.get(index: 2 * i)), y: outH - CGFloat(corners.get(index: 2 * i + 1)))
        }
        guard let filter = CIFilter(name: "CIPerspectiveTransform") else { return nil }
        filter.setValue(source, forKey: kCIInputImageKey)
        filter.setValue(point(0), forKey: "inputTopLeft")
        filter.setValue(point(1), forKey: "inputTopRight")
        filter.setValue(point(2), forKey: "inputBottomRight")
        filter.setValue(point(3), forKey: "inputBottomLeft")
        guard let warped = filter.outputImage else { return nil }
        // ⚠ Left transparent outside the picture: `onBlack` flattens it, AFTER the colour stages, so
        // no slider tints a straightened frame's empty wedges (the Skia shader leaves them black too).
        return warped.cropped(to: CGRect(x: 0, y: 0, width: CGFloat(plan.outputWidth), height: outH))
    }

    /// ⚠ JPEG has no alpha: a half-covered edge pixel is black either way, so say it.
    static func onBlack(_ image: CIImage) -> CIImage {
        image.composited(over: CIImage(color: .black).cropped(to: image.extent))
    }

    /// The colour stages (ED-2) over a drawn frame, in the recipe's order: the transform is already
    /// drawn; adjust then the look run in the shared Kotlin chain (`EditColorPipeline`), the one the
    /// iOS golden test pins. [vignette] is normalised to [drawn]: the whole frame for a save, the crop
    /// box for the uncropped preview. ⚠ `nil` when Core Image refuses — never the unadjusted picture.
    static func colour(_ drawn: CIImage, recipe: EditRecipe, vignette: NormRect) -> CIImage? {
        if recipe.colorIsIdentity { return drawn }
        return EditColorPipeline.shared.apply(
            image: drawn,
            recipe: recipe,
            width: Int32(drawn.extent.width.rounded()),
            height: Int32(drawn.extent.height.rounded()),
            vignette: vignette
        )
    }

    /// The save: the original's bytes in, an upright JPEG with no orientation tag out — or `nil`.
    ///
    /// ⚠ In [EditRecipe]'s order: a redaction's pixels destroyed in the source first, then the
    /// transform, the colour stages, and the markup last over the coloured picture (ED-4).
    static func render(original: Foundation.Data, recipe: EditRecipe) -> Foundation.Data? {
        guard var source = upright(original, maxEdge: maxExportEdge) else { return nil }
        let w = Int32(source.extent.width.rounded())
        let h = Int32(source.extent.height.rounded())
        let markup = recipe.markup
        if markup.hasRedactions {
            // ⚠⚠ In the source raster, before the transform samples it: the copy then cannot depend
            // on a single pixel that was under the box (`MarkupRender.redactionsInSource`).
            guard let raster = context.createCGImage(
                      source, from: CGRect(x: 0, y: 0, width: CGFloat(w), height: CGFloat(h)),
                      format: .RGBAh, colorSpace: MarkupDrawing.deepSpace
                  ),
                  let filled = MarkupDrawing.redacted(raster, MarkupRender.shared.redactionsInSource(markup: markup, sourceWidth: w, sourceHeight: h))
            else { return nil }
            source = CIImage(cgImage: filled)
        }
        guard let drawn = draw(source, edit: recipe.transform),
              let coloured = colour(drawn, recipe: recipe, vignette: NormRect.companion.FULL).map(onBlack),
              var cg = context.createCGImage(coloured, from: coloured.extent) else { return nil }
        let ops = MarkupRender.shared.scene(
            markup: markup, plan: recipe.transform.plan(sourceWidth: w, sourceHeight: h, includeCrop: true), sourceWidth: w, sourceHeight: h
        )
        if !ops.isEmpty {
            guard let marked = MarkupDrawing.over(cg, ops) else { return nil }
            cg = marked
        }
        return jpeg(cg)
    }

    static func jpeg(_ image: CGImage, quality: CGFloat = 0.92) -> Foundation.Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        let props: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: quality,
            // ⚠⚠ Explicitly 1: the pixels are already upright, and any other tag turns them twice.
            kCGImagePropertyOrientation: 1,
        ]
        CGImageDestinationAddImage(dest, image, props as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Foundation.Data
    }

    /// A preview frame as a `UIImage`. `cropping`: the Crop tab's whole frame (the box is an
    /// overlay, and the vignette sits on it); otherwise the cropped result, as a save writes it.
    static func preview(_ source: CIImage, recipe: EditRecipe, cropping: Bool) -> UIImage? {
        let vignette = cropping ? recipe.transform.viewCrop : NormRect.companion.FULL
        guard let drawn = draw(source, edit: recipe.transform, includeCrop: !cropping),
              let coloured = colour(drawn, recipe: recipe, vignette: vignette).map(onBlack),
              let cg = context.createCGImage(coloured, from: coloured.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    static func uiImage(_ source: CIImage) -> UIImage? {
        context.createCGImage(source, from: source.extent).map { UIImage(cgImage: $0) }
    }
}
