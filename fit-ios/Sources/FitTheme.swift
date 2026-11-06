import SwiftUI
import TenshuSdk

// Shared visual language + formatting for Fit. The domain model emits SI data (metres, seconds, kJ)
// and typed enums (fit-app.md §5 "data, not display strings"); the UI formats + colours here.

extension ActivityType {
    var sfSymbol: String {
        switch self {
        case .running: return "figure.run"
        case .walking: return "figure.walk"
        case .cycling: return "figure.outdoor.cycle"
        case .strength: return "dumbbell"
        case .other: return "figure.mixed.cardio"
        @unknown default: return "figure.mixed.cardio"
        }
    }
    var tint: Color {
        switch self {
        case .running: return .orange
        case .walking: return .green
        case .cycling: return .blue
        case .strength: return .purple
        case .other: return .teal
        @unknown default: return .gray
        }
    }
    var label: String {
        switch self {
        case .running: return "Run"
        case .walking: return "Walk"
        case .cycling: return "Ride"
        case .strength: return "Strength"
        case .other: return "Workout"
        @unknown default: return "Workout"
        }
    }
}

extension CategoryId {
    var title: String {
        switch self {
        case .activity: return "Activity"
        case .body: return "Body"
        case .vitals: return "Vitals"
        case .sleep: return "Sleep"
        case .nutrition: return "Nutrition"
        case .cycle: return "Cycle"
        case .medical: return "Medical"
        @unknown default: return "Category"
        }
    }
    var sfSymbol: String {
        switch self {
        case .activity: return "flame.fill"
        case .body: return "figure"
        case .vitals: return "heart.fill"
        case .sleep: return "bed.double.fill"
        case .nutrition: return "fork.knife"
        case .cycle: return "drop.fill"
        case .medical: return "cross.case.fill"
        @unknown default: return "square.grid.2x2"
        }
    }
    var tint: Color {
        switch self {
        case .activity: return .orange
        case .body: return .indigo
        case .vitals: return .red
        case .sleep: return .blue
        case .nutrition: return .green
        case .cycle: return .pink
        case .medical: return .teal
        @unknown default: return .gray
        }
    }
}

extension Source {
    var label: String {
        switch self {
        case .recorded: return "Recorded in Fit"
        case .healthConnect: return "Health Connect"
        case .appleHealth: return "Apple Health"
        case .file: return "Imported file"
        case .manual: return "Manual"
        case .detected: return "Detected"
        @unknown default: return "Unknown"
        }
    }

    /// The workout footer's provenance when no device is named (`deviceLabel` in `FitFormatting.kt`).
    var provenance: String {
        switch self {
        case .file: return "Imported from a file"
        default: return label
        }
    }
}

// MARK: - Formatting

enum FitUnitFormat {
    /// Metres → "x.x km" (or "x m" under ~1 km).
    static func distance(_ metres: Int) -> String {
        if metres >= 1000 { return String(format: "%.1f km", Double(metres) / 1000.0) }
        return "\(metres) m"
    }

    /// Seconds → "1 h 22 min" / "34 min".
    static func duration(_ seconds: Int) -> String {
        let m = max(0, seconds) / 60
        let h = m / 60
        let rem = m % 60
        if h > 0 { return rem > 0 ? "\(h) h \(rem) min" : "\(h) h" }
        return "\(m) min"
    }

    /// Pace seconds/km → "5:32 /km".
    static func pace(_ secPerKm: Int) -> String {
        let m = secPerKm / 60
        let s = secPerKm % 60
        return String(format: "%d:%02d /km", m, s)
    }

    /// kJ → "kcal" (the display convention), 1 kcal ≈ 4.184 kJ.
    static func energy(_ kj: Int) -> String {
        "\(Int(Double(kj) / 4.184)) kcal"
    }
}

extension Kotlinx_datetimeLocalDateTime {
    /// "HH:mm" for a workout start/end.
    var hhmm: String { String(format: "%02d:%02d", Int(hour), Int(minute)) }
}

/// ⚠⚠ A `LocalDate`'s fields are ISO (Gregorian) year/month/day, so every `LocalDate` ⇄ `Date`
/// conversion goes through this calendar — `Calendar.current` under a Buddhist, Japanese or Islamic
/// system calendar reads 2026-11-01 as a different day. Formatters and pickers still present the
/// resulting `Date` in the user's own calendar and locale. Computed, so it follows a time-zone change.
var isoCalendar: Calendar { Calendar(identifier: .gregorian) }

extension Kotlinx_datetimeLocalDate {
    /// Local midnight of this day — via `isoCalendar`, never `Calendar.current`.
    var asDate: Date? {
        isoCalendar.date(from: DateComponents(year: Int(year), month: Int(monthNumber), day: Int(dayOfMonth)))
    }

    /// "5 Jun 2026" for record dates.
    var display: String {
        let months = ["", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        let m = Int(monthNumber)
        return "\(Int(dayOfMonth)) \(months.indices.contains(m) ? months[m] : "") \(Int(year))"
    }
}

// A big serif-free stat used across cards.
struct StatBlock: View {
    let value: String
    let label: String
    var tint: Color = .primary
    var body: some View {
        VStack(spacing: 2) {
            Text(value).font(.title3.weight(.semibold)).foregroundStyle(tint)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

// iOS 17+ ContentUnavailableView stand-in (app targets iOS 16).
struct FitEmptyState: View {
    let title: String
    let systemImage: String
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage).font(.largeTitle).foregroundStyle(.tertiary)
            Text(title).font(.headline).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
