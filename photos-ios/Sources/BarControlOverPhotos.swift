import SwiftUI

// A bar control over photographs (photos-ios.md §8.21, rule 11).
//
// ⚠⚠ Why the system's own item is not enough: while photographs lie under the Library's bar the bar
// is dark-schemed (rule 7 — the title and the status bar are white over the dark fade). On iOS 26.x
// the system's glass still ADAPTS to what is behind it — over a bright photograph it turns light —
// but the forced scheme pins the glyph white: white on light glass, 1.4–2.7:1 measured on 26.5.
// (iOS 27.0 no longer flips the glass, and there the same item read 3:1.) Nothing un-pins one item:
// a colour scheme set on the label pins it to that scheme instead, and dropping the bar's scheme
// leaves 26.x's status bar black on the dark fade.
//
// So over photographs the control is the HIG's own pattern for that case (Materials, "Liquid
// Glass"): the CLEAR variant — "for components that float above media backgrounds — such as photos
// and videos" — which does not adapt, over the dimming layer the same page asks for: "If the
// underlying content is bright, consider adding a dark dimming layer of 35% opacity." A white label
// on that holds its contrast whatever scrolls under it. At rest, over the page, it is the plain
// system item, untouched.

/// A toolbar button that is the system's at rest and clear glass over a 35 % dim, with a white
/// label, while photographs lie under the bar.
///
/// ⚠ Pair it with `barItemOverPhotos(_:)` on its `ToolbarItem`, from the SAME flag: the item's shared
/// glass background has to step aside exactly when this one draws its own.
/// ⚠ One `Button` in both states, so its identity, its accessibility element and anything a caller
/// hangs on it (a label, an identifier) do not change with the backdrop.
struct BarButtonOverPhotos: View {
    enum Label {
        case symbol(String)
        case text(String)
    }

    let label: Label
    let overPhotos: Bool
    let action: () -> Void

    /// ⚠ iOS 26 gives a text item 4 pt more room either side once its shared background is hidden;
    /// iOS 27 does not, and moves the item 4 pt towards the edge instead. Taken back here, the text
    /// stands where the system's stood on both (measured on 26.5 and 27.0).
    private static var textInset: CGFloat {
        if #available(iOS 27.0, *) { return 0 }
        return -4
    }

    var body: some View {
        Button(action: action) {
            // ⚠⚠ The numbers are the system item's own metrics, MEASURED (iOS 26.5 and 27.0), so that
            // the label and the glass stand where the system's stood: its item is 36 pt wide whatever
            // the symbol's own width, and its shared background reaches 4 pt past the item all round.
            // `BarControlUITests` pins the result — the control's centre does not move.
            switch label {
            case .symbol(let name):
                Image(systemName: name)
                    .modifier(OverPhotos(active: overPhotos, width: 24, inset: 0, overhang: 10))
            case .text(let title):
                Text(title)
                    .dynamicTypeSize(...DynamicTypeSize.xxLarge) // the bar does not grow
                    .modifier(OverPhotos(active: overPhotos, width: nil, inset: Self.textInset, overhang: 16 - Self.textInset))
            }
        }
    }

    private struct OverPhotos: ViewModifier {
        let active: Bool
        let width: CGFloat?
        let inset: CGFloat
        let overhang: CGFloat

        func body(content: Content) -> some View {
            if #available(iOS 26.0, *), active {
                content
                    .frame(width: width)
                    .padding(.horizontal, inset)
                    .modifier(ClearGlassOverPhotos(overhang: overhang))
            } else {
                content
            }
        }
    }
}

extension ToolbarContent {
    /// Hides the item's shared glass background while `BarButtonOverPhotos` draws its own. Nothing
    /// before iOS 26, where a bar item has no glass.
    @ToolbarContentBuilder
    func barItemOverPhotos(_ overPhotos: Bool) -> some ToolbarContent {
        if #available(iOS 26.0, *) {
            sharedBackgroundVisibility(overPhotos ? .hidden : .automatic)
        } else {
            self
        }
    }
}

@available(iOS 26.0, *)
private struct ClearGlassOverPhotos: ViewModifier {
    /// How far the glass reaches past the label either side.
    let overhang: CGFloat
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    /// The system's shared background is 44 pt tall — a 44 pt circle round a glyph.
    private static let height: CGFloat = 44

    func body(content: Content) -> some View {
        content
            .foregroundStyle(.white)
            // ⚠⚠ A BACKGROUND, and the room it needs taken back: the label is laid out exactly as the
            // system's item lays it out, so the control cannot move, or the bar grow, when the
            // backdrop changes. The glass overhangs the label as the system's shared background does.
            .padding(.horizontal, overhang)
            .background {
                // ⚠ The dim is the HIG's 35 %; more only where the user has asked for less glass or
                // more contrast, and then it is what carries the label.
                Color.black.opacity(dim)
                    .frame(minHeight: Self.height)
                    .clipShape(Capsule())
                    .glassEffect(.clear, in: .capsule)
            }
            // ⚠ The hit target: on 27.0 the whole glass; on 26.5 only the 36 pt item — the outer 4 pt
            // of glass either side takes no tap, and a `contentShape` past the item cannot widen it
            // (UIKit hit-tests the bar item's own bounds first; measured). Widening the item itself
            // would move the control between states, which is the one thing this must not do.
            .padding(.horizontal, -overhang)
    }

    private var dim: Double {
        if reduceTransparency { return 0.8 }
        return contrast == .increased ? 0.6 : 0.35
    }
}
