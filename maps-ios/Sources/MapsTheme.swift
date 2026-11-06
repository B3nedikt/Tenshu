import SwiftUI
import TenshuSdk

// Maps' shared SwiftUI chrome — the twin of `:maps-shared-ui`'s `components/` + `theme/` packages
// (`maps-app.md` §6 Ph4 / handoff §3–§4). Everything here is presentation only; every value it
// renders comes from `:maps-shared-logic`.

// MARK: - Glyphs

/// The category glyph, as SF Symbols. `:maps-shared-ui`'s `MapsIcons.forCategory` maps the design's
/// Lucide set onto `material-icons-extended` approximations; this is the same mapping onto Apple's
/// set, which is what a native iOS surface should wear.
enum MapsGlyph {
    static func category(_ category: PlaceCategory) -> String {
        switch category {
        case .home: return "house"
        case .work: return "briefcase"
        case .cafe: return "cup.and.saucer"
        case .restaurant: return "fork.knife"
        case .park: return "tree"
        case .store: return "bag"
        case .culture: return "building.columns"
        case .transit: return "tram"
        case .viewpoint: return "mountain.2"
        }
    }

    /// Provenance glyph on a "For you" card (`MapsIcons.forReason`).
    static func reason(_ kind: ReasonKind) -> String {
        switch kind {
        case .timeline: return "clock.arrow.circlepath"
        case .contact: return "person.2"
        case .curated: return "sparkles"
        }
    }

    /// The category's own display name — the Compose surface renders `place.category.name` raw, so
    /// this is the same string, title-cased for a Swift enum's lowercase export.
    static func categoryName(_ category: PlaceCategory) -> String {
        let raw = category.name
        return raw.prefix(1).uppercased() + raw.dropFirst().lowercased()
    }
}

/// The category avatar — the circle + glyph `CategoryAvatar` draws.
///
/// ⚠ **Deliberately un-tinted.** The by-category accent (`categoryAccent`) is an *opt-in* seam and
/// its Compose default is `enabled = false`, which resolves to the plain Amethyst `primary` /
/// `secondaryContainer` roles. Reproducing the oklch derivation here would make iOS the only surface
/// where the tint is on by default, so this mirrors the default rather than the option.
struct CategoryAvatar: View {
    let category: PlaceCategory
    var size: CGFloat = 48

    var body: some View {
        ZStack {
            Circle().fill(Color.accentColor.opacity(0.16))
            Image(systemName: MapsGlyph.category(category))
                .font(.system(size: size * 0.44, weight: .regular))
                .foregroundStyle(Color.accentColor)
        }
        .frame(width: size, height: size)
    }
}

// MARK: - Primitives

/// The rounded surface every Maps list item and section sits on (`MapsCard`).
struct MapsCard<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        content()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }
}

/// A small-caps section eyebrow (`Eyebrow` / `SectionLabel`).
struct MapsEyebrow: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.caption2.weight(.semibold))
            .tracking(1.4)
            .foregroundStyle(.secondary)
    }
}

/// The "Home" / "Work" chip beside a name (`LabelChip`).
struct MapsLabelChip: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(Color.accentColor.opacity(0.15), in: Capsule())
            .foregroundStyle(Color.accentColor)
    }
}

/// The lock-and-line honesty cue every Maps surface ends with (`TrustCue`).
struct MapsTrustCue: View {
    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "lock").font(.caption2)
            Text("Places are yours. They stay on your devices, end-to-end encrypted.")
                .font(.caption2)
        }
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity)
        .multilineTextAlignment(.center)
    }
}

/// A quiet informational strip (`DetailNote`).
struct MapsNote: View {
    let systemImage: String
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: systemImage).font(.caption)
            Text(text).font(.caption)
            Spacer(minLength: 0)
        }
        .foregroundStyle(.secondary)
        .padding(12)
        .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Model conveniences

extension MapsPlace {
    /// Whether this place has a real coordinate to put on a map.
    ///
    /// ⚠⚠ Nullability is **not** the gate. `MapsPlaceProjection` fills `location` unconditionally
    /// from the store's `latE7`/`lngE7`, which are `0` for a place that has never been located — and
    /// `(0, 0)` is a specific point in the Atlantic, so an ungated hero would render a confidently
    /// wrong patch of empty sea instead of an honest placeholder. Same gate `PlaceDetailScreen`
    /// applies on Compose, and the same one `timeline-ios`'s `Place.hasCoordinate` records (D11).
    var hasCoordinate: Bool {
        guard let l = location else { return false }
        return l.latitude != 0 || l.longitude != 0
    }

    /// "3 days ago" for the last visit, via `:maps-shared-logic`'s locale-independent bucketing —
    /// the split ADR 0016 mandates (logic emits the form + magnitude, the UI writes the words).
    func lastVisitText(today: Kotlinx_datetimeLocalDate) -> String? {
        guard let visit = lastVisit else { return nil }
        let r = MapsFormat.shared.relativeVisit(today: today, visit: visit)
        switch r.form {
        case .today: return "Today"
        case .yesterday: return "Yesterday"
        case .daysAgo: return "\(r.value) days ago"
        case .weeksAgo: return r.value == 1 ? "1 week ago" : "\(r.value) weeks ago"
        case .monthsAgo: return r.value == 1 ? "1 month ago" : "\(r.value) months ago"
        case .yearsAgo: return r.value == 1 ? "1 year ago" : "\(r.value) years ago"
        }
    }

    /// "12 visits" / "1 visit" / nil when the store cannot answer (`visitCountText`).
    var visitCountText: String? {
        guard visitCount > 0 else { return nil }
        return visitCount == 1 ? "1 visit" : "\(visitCount) visits"
    }
}

extension Kotlinx_datetimeLocalDate {
    /// "5 Jun 2026" — the same shape `fit-ios` renders its record dates in.
    var display: String {
        let months = ["", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        let m = Int(monthNumber)
        return "\(Int(dayOfMonth)) \(months.indices.contains(m) ? months[m] : "") \(Int(year))"
    }
}

/// Coarse, honest size for a region bundle — GB above 1024 MB, else whole MB. The Swift twin of
/// `PlaceDetailScreen.formatRegionSize`; the size is the point, because a region is hundreds of
/// megabytes and the map already works without it.
func formatRegionSize(_ bytes: Int64) -> String {
    let mib = Double(bytes) / (1024.0 * 1024.0)
    if mib >= 1024.0 {
        return "\((mib / 1024.0 * 10).rounded() / 10.0) GB"
    }
    return "\(Int64(mib)) MB"
}
