import SwiftUI
import TenshuSdk

// Timeline's editorial voice (ADR 0021): every display/title/heading is an italic serif — day
// dates, moment titles, place & trip names. The brand ships no serif, so like the Compose app's
// interim we use the platform-generic serif (`.serif` design) behind one accessor. The dominant
// colour of a moment's cover photo tints chrome (personalisation) — the load-bearing field the
// domain model keeps.

extension Font {
    /// The editorial serif used for headings/titles across Timeline (ADR 0021).
    static func timelineSerif(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .serif).italic()
    }
}

extension Color {
    /// Parse a `#RRGGBB` dominant-colour string (the model's `Photo.dominantColor`). Falls back to
    /// a neutral secondary when the string isn't a well-formed hex.
    init(timelineHex hex: String?) {
        guard let rgb = TimelineRgb(hex: hex) else {
            self = .secondary
            return
        }
        self = rgb.color
    }
}

// MARK: - Personalisation (the iOS twin of `ui/theme/Personalisation.kt`)

/// A plain sRGB triple — the arithmetic space personalisation mixes in. SwiftUI's `Color` cannot be
/// interpolated (and a *dynamic* colour has no components until a trait environment resolves it), so
/// the Amethyst base tokens are carried literally and the lerps happen here, exactly as Compose's
/// `lerp(Color, Color, Float)` does.
struct TimelineRgb {
    var red: Double
    var green: Double
    var blue: Double

    /// Parses `#rrggbb` / `rrggbb` (the shared `parseHexColor` shape). Returns nil when malformed.
    init?(hex: String?) {
        guard let hex else { return nil }
        let body = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard body.count == 6, let value = UInt32(body, radix: 16) else { return nil }
        red = Double((value >> 16) & 0xFF) / 255.0
        green = Double((value >> 8) & 0xFF) / 255.0
        blue = Double(value & 0xFF) / 255.0
    }

    init(_ red: Double, _ green: Double, _ blue: Double) {
        self.red = red
        self.green = green
        self.blue = blue
    }

    var color: Color { Color(.sRGB, red: red, green: green, blue: blue) }

    func lerp(to other: TimelineRgb, _ fraction: Double) -> TimelineRgb {
        let t = min(max(fraction, 0), 1)
        return TimelineRgb(
            red + (other.red - red) * t,
            green + (other.green - green) * t,
            blue + (other.blue - blue) * t
        )
    }
}

/// The calm tint at the heart of the Chapters look (`timeline-app.md` §5) — the SwiftUI port of
/// `app.tenshu.timeline.ui.theme.Personalisation`, including its numbers.
///
/// The shared view models have always published a `personalisationStrength`; iOS ignored it and
/// painted raw dominant colours instead, which is *more* saturated than the design allows and drops
/// the one knob that turns personalisation off. This restores the Compose contract: it only shifts
/// **accent** marks and lightens **soft** surfaces toward a photo's dominant colour, and at
/// `strength <= 0` (or with no photo) it is a clean no-op — pure Amethyst.
///
/// ⚠ The base roles are passed by colour *scheme* rather than read from a dynamic `UIColor`: mixing
/// needs real components, and resolving a dynamic colour outside a trait collection silently yields
/// the light variant. Call sites read `@Environment(\.colorScheme)`.
struct TimelinePersonalisation {
    /// Accent marks (eyebrows, rail glyphs, small icons). Amethyst `primary` at strength 0.
    let accent: Color
    /// Soft surfaces (tinted circles, cards). Amethyst `primaryContainer` at strength 0.
    let soft: Color
    /// An extremely faint background wash — nil unless `strength > 0.4`.
    let tint: Color?

    // Amethyst Neutral (`AppColorTokens`, ADR 0001) — the same primary / primaryContainer pair the
    // Compose `rememberPersonalisation` sources from `MaterialTheme.colorScheme`.
    private static let lightPrimary = TimelineRgb(0x5A / 255.0, 0x4A / 255.0, 0x96 / 255.0)
    private static let lightPrimaryContainer = TimelineRgb(0xE6 / 255.0, 0xDE / 255.0, 0xFF / 255.0)
    private static let darkPrimary = TimelineRgb(0xCB / 255.0, 0xC0 / 255.0, 0xFF / 255.0)
    private static let darkPrimaryContainer = TimelineRgb(0x42 / 255.0, 0x34 / 255.0, 0x7D / 255.0)

    static func resolve(
        dominantHex: String?,
        strength: Float,
        scheme: ColorScheme
    ) -> TimelinePersonalisation {
        let dark = scheme == .dark
        let primary = dark ? darkPrimary : lightPrimary
        let primaryContainer = dark ? darkPrimaryContainer : lightPrimaryContainer
        guard strength > 0, let dominant = TimelineRgb(hex: dominantHex) else {
            return TimelinePersonalisation(
                accent: primary.color,
                soft: primaryContainer.color,
                tint: nil
            )
        }
        let s = Double(strength)
        let darkened = dominant.lerp(to: TimelineRgb(0, 0, 0), 0.12)
        let softened = dominant.lerp(to: TimelineRgb(1, 1, 1), 0.55)
        return TimelinePersonalisation(
            accent: primary.lerp(to: darkened, min(1, s * 0.7)).color,
            soft: primaryContainer.lerp(to: softened, min(1, s * 0.85)).color,
            tint: strength > 0.4 ? TimelineRgb(1, 1, 1).lerp(to: dominant, 0.06).color : nil
        )
    }
}

extension MomentKind {
    /// SF Symbol for the moment's rail glyph (mirrors the Compose `TimelineIcons` intent).
    var sfSymbol: String {
        switch self {
        case .activity: return "figure.walk"
        case .visit: return "mappin.and.ellipse"
        case .event: return "sparkles"
        case .quiet: return "moon.stars"
        // M5-lite's kind, missed when it landed: this fell through to the default "circle"
        // in the feed rail and the detail hero.
        case .movement: return "point.topleft.down.curvedto.point.bottomright.up"
        @unknown default: return "circle"
        }
    }
}

/// A place role's display name — the Swift twin of the Compose `placeRoleName` (`PlaceRoleUi.kt`),
/// including its contract.
///
/// ⚠ Returning nil rather than the raw token is the point: a newer peer may sync `gym`, and printing
/// a wire string at the user is worse than saying nothing. The unknown role is still *stored* and
/// still syncs; it just is not rendered.
func timelinePlaceRoleName(_ role: String?) -> String? {
    guard let role else { return nil }
    switch role {
    case PlaceRoleNames.shared.HOME: return "Home"
    case PlaceRoleNames.shared.WORK: return "Work"
    default: return nil
    }
}

// A small-caps tracked section eyebrow, reused across screens ("TIMELINE", "PLACES", day labels).
struct Eyebrow: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased())
            .font(.system(.caption, design: .default).weight(.semibold))
            .tracking(2)
            .foregroundStyle(.secondary)
    }
}
