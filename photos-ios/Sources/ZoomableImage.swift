import SwiftUI
import TenshuSdk
import UIKit

// One viewer page's photograph, zoomable and dismissable (photos-ios.md §8.13).
//
// ⚠⚠ The gesture priority is Compose's, decided once (`PhotoViewerScreen`, D4) — *while
// zoomed in, the drag belongs to the pan*:
//
// | Gesture                            | Owner                                              |
// |------------------------------------|----------------------------------------------------|
// | two fingers                        | the zoom, always — a pinch is never a page swipe   |
// | one finger, magnified              | the pan                                            |
// | one finger down, at fit-scale      | drag to dismiss                                    |
// | one finger sideways, at fit-scale  | the pager                                          |
//
// UIKit's nesting gives the last two rows for free, and that is why this is a `UIScrollView`: at
// fit-scale it has nothing to scroll, so a sideways drag passes to the pager; magnified it always
// bounces horizontally, so it keeps the drag and the pager is unreachable until the photograph is
// back at fit-scale. The numbers — how far it zooms, how far is far enough to close — are the shared
// `ViewerGestures`, so both apps zoom and close at the same distances.

struct ZoomableImage: UIViewRepresentable {
    let image: UIImage
    /// The photograph's identity — its path. ⚠⚠ The zoom belongs to the PHOTOGRAPH, not to the bitmap:
    /// a sharper rung of the same photograph replaces the picture and keeps the user's zoom (Compose
    /// keys its zoom on the path the same way). Resetting on every new `UIImage` would snap a pinch
    /// back to fit-scale mid-gesture when the pulled rung landed.
    let identity: String
    /// A single tap: the chrome toggle.
    let onTap: () -> Void
    /// A drag down at fit-scale let go past the line.
    let onDismiss: () -> Void
    /// A drag UP at fit-scale — the About sheet (Compose's "upward scrolls into About").
    var onSwipeUp: () -> Void = {}

    func makeUIView(context: Context) -> ZoomingScrollView {
        let view = ZoomingScrollView()
        view.onTap = onTap
        view.onDismiss = onDismiss
        view.onSwipeUp = onSwipeUp
        view.show(image, identity: identity)
        return view
    }

    func updateUIView(_ view: ZoomingScrollView, context: Context) {
        view.onTap = onTap
        view.onDismiss = onDismiss
        view.onSwipeUp = onSwipeUp
        if view.identity != identity {
            // Another photograph reusing the page: it starts at fit-scale.
            view.show(image, identity: identity)
        } else if view.shown !== image {
            // A sharper rung of the SAME photograph: the picture changes, the zoom stays.
            view.replace(image)
        }
    }
}

final class ZoomingScrollView: UIScrollView, UIScrollViewDelegate, UIGestureRecognizerDelegate {
    var onTap: (() -> Void)?
    var onDismiss: (() -> Void)?
    var onSwipeUp: (() -> Void)?
    private(set) weak var shown: UIImage?
    private(set) var identity: String?

    private let imageView = UIImageView()
    private lazy var dismissPan = UIPanGestureRecognizer(target: self, action: #selector(dismissPanned(_:)))

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        minimumZoomScale = 1
        maximumZoomScale = CGFloat(ViewerGestures.shared.MAX_ZOOM)
        showsVerticalScrollIndicator = false
        showsHorizontalScrollIndicator = false
        decelerationRate = .fast
        contentInsetAdjustmentBehavior = .never
        backgroundColor = .clear
        imageView.contentMode = .scaleAspectFit
        imageView.isAccessibilityElement = false
        addSubview(imageView)
        // The UI tests' handle on the page (`ViewerGestureUITests`).
        isAccessibilityElement = true
        accessibilityIdentifier = "viewerPhoto"
        accessibilityLabel = "Photo"

        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped))
        addGestureRecognizer(tap)
        dismissPan.delegate = self
        // ⚠ ONE finger: two always belong to the zoom. Unconstrained, a pinch whose centre drifted
        // downward at fit-scale could start the dismiss and shrink or close the photograph under
        // it.
        dismissPan.maximumNumberOfTouches = 1
        addGestureRecognizer(dismissPan)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func show(_ image: UIImage, identity: String) {
        self.identity = identity
        shown = image
        imageView.image = image
        setZoomScale(1, animated: false)
        setNeedsLayout()
    }

    /// The same photograph, sharper: only the pixels change — zoom and pan stay where the user left
    /// them. ⚠ Every rung has the photograph's aspect ratio, so the fitted frame is unchanged.
    func replace(_ image: UIImage) {
        shown = image
        imageView.image = image
    }

    /// The page size the base frame was fitted for.
    private var fittedFor: CGSize = .zero
    /// The point at the page's centre, as a fraction of the photograph — recorded only while the page
    /// is the size its layout was fitted for, so a resize's own offset adjustment cannot overwrite it.
    private var lastFocus: CGPoint?

    private func recordFocus() {
        guard bounds.size == fittedFor, contentSize.width > 0, contentSize.height > 0 else { return }
        lastFocus = CGPoint(
            x: (contentOffset.x + bounds.width / 2) / contentSize.width,
            y: (contentOffset.y + bounds.height / 2) / contentSize.height
        )
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if zoomScale > 1.001 {
            // ⚠⚠ The page changed size WHILE magnified — a rotation, an iPad Split View resize. The
            // base frame was fitted for the old page, so refit it and keep the relative zoom and
            // the point the user was looking at; otherwise, until the user returns to 1×, the
            // photograph is the wrong size, with wrong pan limits.
            if bounds.size != fittedFor, fittedFor != .zero { refit() }
            reportZoom()
            return
        }
        // ⚠⚠ The zoomed view is the photograph's ASPECT-FITTED rectangle, not the page: a page-sized
        // view zooms its transparent letterbox too, and a landscape photograph on a portrait phone
        // could then be panned into its own empty margins — partly or entirely off screen.
        // Centred by the insets `scrollViewDidZoom` keeps.
        let fitted = fittedSize()
        imageView.frame = CGRect(origin: .zero, size: fitted)
        contentSize = fitted
        fittedFor = bounds.size
        centre()
        updateBounce()
        reportZoom()
    }

    /// Refit the base frame for the current page while magnified, keeping the zoom and the focal point.
    private func refit() {
        let zoom = zoomScale
        let oldBase = imageView.bounds.size
        // Where the OLD page's centre sat in the photograph, as a fraction of it. ⚠⚠ The old page's
        // half-size, from `fittedFor` — `bounds` is already the new one here, and the offset and base
        // still describe the old layout: mixing the two moves the focus and the photograph jumps to
        // another region on rotation.
        //
        // ⚠⚠ And not from `contentOffset` either: by the time this runs UIKit has already moved the
        // offset for the new size (seen: 313 where the focus needed ~605). So the focus is RECORDED
        // while the page is still the size its layout was fitted for (`lastFocus`), and read here.
        let old = fittedFor
        let focus = lastFocus ?? CGPoint(
            x: oldBase.width > 0 ? (contentOffset.x + old.width / 2) / (oldBase.width * zoom) : 0.5,
            y: oldBase.height > 0 ? (contentOffset.y + old.height / 2) / (oldBase.height * zoom) : 0.5
        )
        setZoomScale(1, animated: false)
        let fitted = fittedSize()
        imageView.transform = .identity
        imageView.frame = CGRect(origin: .zero, size: fitted)
        contentSize = fitted
        fittedFor = bounds.size
        setZoomScale(zoom, animated: false)
        lastFocus = focus
        centre()
        let maxX = max(contentSize.width - bounds.width + contentInset.right, -contentInset.left)
        let maxY = max(contentSize.height - bounds.height + contentInset.bottom, -contentInset.top)
        contentOffset = CGPoint(
            x: min(max(focus.x * contentSize.width - bounds.width / 2, -contentInset.left), maxX),
            y: min(max(focus.y * contentSize.height - bounds.height / 2, -contentInset.top), maxY)
        )
        updateBounce()
    }

    /// The UI tests' reading of the page: the zoom and the unscaled, fitted base size.
    private func reportZoom() {
        let base = imageView.bounds.size
        // The point at the page's centre, as a fraction of the photograph.
        let fx = contentSize.width > 0 ? (contentOffset.x + bounds.width / 2) / contentSize.width : 0.5
        let fy = contentSize.height > 0 ? (contentOffset.y + bounds.height / 2) / contentSize.height : 0.5
        accessibilityValue = String(format: "zoom=%.2f base=%.0fx%.0f focus=%.3f,%.3f",
                                    zoomScale, base.width, base.height, fx, fy)
    }

    /// The photograph's size when fitted into the page, keeping its aspect ratio.
    private func fittedSize() -> CGSize {
        guard let size = imageView.image?.size, size.width > 0, size.height > 0,
              bounds.width > 0, bounds.height > 0 else { return bounds.size }
        let scale = min(bounds.width / size.width, bounds.height / size.height)
        return CGSize(width: size.width * scale, height: size.height * scale)
    }

    /// Keep a photograph smaller than the page centred in it — at fit-scale and while it grows.
    private func centre() {
        let x = max((bounds.width - contentSize.width) / 2, 0)
        let y = max((bounds.height - contentSize.height) / 2, 0)
        contentInset = UIEdgeInsets(top: y, left: x, bottom: y, right: x)
    }

    private func updateBounce() {
        // ⚠⚠ The pager rule: magnified, this view keeps every horizontal drag (it always bounces), so
        // the pager cannot take one; at fit-scale it has nothing to scroll and lets them through.
        let magnified = zoomScale > 1.001
        alwaysBounceHorizontal = magnified
        alwaysBounceVertical = magnified
    }

    // MARK: Zoom

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        centre()
        updateBounce()
        recordFocus()
        reportZoom()
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        recordFocus()
        reportZoom()
    }

    // MARK: Tap

    @objc private func tapped() { onTap?() }

    // MARK: Drag to dismiss

    /// ⚠⚠ Claims only a drag that starts at FIT-SCALE and is mostly DOWNWARD — decided once, when the
    /// gesture begins (Compose latches its owner the same way; an owner that changes under the finger
    /// is the bug D4 exists against). Magnified, the drag is the pan's; sideways, it is the pager's.
    override func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
        guard recognizer === dismissPan else { return super.gestureRecognizerShouldBegin(recognizer) }
        guard zoomScale <= 1.001 else { return false }
        // Vertical, either way: down is the dismiss, up is About. Sideways stays the pager's.
        let velocity = dismissPan.velocity(in: self)
        return abs(velocity.y) > abs(velocity.x)
    }

    /// How far up a drag must travel to open About — enough to be meant, short of a scroll.
    private static let aboutTravel: CGFloat = 60

    @objc private func dismissPanned(_ pan: UIPanGestureRecognizer) {
        let dy = pan.translation(in: self).y
        let dragDown = max(dy, 0)
        let height = Float(bounds.height)
        if dy < 0 {
            // Upward: no shrink and no dismiss — past the travel, the About sheet opens on release.
            if pan.state == .ended, -dy > Self.aboutTravel { onSwipeUp?() }
            if pan.state == .ended || pan.state == .cancelled { springBack() }
            return
        }
        switch pan.state {
        case .changed:
            // The shrink is the feedback that says "let go and this closes" (`ViewerGestures`).
            let scale = CGFloat(ViewerGestures.shared.dismissScale(dragDown: Float(dragDown), height: height))
            imageView.transform = CGAffineTransform(translationX: 0, y: dragDown).scaledBy(x: scale, y: scale)
        case .ended:
            if ViewerGestures.shared.dismisses(dragDown: Float(dragDown), height: height) {
                onDismiss?()
            } else {
                springBack()
            }
        case .cancelled, .failed:
            springBack()
        default:
            break
        }
    }

    private func springBack() {
        UIView.animate(withDuration: 0.25, delay: 0, usingSpringWithDamping: 0.85, initialSpringVelocity: 0) {
            self.imageView.transform = .identity
        }
    }
}
